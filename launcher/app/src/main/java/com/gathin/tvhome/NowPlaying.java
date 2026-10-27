package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.List;

/**
 * mac_home: a glass bar above the dock with what is playing (art, title, artist) and previous / play-pause / next.
 * Left and Right pick a control, OK runs it. Reads Android's media sessions, which needs notification access for
 * MediaListener; without it, or with nothing playing, the bar stays hidden.
 */
final class NowPlaying extends View {
    interface Listener {
        /** Playing started or stopped, or the bar appeared or went away. */
        void onChanged(boolean visible, boolean playing);
    }

    private static final float W_DP = 560, H_DP = 76, ART_DP = 52, PAD_DP = 12, CTRL_DP = 40;

    private final float dp;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artBlank = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ctrl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ctrlFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint artist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path shape = new Path();
    private final Matrix artMatrix = new Matrix();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private MediaSessionManager sessions;
    private ComponentName access;
    private MediaController controller;
    private String titleText = "", artistText = "";
    private Bitmap artBitmap;
    private boolean playing;
    /** Back on the bar hides it; it comes back when something starts playing again. */
    private boolean dismissed;
    /** 0 previous, 1 play/pause, 2 next. */
    private int control = 1;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::pick;

    NowPlaying(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        setFocusable(true);
        setVisibility(GONE);
        glass.setColor(0x47FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1 * dp);
        rim.setColor(0x47FFFFFF);
        rimFocus.setStyle(Paint.Style.STROKE);
        rimFocus.setStrokeWidth(2 * dp);
        rimFocus.setColor(0xE6FFFFFF);
        artBlank.setColor(0x33000000);
        ctrl.setColor(0xFFFFFFFF);
        ctrlFocus.setColor(0x40FFFFFF);
        title.setColor(0xFFFFFFFF);
        title.setTextSize(17 * sp);
        title.setTypeface(UiFont.get(context, R.string.font_medium));
        artist.setColor(0xCCFFFFFF);
        artist.setTextSize(14 * sp);
        artist.setTypeface(UiFont.get(context, R.string.font_medium));
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** While Home is on screen. */
    void start() {
        sessions = getContext().getSystemService(MediaSessionManager.class);
        access = new ComponentName(getContext(), MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, access, main);
            pick(sessions.getActiveSessions(access));
        } catch (SecurityException e) {
            sessions = null;   // no notification access: no bar
            pick(null);
        }
    }

