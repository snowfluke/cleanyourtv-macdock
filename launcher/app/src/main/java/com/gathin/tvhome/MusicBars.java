package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/**
 * mac_home: see-through bars across the bottom of Home, behind the dock, while music plays (like cava). They follow
 * the speaker output through Android's Visualizer when the TV allows it (RECORD_AUDIO, granted by hand); when it
 * does not, or it stays silent for {@link #SILENT_MS} while music plays, they move on an animation instead.
 * Nothing runs while Home is not on screen or nothing plays.
 */
final class MusicBars extends View {
    private static final String TAG = "MusicBars";
    static final int BARS = 32;
    private static final long SILENT_MS = 3_000L;
    /** Gravity for a falling bar, in screen heights per second squared: slow to start, then quicker, as cava does. */
    private static final float GRAVITY = 2.6f;
    /** How fast a bar rises to a louder reading, per second: a few frames rather than one jump. */
    private static final float RISE = 22f;
    /** Share of a new reading blended into a band: steadies one-off spikes between readings. */
    private static final float BLEND = 0.55f;
    /**
     * 30 frames a second. Each frame redraws the whole screen on this TV's GPU (RenderThread, not this code), so the
     * frame rate sets the cost: 60 took about 40% CPU, 30 about half that. The easing keeps 30 smooth.
     */
    private static final long FRAME_MS = 33L;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** A thin white edge on each bar, so the bars read against a light wallpaper too. */
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int accent = 0xFFE8573F;
    /** music_style "waves": three flowing lines instead of bars. */
    private boolean waves;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int WAVE_POINTS = 33, ENVELOPE = 8;
    private final android.graphics.Path wave = new android.graphics.Path();
    private final float[] envelope = new float[ENVELOPE];
    private float waveStep;
    private final float[] target = new float[BARS];
    private final float[] shown = new float[BARS];
    private final float[] fall = new float[BARS];   // each bar's falling speed
    private final float[] level = new float[BARS];
    private final int[] binFrom = new int[BARS], binTo = new int[BARS];
    private int binsFor = -1, rateFor = -1;
    private final float[] peak = new float[1];   // running loudness for the auto gain
    private Visualizer visualizer;
    private boolean real;
    private boolean running;
    private long started, lastSound, lastFrame;

