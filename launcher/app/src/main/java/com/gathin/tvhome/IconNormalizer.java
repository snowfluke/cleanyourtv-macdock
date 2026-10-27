package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * mac_home icons, all one shape: a rounded square. An adaptive icon draws its own two layers into it; an icon that
 * already fills its square is used as it is; a logo on a transparent ground is centred on a light tile (or a dark
 * one when the logo itself is near white) at one common size. Off the UI thread; cached per package and size.
 */
final class IconNormalizer {
    static final float RADIUS = 0.225f;
    private static final float LOGO = 0.66f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private IconNormalizer() {
    }

    static Drawable of(Context context, String pkg, Drawable src, int size) {
        if (src == null || size <= 0) return src;
        String key = pkg + "@" + size;
        Bitmap out;
        synchronized (CACHE) {
            out = CACHE.get(key);
        }
        if (out == null) {
            out = render(src, size);
            synchronized (CACHE) {
                CACHE.put(key, out);
            }
        }
        return new BitmapDrawable(context.getResources(), out);
    }

    private static Bitmap render(Drawable src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Path round = new Path();
        round.addRoundRect(new RectF(0, 0, size, size), size * RADIUS, size * RADIUS, Path.Direction.CW);
        canvas.clipPath(round);
        Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas rawCanvas = new Canvas(raw);
        if (src instanceof AdaptiveIconDrawable) {
            // The layers are 108 units with the middle 72 shown: draw them half as large again and centred. Then
            // the same check as any icon: Spotify's background layer is transparent, so it needs a tile too.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) src;
            int pad = size / 4;
            for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-pad, -pad, size + pad, size + pad);
                layer.draw(rawCanvas);
            }
        } else {
            src.setBounds(0, 0, size, size);
            src.draw(rawCanvas);
        }
        int[] px = new int[size * size];
        raw.getPixels(px, 0, size, 0, 0, size, size);
        if (isFullBleed(px, size, size)) {
            canvas.drawColor(px[(size / 2) * size + Math.max(1, size / 50)] | 0xFF000000);
            canvas.drawBitmap(raw, 0, 0, null);
        } else {
            canvas.drawColor(contentIsLight(px) ? 0xFF2C2C2E : 0xFFF5F5F7);
            Rect content = opaqueBounds(px, size, size);
            if (content == null) return out;
            float scale = LOGO * size / Math.max(content.width(), content.height());
            float w = content.width() * scale, h = content.height() * scale;
            RectF dst = new RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2);
            canvas.drawBitmap(raw, content, dst, null);
        }
        raw.recycle();
        return out;
    }

    /** True when the icon covers its square: at least 7 of 8 points along the edges (corners left out) are opaque. */
    static boolean isFullBleed(int[] px, int w, int h) {
        int in = Math.max(1, Math.min(w, h) / 50);
        int[][] points = {
                {w / 4, in}, {3 * w / 4, in}, {w / 4, h - 1 - in}, {3 * w / 4, h - 1 - in},
                {in, h / 4}, {in, 3 * h / 4}, {w - 1 - in, h / 4}, {w - 1 - in, 3 * h / 4}};
        int opaque = 0;
        for (int[] p : points) if ((px[p[1] * w + p[0]] >>> 24) > 200) opaque++;
        return opaque >= 7;
    }

    /** True when the visible pixels are mostly near white, so a light tile would hide them. */
    static boolean contentIsLight(int[] px) {
        double sum = 0;
        int n = 0;
        for (int c : px) {
            if ((c >>> 24) < 128) continue;
            sum += (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
            n++;
        }
        return n > 0 && sum / n > 0.82;
    }

    private static Rect opaqueBounds(int[] px, int w, int h) {
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) < 24) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > b) b = y;
            }
        }
        return r < 0 ? null : new Rect(l, t, r + 1, b + 1);
    }
}