    void stop() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        sessions = null;
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
    }

    /** The session to show: one that is playing, else the first that has something loaded. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null && c.getMetadata() != null) best = c;
            }
        }
        if (controller != null && (best == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        if (best != null && (controller == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            best.registerCallback(callback, main);
        }
        controller = best;
        update();
    }

    private void update() {
        MediaController c = controller;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState s = c == null ? null : c.getPlaybackState();
        boolean nowPlaying = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        if (nowPlaying && !playing) dismissed = false;   // a new start brings a dismissed bar back
        playing = nowPlaying;
        if (m != null) {
            titleText = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artistText = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            setArt(b);
        }
        boolean visible = m != null && !TextUtils.isEmpty(titleText) && !dismissed;
        if (!visible && hasFocus()) {
            View down = focusSearch(FOCUS_DOWN);
            if (down != null) down.requestFocus();
        }
        setVisibility(visible ? VISIBLE : GONE);
        invalidate();
        if (listener != null) listener.onChanged(visible, playing && visible);
    }

    private static String text(MediaMetadata m, String key, String fallback) {
        CharSequence t = m.getText(key);
        if (TextUtils.isEmpty(t)) t = m.getText(fallback);
        return t == null ? "" : t.toString();
    }

    private void setArt(Bitmap b) {
        artBitmap = b;
        if (b == null) {
            art.setShader(null);
            return;
        }
        art.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = Math.min(MeasureSpec.getSize(widthSpec), Math.round(W_DP * dp));
        setMeasuredDimension(w, Math.round(H_DP * dp));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2.6f, pad = PAD_DP * dp;
        box.set(0.5f * dp, 0.5f * dp, w - 0.5f * dp, h - 0.5f * dp);
        canvas.drawRoundRect(box, r, r, glass);
        canvas.drawRoundRect(box, r, r, hasFocus() ? rimFocus : rim);   // focus is a brighter edge, not a fill
        // Art
        float a = ART_DP * dp, ay = (h - a) / 2;
        box.set(pad, ay, pad + a, ay + a);
        if (artBitmap != null) {
            float scale = Math.max(a / artBitmap.getWidth(), a / artBitmap.getHeight());
            artMatrix.setScale(scale, scale);
            artMatrix.postTranslate(pad + (a - artBitmap.getWidth() * scale) / 2, ay + (a - artBitmap.getHeight() * scale) / 2);
            art.getShader().setLocalMatrix(artMatrix);
            canvas.drawRoundRect(box, 10 * dp, 10 * dp, art);
        } else {
            canvas.drawRoundRect(box, 10 * dp, 10 * dp, artBlank);
        }
        // Controls, right to left: next, play/pause, previous
        float c = CTRL_DP * dp, cy = h / 2, right = w - pad;
        float[] cx = {right - c * 2.5f, right - c * 1.5f, right - c * 0.5f};
        for (int i = 0; i < 3; i++) {
            if (hasFocus() && i == control) canvas.drawCircle(cx[i], cy, c * 0.5f, ctrlFocus);
            drawControl(canvas, i, cx[i], cy, c * 0.22f);
        }
        // Text
        float tx = pad + a + 14 * dp, maxW = cx[0] - c * 0.6f - tx;
        CharSequence t1 = TextUtils.ellipsize(titleText, title, maxW, TextUtils.TruncateAt.END);
        CharSequence t2 = TextUtils.ellipsize(artistText, artist, maxW, TextUtils.TruncateAt.END);
        canvas.drawText(t1, 0, t1.length(), tx, cy - 3 * dp, title);
        canvas.drawText(t2, 0, t2.length(), tx, cy - 3 * dp - artist.ascent() + 2 * dp, artist);
    }

    /** Glyphs drawn as shapes, so they look the same on every firmware's fonts. */
    private void drawControl(Canvas canvas, int which, float x, float y, float s) {
        shape.reset();
        if (which == 1 && playing) {
            canvas.drawRoundRect(x - s * 0.9f, y - s, x - s * 0.3f, y + s, s * 0.15f, s * 0.15f, ctrl);
            canvas.drawRoundRect(x + s * 0.3f, y - s, x + s * 0.9f, y + s, s * 0.15f, s * 0.15f, ctrl);
            return;
        }
        if (which == 1) {
            shape.moveTo(x - s * 0.7f, y - s);
            shape.lineTo(x + s, y);
            shape.lineTo(x - s * 0.7f, y + s);
        } else {
            float dir = which == 2 ? 1 : -1;
            for (int k = 0; k < 2; k++) {
                float bx = x + dir * (k == 0 ? -s * 0.9f : 0);
                shape.moveTo(bx, y - s * 0.8f);
                shape.lineTo(bx + dir * s * 0.9f, y);
                shape.lineTo(bx, y + s * 0.8f);
                shape.close();
            }
        }
        shape.close();
        canvas.drawPath(shape, ctrl);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        if (gainFocus) control = 1;
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                control = Math.max(0, control - 1);
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                control = Math.min(2, control + 1);
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;   // acted on at key up
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!event.isCanceled()) {
                dismissed = true;
                update();   // hides the bar and hands focus down to the dock
            }
            return true;
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return super.onKeyUp(keyCode, event);
        }
        MediaController c = controller;
        if (c != null && !event.isCanceled()) {
            MediaController.TransportControls t = c.getTransportControls();
            if (control == 0) t.skipToPrevious();
            else if (control == 2) t.skipToNext();
            else if (playing) t.pause();
            else t.play();
        }
        return true;
    }
}
