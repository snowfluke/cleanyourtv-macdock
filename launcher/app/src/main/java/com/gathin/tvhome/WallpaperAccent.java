package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * accent_from_wallpaper: the wallpaper's most vivid colour, for the rings and the music visualizer, so a new
 * wallpaper brings its own accent. Greys and dark pixels are skipped; the rest are grouped by hue, weighted by how
 * vivid they are, and the strongest group's average colour wins. Off the UI thread.
 */
final class WallpaperAccent {
    private static final int HUES = 24;

    private WallpaperAccent() {
    }

    /** The accent of the picture in this file, or the fallback when it has no colour to speak of. */
    static int from(File file, int fallback) {
        if (file == null || !file.isFile()) return fallback;
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), size);
        BitmapFactory.Options small = new BitmapFactory.Options();
        small.inSampleSize = Math.max(1, Math.max(size.outWidth, size.outHeight) / 96);
        Bitmap b = BitmapFactory.decodeFile(file.getPath(), small);
        if (b == null) return fallback;
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        b.recycle();
        return pick(px, fallback);
    }

    static int pick(int[] px, int fallback) {
        double[] weight = new double[HUES], r = new double[HUES], g = new double[HUES], bl = new double[HUES];
        for (int c : px) {
            int rr = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
            int max = Math.max(rr, Math.max(gg, bb)), min = Math.min(rr, Math.min(gg, bb));
            float v = max / 255f, s = max == 0 ? 0 : (max - min) / (float) max;
            if (s < 0.30f || v < 0.30f) continue;   // greys, skin-pale tints and shadows say little about the accent
            float hue;
            if (max == rr) hue = ((gg - bb) / (float) (max - min) + 6) % 6;
            else if (max == gg) hue = (bb - rr) / (float) (max - min) + 2;
            else hue = (rr - gg) / (float) (max - min) + 4;
            int bucket = Math.min(HUES - 1, (int) (hue / 6f * HUES));
            double w = s * s * v;   // the vivid ones count most
            weight[bucket] += w;
            r[bucket] += rr * w;
            g[bucket] += gg * w;
            bl[bucket] += bb * w;
        }
        int best = -1;
        for (int i = 0; i < HUES; i++) if (weight[i] > 0 && (best < 0 || weight[i] > weight[best])) best = i;
        if (best < 0) return fallback;
        return 0xFF000000 | (int) Math.round(r[best] / weight[best]) << 16
                | (int) Math.round(g[best] / weight[best]) << 8 | (int) Math.round(bl[best] / weight[best]);
    }
}