    MusicBars(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1 * dp);
        edge.setColor(0x40FFFFFF);   // a hint of an edge, not an outline
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        setAlpha(0f);
    }

    void setWaves(boolean on) {
        waves = on;
        invalidate();
    }

    void setColor(int color) {
        accent = color | 0xFF000000;
        shade(getHeight());
    }

    /**
     * Filled, see-through bars: the accent at the bottom fading to a pale tint of it at the top, so the colours
     * come from the theme rather than a fixed palette.
     */
    private void shade(int h) {
        if (h <= 0) return;
        int r = (accent >> 16) & 0xFF, g = (accent >> 8) & 0xFF, b = accent & 0xFF;
        int pale = 0xFF000000 | mix(r) << 16 | mix(g) << 8 | mix(b);
        fill.setShader(new LinearGradient(0, h, 0, 0,
                new int[]{(accent & 0x00FFFFFF) | 0x8C000000, (pale & 0x00FFFFFF) | 0x33000000},
                null, Shader.TileMode.CLAMP));
    }

    /** A channel 60% of the way to white. */
    private static int mix(int c) {
        return c + Math.round((255 - c) * 0.6f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        shade(h);
    }

    /** Music started (true) or stopped (false), while Home is on screen. */
    void setPlaying(boolean playing) {
        if (playing == running) return;
        running = playing;
        if (playing) {
            started = lastSound = SystemClock.uptimeMillis();
            lastFrame = 0;
            real = openVisualizer();
            animate().alpha(1f).setDuration(400).start();
            postInvalidateOnAnimation();
        } else {
            closeVisualizer();
            animate().alpha(0f).setDuration(600).start();
        }
    }

    private boolean openVisualizer() {
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no RECORD_AUDIO: animated bars");
            return false;
        }
        try {
            Visualizer v = new Visualizer(0);   // session 0: everything the TV plays
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.min(1024, range[1]));
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 25_000);   // milliHertz: about 25 a second
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] wave, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                    bands(fft, samplingRate);
                }
            }, rate, false, true);
            v.setEnabled(true);
            visualizer = v;
            Log.i(TAG, "visualizer on the output mix");
            return true;
        } catch (RuntimeException e) {
            Log.i(TAG, "visualizer refused (" + e + "): animated bars");
            return false;
        }
    }

    private void closeVisualizer() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (RuntimeException ignored) {
            // already released by the system
        }
        visualizer = null;
    }

    /** Log-spaced bands from 60 Hz to 12 kHz, each the loudest bin in it, scaled by a slowly falling peak. */
    private void bands(byte[] fft, int samplingRateMilliHz) {
        int bins = fft.length / 2;
        if (bins != binsFor || samplingRateMilliHz != rateFor) {
            // The band edges depend only on the capture size and the rate: worked out once, not on every reading.
            float hzPerBin = samplingRateMilliHz / 1000f / 2f / bins;
            for (int b = 0; b < BARS; b++) {
                double lo = 60 * Math.pow(200, b / (double) BARS), hi = 60 * Math.pow(200, (b + 1) / (double) BARS);
                binFrom[b] = Math.max(1, (int) (lo / hzPerBin));
                binTo[b] = Math.max(binFrom[b] + 1, Math.min(bins, (int) (hi / hzPerBin)));
            }
            binsFor = bins;
            rateFor = samplingRateMilliHz;
        }
        boolean any = false;
        float loudest = 0;
        for (int b = 0; b < BARS; b++) {
            int max = 0;
            for (int k = binFrom[b]; k < binTo[b]; k++) {
                int re = fft[2 * k], im = fft[2 * k + 1];
                max = Math.max(max, re * re + im * im);   // squared: the square root folds into the log below
            }
            if (max > 0) any = true;
            level[b] = 0.5f * (float) Math.log10(1 + max);
            loudest = Math.max(loudest, level[b]);
        }
        peak[0] = Math.max(loudest, peak[0] * 0.995f);
        if (any) lastSound = SystemClock.uptimeMillis();
        float gain = peak[0] > 0 ? 1f / peak[0] : 0;
        for (int b = 0; b < BARS; b++) {
            target[b] += (Math.min(1f, level[b] * gain) - target[b]) * BLEND;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1000f;
        lastFrame = now;
        if (running && real && now - lastSound > SILENT_MS && now - started > SILENT_MS) {
            Log.i(TAG, "visualizer silent while playing: animated bars");
            closeVisualizer();
            real = false;
        }
        if (running && !real) animatedTargets(now / 1000f);
        if (!running) java.util.Arrays.fill(target, 0f);
        float w = getWidth(), h = getHeight(), gap = 6 * dp, bw = (w - gap * (BARS + 1)) / BARS, round = 4 * dp;
        float step = Math.min(dt, 0.1f);   // a stalled frame does not make the bars jump
        boolean moving = false;
        for (int b = 0; b < BARS; b++) {
            float t = target[b];
            if (t >= shown[b]) {
                shown[b] += (t - shown[b]) * Math.min(1f, RISE * step);
                fall[b] = 0;
            } else {
                fall[b] += GRAVITY * step;
                shown[b] = Math.max(t, shown[b] - fall[b] * step);
            }
            if (shown[b] > 0.005f) moving = true;
            if (waves) continue;
            float bh = Math.max(4 * dp, shown[b] * h * 0.92f);
            float x = gap + b * (bw + gap);
            // Rounded top only: the bottom corners sit below the edge. A rounded rectangle is drawn on the GPU as
            // it is; one Path holding every bar was re-traced on the CPU each frame and cost twice as much.
            canvas.drawRoundRect(x, h - bh, x + bw, h + round, round, round, fill);
            canvas.drawRoundRect(x, h - bh, x + bw, h + round, round, round, edge);
        }
        waveStep = step;
        if (waves) drawWaves(canvas, w, h, now / 1000f);
        if (running || moving) postInvalidateDelayed(FRAME_MS);
    }

    /**
     * Three lines that flow sideways, each its own speed and wavelength. How far a line swings at a point follows
     * the band under that point (bass on the left, treble on the right), so the loud part of the sound bulges.
     * Thin lines touch far fewer pixels than filled bars.
     */
    private void drawWaves(Canvas canvas, float w, float h, float t) {
        // The swing along a line comes from 8 control points (4 bands each), eased over time and blended between
        // with a cosine, so a loud band bends the line gently instead of putting a kink in it.
        for (int c = 0; c < ENVELOPE; c++) {
            float sum = 0;
            for (int b = c * (BARS / ENVELOPE); b < (c + 1) * (BARS / ENVELOPE); b++) sum += shown[b];
            float target = sum / (BARS / ENVELOPE);
            envelope[c] += (target - envelope[c]) * Math.min(1f, 6f * waveStep);
        }
        float mid = h * 0.55f;
        float[][] style = {   // wavelengths across the screen, speed, alpha
                {1.3f, 0.9f, 0.95f}, {1.9f, -0.6f, 0.7f}, {2.6f, 1.4f, 0.5f}};
        for (int l = 0; l < 3; l++) {
            float cycles = style[l][0], speed = style[l][1];
            wave.rewind();
            float px = 0, py = 0;
            for (int i = 0; i < WAVE_POINTS; i++) {
                float f = i / (float) (WAVE_POINTS - 1);
                float pos = f * (ENVELOPE - 1);
                int c0 = (int) pos, c1 = Math.min(ENVELOPE - 1, c0 + 1);
                float mu = (1 - (float) Math.cos((pos - c0) * Math.PI)) / 2;
                float band = envelope[c0] * (1 - mu) + envelope[c1] * mu;
                float amp = (0.06f + band * 0.9f) * h * 0.4f;
                float x = f * w, y = mid + amp * (float) Math.sin(f * cycles * 2 * Math.PI + t * speed * 2 + l * 2.1);
                if (i == 0) {
                    wave.moveTo(x, y);
                } else {
                    wave.quadTo(px, py, (px + x) / 2, (py + y) / 2);   // through the midpoints: no corners
                }
                px = x;
                py = y;
            }
            wave.lineTo(px, py);
            line.setColor((accent & 0x00FFFFFF) | (Math.round(style[l][2] * 255) << 24));
            canvas.drawPath(wave, line);
        }
    }

    /** Without the sound itself: each bar breathes on its own slow mix of waves. */
    private void animatedTargets(float t) {
        for (int b = 0; b < BARS; b++) {
            double v = 0.55 * Math.abs(Math.sin(t * (1.3 + (b % 5) * 0.37) + b * 1.7))
                    + 0.30 * Math.abs(Math.sin(t * (0.6 + (b % 3) * 0.21) + b * 0.9));
            target[b] = (float) Math.min(1.0, 0.08 + v * (0.55 + 0.45 * Math.sin(t * 0.5 + b * 0.4) * 0.5 + 0.225));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        closeVisualizer();
        super.onDetachedFromWindow();
    }
}
