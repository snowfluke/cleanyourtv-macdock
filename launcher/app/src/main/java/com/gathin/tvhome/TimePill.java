package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Date;

/**
 * The "clock" remote action: the time and date in a small pill at the top right, over any app, for four seconds.
 * Drawn by the key helper as an accessibility overlay, so it needs no draw-over-apps permission and takes no keys.
 */
final class TimePill {
    private static final long SHOW_MS = 4_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static TextView shown;
    private static final Runnable HIDE = TimePill::hide;

    private TimePill() {
    }

    static void show(AccessibilityService service) {
        MAIN.removeCallbacks(HIDE);
        String text = DateFormat.getTimeFormat(service).format(new Date()) + "   "
                + DateFormat.format("EEE d MMM", new Date());
        if (shown != null) {
            shown.setText(text);
        } else {
            float dp = service.getResources().getDisplayMetrics().density;
            TextView pill = new TextView(service);
            pill.setText(text);
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            pill.setTypeface(UiFont.get(service, R.string.font_medium));
            int h = Math.round(20 * dp), v = Math.round(10 * dp);
            pill.setPadding(h, v, h, v);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xD91E1C1B);
            bg.setCornerRadius(100 * dp);
            bg.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
            pill.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = Math.round(32 * dp);
            lp.y = Math.round(28 * dp);
            try {
                service.getSystemService(WindowManager.class).addView(pill, lp);
                shown = pill;
            } catch (RuntimeException e) {
                return;   // the service is going away
            }
        }
        MAIN.postDelayed(HIDE, SHOW_MS);
    }

    private static void hide() {
        if (shown == null) return;
        try {
            ((WindowManager) shown.getContext().getSystemService(WindowManager.class)).removeView(shown);
        } catch (RuntimeException ignored) {
            // Already gone with its service.
        }
        shown = null;
    }
}
