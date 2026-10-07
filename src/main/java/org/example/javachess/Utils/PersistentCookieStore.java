package org.example.javachess.Utils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cookie store for the Java HTTP stack ({@code java.net.CookieHandler}) persisted to
 * {@code ~/.javachess/cookies.json} (owner-only permissions, atomic writes).
 *
 * <p>Cookies keep their absolute expiry time across restarts; host matching follows RFC 6265 (a cookie for
 * {@code chess.com} is sent to {@code www.chess.com} but never to {@code evilchess.com}). Cookie values are never
 * logged. The integrated browser (JCEF) keeps its own cookies in its cache folder.
 */
public class PersistentCookieStore implements CookieStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentCookieStore.class);

    private record Entry(HttpCookie cookie, long expiresAtMs) {
        boolean expired(long now) {
            return expiresAtMs >= 0 && now >= expiresAtMs;
        }
    }

    /** Key: "domain|name|path". */
    private final Map<String, Entry> cookieJar = new ConcurrentHashMap<>();
    private final Path cookieFile;

    public PersistentCookieStore() {
        this(AppPaths.cookiesFile());
        migrateLegacy();
    }

    public PersistentCookieStore(Path file) {
        this.cookieFile = file;
        loadCookies();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveCookies, "cookie-store-save"));
    }

    private void migrateLegacy() {
        Path legacy = AppPaths.legacyDir().resolve("cookies.json");
        if (cookieJar.isEmpty() && Files.isRegularFile(legacy) && !legacy.equals(cookieFile)) {
            try {
                Files.copy(legacy, cookieFile);
                AppPaths.restrictToOwner(cookieFile);
                Files.delete(legacy);
                loadCookies();
                log.info("Moved cookies from {} to {}", legacy, cookieFile);
            } catch (IOException e) {
                log.warn("Cannot move old cookies file: {}", e.getMessage());
            }
        }
    }

    private void loadCookies() {
        if (!Files.exists(cookieFile)) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            String content = Files.readString(cookieFile, StandardCharsets.UTF_8).strip();
            if (content.isEmpty()) {
                return;
            }
            JSONArray jsonArray = new JSONArray(content);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject c = jsonArray.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                try {
                    HttpCookie cookie = new HttpCookie(c.getString("name"), c.getString("value"));
                    if (c.has("domain") && !c.isNull("domain")) {
                        cookie.setDomain(c.getString("domain"));
                    }
                    if (c.has("path") && !c.isNull("path")) {
                        cookie.setPath(c.getString("path"));
                    }
                    cookie.setSecure(c.optBoolean("secure", false));
                    cookie.setHttpOnly(c.optBoolean("httpOnly", false));
                    long expiresAt = c.has("expiresAt") ? c.getLong("expiresAt")
                            // old format stored the relative max-age: treat it as already counting from now
                            : c.optLong("maxAge", -1) >= 0 ? now + c.optLong("maxAge") * 1000 : -1;
                    Entry e = new Entry(cookie, expiresAt);
                    if (!e.expired(now)) {
                        cookieJar.put(generateKey(cookie), e);
                    }
                } catch (RuntimeException e) {
                    log.debug("Skipping unreadable cookie: {}", e.getMessage());
                }
            }
            log.debug("Loaded {} cookies", cookieJar.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Cookie file {} unreadable, starting with no cookies: {}", cookieFile, e.getMessage());
            cookieJar.clear();
        }
    }

    public synchronized void saveCookies() {
        long now = System.currentTimeMillis();
        JSONArray jsonArray = new JSONArray();
        for (Entry e : cookieJar.values()) {
            if (e.expired(now) || e.expiresAtMs() < 0) {
                continue; // session cookies are not persisted
            }
            HttpCookie cookie = e.cookie();
            JSONObject c = new JSONObject();
            c.put("name", cookie.getName());
            c.put("value", cookie.getValue());
            c.put("domain", cookie.getDomain());
            c.put("path", cookie.getPath());
            c.put("expiresAt", e.expiresAtMs());
            c.put("secure", cookie.getSecure());
            c.put("httpOnly", cookie.isHttpOnly());
            jsonArray.put(c);
        }
        try {
            AtomicFiles.writeString(cookieFile, jsonArray.toString(2), true);
        } catch (IOException ex) {
            log.warn("Cannot save cookies: {}", ex.getMessage());
        }
    }

    private static String generateKey(HttpCookie cookie) {
        String domain = cookie.getDomain() != null ? cookie.getDomain().toLowerCase(Locale.ROOT) : "nodomain";
        String path = cookie.getPath() != null ? cookie.getPath() : "/";
        return domain + "|" + cookie.getName() + "|" + path;
    }

    @Override
    public void add(URI uri, HttpCookie cookie) {
        if (cookie.getDomain() == null && uri != null) {
            cookie.setDomain(uri.getHost());
        } else if (uri != null && uri.getHost() != null && !domainMatches(cookie.getDomain(), uri.getHost())) {
            log.debug("Rejected cookie for a domain the site does not belong to");
            return; // a site may only set cookies for itself or its parent domains
        }
        if (cookie.getMaxAge() == 0) {
            cookieJar.remove(generateKey(cookie));
            return;
        }
        long expiresAt = cookie.getMaxAge() > 0 ? System.currentTimeMillis() + cookie.getMaxAge() * 1000 : -1;
        cookieJar.put(generateKey(cookie), new Entry(cookie, expiresAt));
    }

    @Override
    public List<HttpCookie> get(URI uri) {
        List<HttpCookie> result = new ArrayList<>();
        if (uri == null || uri.getHost() == null) {
            return result;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        long now = System.currentTimeMillis();
        for (Entry e : cookieJar.values()) {
            HttpCookie cookie = e.cookie();
            if (e.expired(now) || (cookie.getSecure() && !secure)) {
                continue;
            }
            if (domainMatches(cookie.getDomain(), host) && pathMatches(cookie.getPath(), path)) {
                result.add(cookie);
            }
        }
        return result;
    }

    /** RFC 6265 domain matching: exact host, or host ending with "." + domain. */
    static boolean domainMatches(String cookieDomain, String host) {
        if (cookieDomain == null || host == null) {
            return false;
        }
        String d = cookieDomain.toLowerCase(Locale.ROOT);
        if (d.startsWith(".")) {
            d = d.substring(1);
        }
        String h = host.toLowerCase(Locale.ROOT);
        return !d.isEmpty() && (h.equals(d) || h.endsWith("." + d));
    }

    static boolean pathMatches(String cookiePath, String requestPath) {
        if (cookiePath == null || cookiePath.isEmpty() || cookiePath.equals("/")) {
            return true;
        }
        return requestPath.equals(cookiePath) || requestPath.startsWith(
                cookiePath.endsWith("/") ? cookiePath : cookiePath + "/");
    }

    @Override
    public List<HttpCookie> getCookies() {
        long now = System.currentTimeMillis();
        List<HttpCookie> out = new ArrayList<>();
        cookieJar.values().stream().filter(e -> !e.expired(now)).forEach(e -> out.add(e.cookie()));
        return out;
    }

    @Override
    public List<URI> getURIs() {
        return new ArrayList<>();
    }

    @Override
    public boolean remove(URI uri, HttpCookie cookie) {
        return cookieJar.remove(generateKey(cookie)) != null;
    }

    @Override
    public boolean removeAll() {
        cookieJar.clear();
        saveCookies();
        return true;
    }
}
