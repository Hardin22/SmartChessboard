package io.github.hardin22.javachess.Utils;

import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe cache of decoded classpath images keyed by path and requested size (views may load off the FX thread). */
public class ImageCache {
    private static final Logger LOG = LoggerFactory.getLogger(ImageCache.class);
    private static final ImageCache INSTANCE = new ImageCache();
    private final Map<String, Image> cache = new ConcurrentHashMap<>();

    private ImageCache() {
    }

    public static ImageCache getInstance() {
        return INSTANCE;
    }

    public Image getImage(String path) {
        return getImage(path, -1, -1);
    }

    /** Returns the image scaled (smoothly, keeping the ratio) to fit the size, or null if the resource is missing. */
    public Image getImage(String path, double width, double height) {
        String key = path + "_" + width + "_" + height;
        Image cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = ImageCache.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.warn("Image not found: {}", path);
                return null;
            }
            Image image = new Image(in, Math.max(width, 0), Math.max(height, 0), true, true);
            cache.put(key, image);
            return image;
        } catch (Exception e) {
            LOG.warn("Failed to load image: {}", path, e);
            return null;
        }
    }

    public void preload(String... paths) {
        for (String path : paths) {
            getImage(path);
        }
    }
}
