package com.zellijconnect.app;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * LRU memory cache for rendered mermaid diagram bitmaps.
 * Sized to 1/8 of available memory to avoid pressure.
 */
public class MermaidBitmapCache {

    private final LruCache<String, Bitmap> cache;

    public MermaidBitmapCache() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<String, Bitmap>(maxMemory) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }

            // Do NOT recycle evicted bitmaps — they may still be referenced
            // by ImageSpans in a visible TextView. Let the GC handle cleanup.
        };
    }

    public Bitmap get(String key) {
        return cache.get(key);
    }

    public void put(String key, Bitmap bitmap) {
        cache.put(key, bitmap);
    }

    public void clear() {
        cache.evictAll();
    }
}
