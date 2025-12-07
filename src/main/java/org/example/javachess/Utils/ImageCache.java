package org.example.javachess.Utils;

import javafx.scene.image.Image;
import java.util.HashMap;
import java.util.Map;

public class ImageCache {
    private static ImageCache instance;
    private Map<String, Image> cache = new HashMap<>();

    private ImageCache() {}

    public static synchronized ImageCache getInstance() {
        if (instance == null) {
            instance = new ImageCache();
        }
        return instance;
    }

    public Image getImage(String path) {
        return getImage(path, -1, -1);
    }

    public Image getImage(String path, double width, double height) {
        String key = path + "_" + width + "_" + height;
        if (!cache.containsKey(key)) {
            try {
                // If width/height are -1, load original size
                // Otherwise load resized (preserves aspect ratio if one is -1, but here we usually want exact fit or ratio)
                // Image constructor arguments: url, requestedWidth, requestedHeight, preserveRatio, smooth
                double w = width > 0 ? width : 0; // 0 means load original
                double h = height > 0 ? height : 0;
                
                Image image = new Image(getClass().getResourceAsStream(path), w, h, true, true);
                cache.put(key, image);
            } catch (Exception e) {
                System.err.println("Failed to load image: " + path);
                return null;
            }
        }
        return cache.get(key);
    }
    
    public void preload(String... paths) {
        for (String path : paths) {
            getImage(path);
        }
    }
}
