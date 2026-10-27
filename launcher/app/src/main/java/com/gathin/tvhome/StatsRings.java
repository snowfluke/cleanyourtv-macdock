package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** show_stats as rings: one per reading, the used share as an arc, the percent inside and a label under it. */
public final class StatsRings extends View {
    private static final float RING_DP = 46, STROKE_DP = 4, GAP_DP = 18, LABEL_GAP_DP = 6;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float dp;
    private float[] fractions = new float[0];
    private String[] labels = new String[0];
    /** mac_home header: a small ring with "RAM 63%" beside it, all in one line. */
    private boolean compact;
    private static final float C_RING_DP = 20, C_STROKE_DP = 3, C_TEXT_GAP_DP = 7, C_GAP_DP = 16;

    public StatsRings(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(STROKE_DP * dp);
        track.setColor(0x40FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(STROKE_DP * dp);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFFFFFFFF);
        value.setColor(0xFFFFFFFF);
        value.setTextAlign(Paint.Align.CENTER);
        value.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        value.setTypeface(UiFont.get(context, R.string.font_medium));
        label.setColor(0xD9FFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        label.setLetterSpacing(0.08f);
        label.setTypeface(UiFont.get(context, R.string.font_medium));
    }

    void setCompact(boolean on) {
        compact = on;
        float stroke = (on ? C_STROKE_DP : STROKE_DP) * dp;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        label.setTextAlign(on ? Paint.Align.LEFT : Paint.Align.CENTER);
        label.setTextSize((on ? 15 : 11) * getResources().getDisplayMetrics().scaledDensity);
        label.setLetterSpacing(on ? 0.02f : 0.08f);
        requestLayout();
        invalidate();
    }

    private String compactText(int i) {
        return labels[i] + " " + Math.round(fractions[i] * 100) + "%";
    }

    void setColor(int color) {
        arc.setColor(color);
        invalidate();
    }

    /** fractions[i] in 0..1 is drawn; a negative one leaves that ring out. Main thread only. */
    void set(float[] fractions, String[] labels) {
        int n = 0;
        for (float f : fractions) if (f >= 0) n++;
        float[] f2 = new float[n];
        String[] l2 = new String[n];
        for (int i = 0, j = 0; i < fractions.length; i++) {
            if (fractions[i] < 0) continue;
            f2[j] = Math.min(1f, fractions[i]);
            l2[j++] = labels[i];
        }
        boolean resize = compact || n != this.fractions.length;
        this.fractions = f2;
        this.labels = l2;
        if (resize) requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int n = fractions.length;
        if (compact) {
            float w = 0;
            for (int i = 0; i < n; i++) {
                w += C_RING_DP * dp + C_TEXT_GAP_DP * dp + label.measureText(compactText(i)) + (i > 0 ? C_GAP_DP * dp : 0);
            }
            float h = Math.max(C_RING_DP * dp + C_STROKE_DP * dp, label.descent() - label.ascent());
            setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(n == 0 ? 0 : h));
            return;
        }
        float w = n == 0 ? 0 : n * RING_DP * dp + (n - 1) * GAP_DP * dp + STROKE_DP * dp;
        float h = n == 0 ? 0 : RING_DP * dp + STROKE_DP * dp + LABEL_GAP_DP * dp - label.ascent() + label.descent();
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (compact) {
            float x = 0, cy = getHeight() / 2f, ring = C_RING_DP * dp, half = C_STROKE_DP * dp / 2;
            for (int i = 0; i < fractions.length; i++) {
                if (i > 0) x += C_GAP_DP * dp;
                box.set(x + half, cy - ring / 2 + half, x + ring - half, cy + ring / 2 - half);
                canvas.drawOval(box, track);
                float sweep = 360f * fractions[i];
                if (sweep > 0) canvas.drawArc(box, -90f, sweep, false, arc);
                x += ring + C_TEXT_GAP_DP * dp;
                String t = compactText(i);
                canvas.drawText(t, x, cy - (label.ascent() + label.descent()) / 2, label);
                x += label.measureText(t);
            }
            return;
        }
        float half = STROKE_DP * dp / 2, size = RING_DP * dp;
        for (int i = 0; i < fractions.length; i++) {
            float left = half + i * (size + GAP_DP * dp);
            box.set(left, half, left + size, half + size);
            canvas.drawOval(box, track);
            float sweep = 360f * fractions[i];
            if (sweep > 0) canvas.drawArc(box, -90f, sweep, false, arc);
            float cx = box.centerX();
            float cy = box.centerY() - (value.ascent() + value.descent()) / 2;
            canvas.drawText(Math.round(fractions[i] * 100) + "%", cx, cy, value);
            canvas.drawText(labels[i], cx, box.bottom + half + LABEL_GAP_DP * dp - label.ascent(), label);
        }
    }
}
