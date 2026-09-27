package org.example.javachess.Utils;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class PersistentCookieStore implements CookieStore {
    // Store all cookies in a thread-safe set to avoid duplicates
    // Key: "domain|name|path" -> HttpCookie
    private final Map<String, HttpCookie> cookieJar = new ConcurrentHashMap<>();
    private final File cookieFile;

    public PersistentCookieStore() {
        this.cookieFile = new File("cookies.json");
        loadCookies();
        
        // Auto-save on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveCookies));
    }

    private void loadCookies() {
        if (!cookieFile.exists()) {
            System.out.println("[CookieStore] No cookies.json found. Starting fresh.");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(cookieFile))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) content.append(line);
            
            if (content.length() == 0) return;

            JSONArray jsonArray = new JSONArray(content.toString());
            System.out.println("[CookieStore] Loading " + jsonArray.length() + " cookies from file.");
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject c = jsonArray.getJSONObject(i);
                try {
                    HttpCookie cookie = new HttpCookie(c.getString("name"), c.getString("value"));
                    if (c.has("domain")) cookie.setDomain(c.getString("domain"));
                    if (c.has("path")) cookie.setPath(c.getString("path"));
                    if (c.has("maxAge")) cookie.setMaxAge(c.getLong("maxAge"));
                    if (c.has("secure")) cookie.setSecure(c.getBoolean("secure"));
                    if (c.has("httpOnly")) cookie.setHttpOnly(c.getBoolean("httpOnly"));
                    
                    // Validate expiry
                    if (!cookie.hasExpired()) {
                        add(null, cookie);
                    }
                } catch (Exception e) {
                    System.err.println("[CookieStore] Error parsing cookie: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // If corrupted, start fresh
            cookieJar.clear();
        }
    }

    public void saveCookies() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cookieFile))) {
            JSONArray jsonArray = new JSONArray();
            for (HttpCookie cookie : cookieJar.values()) {
                if (cookie.hasExpired()) continue;
                
                JSONObject c = new JSONObject();
                c.put("name", cookie.getName());
                c.put("value", cookie.getValue());
                c.put("domain", cookie.getDomain());
                c.put("path", cookie.getPath());
                c.put("maxAge", cookie.getMaxAge());
                c.put("secure", cookie.getSecure());
                c.put("httpOnly", cookie.isHttpOnly());
                jsonArray.put(c);
            }
            writer.write(jsonArray.toString(4));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String generateKey(HttpCookie cookie) {
        String domain = cookie.getDomain() != null ? cookie.getDomain().toLowerCase() : "nodomain";
        String path = cookie.getPath() != null ? cookie.getPath() : "/";
        return domain + "|" + cookie.getName() + "|" + path;
    }

    @Override
    public void add(URI uri, HttpCookie cookie) {
        if (cookie.hasExpired()) {
            remove(uri, cookie);
            return;
        }
        
        // Infer domain from URI if missing
        if (cookie.getDomain() == null && uri != null) {
            cookie.setDomain(uri.getHost());
        }
        
        // Deduplicate
        String key = generateKey(cookie);
        cookieJar.put(key, cookie);
        
        // Save periodically or on important changes could be added here
    }

    @Override
    public List<HttpCookie> get(URI uri) {
        List<HttpCookie> result = new ArrayList<>();
        String host = uri.getHost();
        String path = uri.getPath();
        if (path == null || path.isEmpty()) path = "/";

        for (HttpCookie cookie : cookieJar.values()) {
            if (cookie.hasExpired()) {
                continue;
            }

            if (domainMatches(cookie.getDomain(), host) && pathMatches(cookie.getPath(), path)) {
                result.add(cookie);
            }
        }
        return result;
    }

    private boolean domainMatches(String cookieDomain, String host) {
        if (cookieDomain == null || host == null) return false;
        String d = cookieDomain.toLowerCase();
        String h = host.toLowerCase();
        return h.equals(d) || h.endsWith(d) || (d.startsWith(".") && h.endsWith(d.substring(1)));
    }
    
    private boolean pathMatches(String cookiePath, String requestPath) {
        if (cookiePath == null) return true;
        return requestPath.startsWith(cookiePath);
    }

    @Override
    public List<HttpCookie> getCookies() {
        return new ArrayList<>(cookieJar.values());
    }

    @Override
    public List<URI> getURIs() {
        // Not strictly maintained, return empty or approximate
        return new ArrayList<>();
    }

    @Override
    public boolean remove(URI uri, HttpCookie cookie) {
        String key = generateKey(cookie);
        return cookieJar.remove(key) != null;
    }

    @Override
    public boolean removeAll() {
        cookieJar.clear();
        saveCookies();
        return true;
    }
}
