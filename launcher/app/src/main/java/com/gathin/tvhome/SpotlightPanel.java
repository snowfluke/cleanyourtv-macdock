package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * mac_home: the app panel over Home. A glass card with the last-opened apps on top and every TV app A to Z below,
 * seven to a row. Back, or Up from the first row, closes it. Labels and icons load once, off the UI thread.
 */
final class SpotlightPanel extends FrameLayout {
    interface Listener {
        void onOpen(String pkg);
        void onClosed();
    }

    static final class App {
        final String pkg, label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final int COLUMNS = 7, RECENT = 7;
    /** The tile for this launcher's own settings, listed with the apps so people can find it. */
    static final String HOME_SETTINGS = "#home_settings";
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private Listener listener;
    private List<App> apps = new ArrayList<>();
    private Collection<String> hidden = new ArrayList<>();

    SpotlightPanel(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(0x66000000);
        setVisibility(GONE);
        setClickable(true);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xE62A2725);
        glass.setCornerRadius(28 * dp);
        glass.setStroke(Math.round(1 * dp), 0x33FFFFFF);
        card.setBackground(glass);
        int pad = Math.round(24 * dp);
        card.setPadding(pad, Math.round(20 * dp), pad, Math.round(12 * dp));

        TextView title = new TextView(context);
        title.setText(R.string.mac_panel_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(UiFont.get(context, R.string.font_light));
        card.addView(title);

        View rule = new View(context);
        rule.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * dp)));
        rp.topMargin = Math.round(12 * dp);
        card.addView(rule, rp);

        list = new RecyclerView(context);
        GridLayoutManager grid = new GridLayoutManager(context, COLUMNS);
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isLabel(position) ? COLUMNS : 1;
            }
        });
        list.setLayoutManager(grid);
        list.setAdapter(adapter);
        list.setClipToPadding(false);   // clipping at the padding would cut the first column's focus zoom
        // Room on every side for the focused tile's 8% zoom, which the padding would otherwise clip.
        list.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
        list.setClipChildren(false);
        list.setItemAnimator(null);
        card.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LayoutParams cp = new LayoutParams(0, 0, Gravity.CENTER);
        card.setTranslationY(-36 * dp);   // clear of the dock below
        addView(card, cp);
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * 0.74f), h = Math.round((b - t) * 0.72f);
            LayoutParams p = (LayoutParams) card.getLayoutParams();
            if (p.width != w || p.height != h) {
                p.width = w;
                p.height = h;
                card.post(() -> card.setLayoutParams(p));
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** Shows the panel, with what is known now, and refreshes the catalogue behind it. */
    void show(Collection<String> hiddenApps) {
        hidden = hiddenApps;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(140).start();
        adapter.build(apps);
        focusFirst();
        load();
    }

    void hide() {
        if (!isShowing()) return;
        animate().cancel();
        setVisibility(GONE);
        if (listener != null) listener.onClosed();
    }

    /** Called when a key arrives while nothing in the panel has focus. */
    void recoverFocus() {
        focusFirst();
    }

    private void focusFirst() {
        focusAt(adapter.firstApp(), 0);
    }

    /**
     * Focus the tile at this position once it is laid out. The tile may not exist on the first frame after a build,
     * so look again a few frames later rather than give up and leave the panel with no focus at all.
     */
    private void focusAt(int position, int attempt) {
        if (position < 0 || !isShowing()) return;
        if (attempt == 0) list.scrollToPosition(position);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(position);
            if (h != null && h.itemView.requestFocus()) return;
            if (attempt < 6) list.postDelayed(() -> focusAt(position, attempt + 1), 32);
        });
    }

    /**
     * Focus on a section's first row scrolls just far enough for the tile, which left the "Recent" or "All apps"
     * label half under the title's rule. Scroll the label fully into view as well.
     */
    private void showSectionLabel(View tile) {
        RecyclerView.ViewHolder h = list.findContainingViewHolder(tile);
        int label = h == null ? -1 : adapter.labelAbove(h.getBindingAdapterPosition());
        if (label < 0) return;
        list.post(() -> {
            RecyclerView.ViewHolder lh = list.findViewHolderForAdapterPosition(label);
            if (lh == null) {
                list.scrollToPosition(label);
                return;
            }
            int gap = lh.itemView.getTop() - list.getPaddingTop();
            if (gap < 0) list.smoothScrollBy(0, gap);
        });
    }

    /** The adapter position of the focused tile, or -1. */
    private int focusedPosition() {
        View f = list.findFocus();
        RecyclerView.ViewHolder h = f == null ? null : list.findContainingViewHolder(f);
        return h == null ? -1 : h.getBindingAdapterPosition();
    }

    private static boolean samePackages(List<App> a, List<App> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).pkg.equals(b.get(i).pkg) || !a.get(i).label.equals(b.get(i).label)) return false;
        }
        return true;
    }

    private void load() {
        Context app = getContext().getApplicationContext();
        Collection<String> skip = hidden;
        LOADER.execute(() -> {
            PackageManager pm = app.getPackageManager();
            Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<App> found = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(app.getPackageName()) || skip.contains(pkg) || seen.contains(pkg)) continue;
                seen.add(pkg);
                Drawable icon;
                try {
                    icon = IconNormalizer.of(app, pkg, ri.loadIcon(pm), Math.round(60 * app.getResources().getDisplayMetrics().density));
                } catch (RuntimeException e) {
                    icon = null;
                }
                found.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), icon));
            }
            // Home settings, as a tile among the apps: the long-press on the gear is not something anyone would guess.
            float density = app.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable();
            tile.setColor(0xFF3A3A3C);
            tile.setCornerRadius(60 * density * IconNormalizer.RADIUS);
            android.graphics.drawable.LayerDrawable gear = new android.graphics.drawable.LayerDrawable(
                    new Drawable[]{tile, app.getDrawable(R.drawable.ic_settings)});
            int inset = Math.round(14 * density);
            gear.setLayerInset(1, inset, inset, inset, inset);
            found.add(new App(HOME_SETTINGS, app.getString(R.string.mac_home_settings), gear));
            Collator order = Collator.getInstance();
            found.sort((a, b) -> order.compare(a.label, b.label));
            main.post(() -> {
                // Rebuilding the list throws away the focused tile, and with the dock and the top buttons locked
                // behind the panel, focus then has nowhere to go and the arrows stop working. So rebuild only when
                // the apps changed, and put focus back on the same place afterwards.
                boolean unchanged = samePackages(apps, found);
                apps = found;
                if (!isShowing() || unchanged) return;
                int at = focusedPosition();
                adapter.build(apps);
                if (at < 0 || at >= adapter.getItemCount() || adapter.isLabel(at)) focusFirst();
                else focusAt(at, 0);
            });
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) hide();
            return true;
        }
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
        if (arrow && event.getAction() == KeyEvent.ACTION_DOWN && list.findFocus() == null) {
            focusFirst();   // focus was lost: this press brings it back instead of doing nothing
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
            View f = findFocus();
            RecyclerView.ViewHolder h = f == null ? null : list.findContainingViewHolder(f);   // null when focus is not on a tile
            int pos = h == null ? -1 : h.getBindingAdapterPosition();
            if (pos >= 0 && adapter.inFirstRow(pos)) {
                hide();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Object> rows = new ArrayList<>();   // String = section label, App = tile

        void build(List<App> all) {
            rows.clear();
            List<App> recent = new ArrayList<>();
            for (App a : all) if (LastOpened.get(getContext(), a.pkg) > 0) recent.add(a);
            recent.sort((a, b) -> Long.compare(LastOpened.get(getContext(), b.pkg), LastOpened.get(getContext(), a.pkg)));
            if (!recent.isEmpty()) {
                rows.add(getContext().getString(R.string.mac_panel_recent));
                rows.addAll(recent.subList(0, Math.min(RECENT, recent.size())));
            }
            if (!all.isEmpty()) {
                rows.add(getContext().getString(R.string.mac_panel_all));
                rows.addAll(all);
            }
            notifyDataSetChanged();
        }

        /** The section label right above this tile's row, or -1 when the row is not a section's first. */
        int labelAbove(int position) {
            int start = position;
            while (start > 0 && !isLabel(start - 1)) start--;
            if (start == 0 || position - start >= COLUMNS) return -1;
            return start - 1;
        }

        boolean isLabel(int position) {
            return position >= 0 && position < rows.size() && rows.get(position) instanceof String;
        }

        int firstApp() {
            for (int i = 0; i < rows.size(); i++) if (!isLabel(i)) return i;
            return -1;
        }

        /** Whether this tile is in the first row of tiles (the row Up leaves the panel from). */
        boolean inFirstRow(int position) {
            int first = firstApp();
            if (first < 0 || isLabel(position)) return false;
            for (int i = first; i <= position; i++) if (isLabel(i)) return false;
            return position - first < COLUMNS;
        }

        @Override
        public int getItemViewType(int position) {
            return isLabel(position) ? 1 : 0;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Context c = parent.getContext();
            if (type == 1) {
                TextView label = new TextView(c);
                label.setTextColor(0x99FFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                label.setLetterSpacing(0.08f);
                label.setAllCaps(true);
                label.setTypeface(UiFont.get(c, R.string.font_medium));
                label.setPadding(Math.round(8 * dp), Math.round(10 * dp), 0, Math.round(4 * dp));
                return new RecyclerView.ViewHolder(label) { };
            }
            LinearLayout tile = new LinearLayout(c);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Math.round(8 * dp);
            tile.setPadding(p, p, p, p);
            tile.setFocusable(true);
            GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(16 * dp);
            focus.setColor(0x00000000);
            tile.setBackground(focus);
            tile.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x26FFFFFF : 0x00000000);
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
                if (has) showSectionLabel(v);
            });
            ImageView icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.225f);
                }
            });
            icon.setClipToOutline(true);
            int s = Math.round(60 * dp);
            tile.addView(icon, new LinearLayout.LayoutParams(s, s));
            TextView name = new TextView(c);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setTypeface(UiFont.get(c, R.string.font_medium));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = Math.round(6 * dp);
            tile.addView(name, np);
            return new RecyclerView.ViewHolder(tile) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (row instanceof String) {
                ((TextView) holder.itemView).setText((String) row);
                return;
            }
            App a = (App) row;
            LinearLayout tile = (LinearLayout) holder.itemView;
            ((ImageView) tile.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) tile.getChildAt(1)).setText(a.label);
            tile.setOnClickListener(v -> {
                if (listener != null) listener.onOpen(a.pkg);
            });
        }
    }
}
