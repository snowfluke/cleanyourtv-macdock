package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * mac_home: one focusable view at the bottom of Home, a centred glass band of square app icons, a divider and an
 * Apps button. Left and Right go round, OK opens, Down and Menu open the app panel, Up leaves for the top buttons.
 */
final class MacDock extends View {
    interface Listener {
        void onOpen(String pkg);
        void onPanel();
        void onMusic();
    }

    static final class Item {
        /** An app; the Music toggle; the Apps button. The two buttons sit after the divider. */
        static final int APP = 0, MUSIC = 1, APPS = 2;
        final int kind;
        final String pkg;   // null for the two buttons
        String label;
        final Drawable icon;
        float alpha = 1f;

        Item(String pkg, String label, Drawable icon) {
            this(pkg == null ? APPS : APP, pkg, label, icon);
        }

        Item(int kind, String pkg, String label, Drawable icon) {
            this.kind = kind;
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    /** The Music toggle's look: dimmed while the player and the visualizer are hidden. */
    void setMusic(boolean shown, String label) {
        for (Item it : items) {
            if (it.kind != Item.MUSIC) continue;
            it.alpha = shown ? 1f : 0.45f;
            it.label = label;
        }
        invalidate();
    }

    /** The divider goes before the first button that follows an app. */
    private boolean dividerBefore(int i) {
        return i > 0 && items.get(i).kind != Item.APP && items.get(i - 1).kind == Item.APP;
    }

    /** How long a name stays up after focus lands, for the dock and the top buttons alike. */
    static final long NAME_MS = 2_500L;
    private static final float ICON_DP = 64, GAP_DP = 18, PAD_DP = 12, DIVIDER_DP = 20, LIFT = 0.18f;

    private final List<Item> items = new ArrayList<>();
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Rect icon = new Rect();
    private final float dp;
    private Listener listener;
    private int index;
    /** 0..1: how far the focused icon has grown; restarts on each move. */
    private float grow = 1f;
    private ValueAnimator growth;
    /** 1 while the focused icon's name shows; fades to 0 after NAME_MS. */
    private float nameAlpha = 1f;
    private ValueAnimator nameFade;
    private final Runnable fadeName = () -> {
        nameFade = ValueAnimator.ofFloat(1f, 0f).setDuration(220);
        nameFade.addUpdateListener(a -> {
            nameAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        nameFade.start();
    };

    MacDock(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        band.setColor(0x33FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1 * dp);
        rim.setColor(0x40FFFFFF);
        divider.setColor(0x59FFFFFF);
        divider.setStrokeWidth(1 * dp);
        pill.setColor(0xCC1E1C1B);
        name.setColor(0xFFFFFFFF);
        name.setTextAlign(Paint.Align.CENTER);
        name.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        name.setTypeface(UiFont.get(context, R.string.font_medium));
        dot.setColor(0xD9FFFFFF);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setItems(List<Item> list) {
        items.clear();
        items.addAll(list);
        index = Math.min(index, Math.max(0, items.size() - 1));
        requestLayout();
        invalidate();
    }

    /** Index after a move of delta, going round at both ends. */
    static int step(int index, int count, int delta) {
        if (count <= 0) return 0;
        return ((index + delta) % count + count) % count;
    }

    private float bandWidth() {
        int n = items.size();
        if (n == 0) return 0;
        boolean divider = false;
        for (int i = 0; i < n; i++) divider |= dividerBefore(i);
        return 2 * PAD_DP * dp + n * ICON_DP * dp + (n - 1) * GAP_DP * dp + (divider ? DIVIDER_DP * dp : 0);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float bandH = ICON_DP * dp + 2 * PAD_DP * dp;
        float above = ICON_DP * dp * LIFT + 44 * dp;   // room for the grown icon and its name
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Math.ceil(bandH + above));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = items.size();
        if (n == 0) return;
        float size = ICON_DP * dp, pad = PAD_DP * dp, w = bandWidth();
        float left = (getWidth() - w) / 2f, bottom = getHeight(), top = bottom - (size + 2 * pad);
        box.set(left, top, left + w, bottom);
        float r = 22 * dp;
        canvas.drawRoundRect(box, r, r, band);
        canvas.drawRoundRect(box, r, r, rim);
        float x = left + pad;
        boolean focused = hasFocus();
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            if (dividerBefore(i)) {
                float dx = x - GAP_DP * dp / 2 + DIVIDER_DP * dp / 2;
                canvas.drawLine(dx, top + pad, dx, bottom - pad, divider);
                x += DIVIDER_DP * dp;
            }
            float scale = focused && i == index ? 1f + LIFT * grow : 1f;
            float s = size * scale;
            float cx = x + size / 2, iconBottom = bottom - pad;
            icon.set(Math.round(cx - s / 2), Math.round(iconBottom - s), Math.round(cx + s / 2), Math.round(iconBottom));
            if (it.icon != null) {
                // Already rounded by IconNormalizer. A clipPath here cost a stencil pass per icon on every frame of
                // the whole screen, which the music visualizer redraws 30 times a second.
                it.icon.setBounds(icon);
                it.icon.setAlpha(Math.round(255 * it.alpha));
                it.icon.draw(canvas);
            }
            if (focused && i == index && nameAlpha > 0f) {
                pill.setAlpha(Math.round(0xCC * nameAlpha));
                name.setAlpha(Math.round(0xFF * nameAlpha));
                float ty = icon.top - 12 * dp;
                float tw = name.measureText(it.label) + 24 * dp, th = 28 * dp;
                box.set(cx - tw / 2, ty - th, cx + tw / 2, ty);
                canvas.drawRoundRect(box, th / 2, th / 2, pill);
                canvas.drawText(it.label, cx, box.centerY() - (name.ascent() + name.descent()) / 2, name);
            }
            if (focused && i == index) canvas.drawCircle(cx, bottom - pad / 2 + 1 * dp, 2.5f * dp, dot);
            x += size + GAP_DP * dp;
        }
    }

    private void moveTo(int i) {
        index = i;
        removeCallbacks(fadeName);
        if (nameFade != null) nameFade.cancel();
        nameAlpha = 1f;
        postDelayed(fadeName, NAME_MS);
        if (growth != null) growth.cancel();
        growth = ValueAnimator.ofFloat(0f, 1f).setDuration(140);
        growth.addUpdateListener(a -> {
            grow = (float) a.getAnimatedValue();
            invalidate();
        });
        growth.start();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        if (gainFocus) moveTo(index);
        else invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int n = items.size();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (n > 0) moveTo(step(index, n, -1));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (n > 0) moveTo(step(index, n, 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) listener.onPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;   // acts on key up, like a button
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (listener != null && !items.isEmpty() && !event.isCanceled()) {
                Item it = items.get(index);
                if (it.kind == Item.APPS) listener.onPanel();
                else if (it.kind == Item.MUSIC) listener.onMusic();
                else listener.onOpen(it.pkg);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
