package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The weather now, read-only, for a home screen that has no network of its own:
 * content://io.github.sumyabazar1.weather.now gives one row (temp_c, code, day, sky, updated_ms), or none
 * before a place is chosen. The place itself is not shared. A read whose cache is older than
 * {@link Forecast#REFRESH_MS} starts one fetch in the background and answers with the old row; the next read
 * gets the new one. Nothing is scheduled: with nobody reading, nothing is fetched.
 */
public final class NowProvider extends ContentProvider {
    static final String PREFS = "now";
    private static final String[] COLUMNS = {"temp_c", "code", "day", "sky", "updated_ms"};
    private static volatile boolean fetching;

    /** Kept by the page after each reading, and by the fetch below. */
    static void save(Context context, Forecast.Report r) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("temp", Double.doubleToRawLongBits(r.tempC))
                .putInt("code", r.code)
                .putBoolean("day", r.day)
                .putLong("at", System.currentTimeMillis())
                .apply();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context context = getContext();
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (context == null) return out;
        SharedPreferences place = context.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (!place.contains("name")) return out;
        SharedPreferences now = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = now.getLong("at", 0);
        if (System.currentTimeMillis() - at > Forecast.REFRESH_MS) refresh(context, place);
        if (at == 0) return out;
        int code = now.getInt("code", 3);
        boolean day = now.getBoolean("day", true);
        out.addRow(new Object[]{Double.longBitsToDouble(now.getLong("temp", 0)), code, day ? 1 : 0,
                context.getString(Forecast.sky(code, day)), at});
        return out;
    }

    private static void refresh(Context context, SharedPreferences place) {
        if (fetching) return;
        fetching = true;
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                save(app, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Exception ignored) {
                // Offline: the old row stays, and the next read tries again.
            } finally {
                fetching = false;
            }
        }, "weather-now").start();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
