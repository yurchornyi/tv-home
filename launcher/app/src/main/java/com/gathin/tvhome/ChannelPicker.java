package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which of an app's channels go on its shelf (LiveChannels), as a glass list with a tab per part: "On the shelf" (the
 * chosen ones, in their order), "Popular", then each category (LiveChannels.inCategory), and "Never show" last; Left and
 * Right move between the tabs. Each row has the channel's logo, name and a tick; OK ticks or unticks, holding OK sends
 * a channel to "Never show" (the automatic order never brings it back), OK on one there brings it back. Back closes
 * and keeps the choice. Channels listed twice under one name show once.
 */
@SuppressLint("ResourceType")   // pill_text is a colour selector kept in drawable/
final class ChannelPicker {

    interface Done { void onDone(Set<String> chosen, Set<String> hidden); }

    interface Images { void load(String url, ShelfFeed.ImageListener listener); }

    /** Tab ids besides the categories (LiveChannels.POPULAR to ADULT_ONLY). */
    private static final int SHELF = -1, NEVER = 100;

    private final Activity activity;
    private final float d;
    private final List<LiveChannels.Channel> channels;
    private final Set<String> chosen;
    private final Set<String> hidden;
    private final Images images;
    /** The tabs as shown (SHELF, a category, NEVER), each with its channels; rebuilt when a channel changes part. */
    private final List<Integer> tabs = new ArrayList<>();
    private final List<List<LiveChannels.Channel>> tabRows = new ArrayList<>();
    private int tab = SHELF;
    /** The rows of the tab shown. */
    private List<LiveChannels.Channel> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private RecyclerView list;
    private LinearLayout strip;
    private android.widget.HorizontalScrollView stripScroll;

    private ChannelPicker(Activity activity, List<LiveChannels.Channel> all, Set<String> chosen, Set<String> hidden,
                          Images images) {
        this.activity = activity;
        this.d = activity.getResources().getDisplayMetrics().density;
        this.chosen = chosen;
        this.hidden = hidden;
        this.images = images;
        // One row per name: a chosen one wins, then one with a logo.
        Map<String, LiveChannels.Channel> byName = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (LiveChannels.Channel c : all) {
            String key = c.name.trim().toLowerCase(Locale.ROOT);
            LiveChannels.Channel known = byName.get(key);
            if (known == null) {
                byName.put(key, c);
                order.add(key);
            } else if (!chosen.contains(known.id) && (chosen.contains(c.id) || (known.logo == null && c.logo != null))) {
                byName.put(key, c);
            }
        }
        List<LiveChannels.Channel> unique = new ArrayList<>();
        for (String key : order) unique.add(byName.get(key));
        channels = unique;
        rebuild();
        tab = tabs.contains(SHELF) ? SHELF : LiveChannels.POPULAR;
    }

    static PopupWindow show(Activity activity, String appName, List<LiveChannels.Channel> all, Set<String> chosenNow,
                            Set<String> hiddenNow, Images images, Done done) {
        float d = activity.getResources().getDisplayMetrics().density;
        ChannelPicker picker = new ChannelPicker(activity, all, new LinkedHashSet<>(chosenNow),
                new LinkedHashSet<>(hiddenNow), images);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding((int) (12 * d), (int) (16 * d), (int) (12 * d), (int) (12 * d));
        panel.setElevation(12 * d);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.channels_on_shelf, appName));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(activity.getResources().getFont(R.font.outfit_medium));
        title.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (4 * d));
        panel.addView(title);

        TextView hint = new TextView(activity);
        hint.setText(R.string.channels_hint);
        hint.setTextColor(0x8CFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (10 * d));
        panel.addView(hint);

        // The tabs, Left and Right move along them; the arrows say so.
        LinearLayout tabBar = new LinearLayout(activity);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.addView(arrow(activity, "\u2039"));
        android.widget.HorizontalScrollView stripScroll = new android.widget.HorizontalScrollView(activity);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setFocusable(false);
        LinearLayout strip = new LinearLayout(activity);
        stripScroll.addView(strip);
        tabBar.addView(stripScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabBar.addView(arrow(activity, "\u203A"));
        tabBar.setPadding(0, 0, 0, (int) (8 * d));
        panel.addView(tabBar);
        picker.strip = strip;
        picker.stripScroll = stripScroll;
        picker.showTab();

        RecyclerView list = new RecyclerView(activity);
        picker.list = list;
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(picker.adapter);
        list.setItemAnimator(null);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, (int) (8 * d));
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (390 * d)));

        PopupWindow popup = new PopupWindow(panel, (int) (460 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> done.onDone(picker.chosen, picker.hidden));
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        list.post(() -> picker.focusRow(0));
        return popup;
    }

    /** The tabs from the current choice: the shelf's in their order, the categories with a channel, "Never show". */
    private void rebuild() {
        tabs.clear();
        tabRows.clear();
        Map<String, LiveChannels.Channel> byId = new HashMap<>();
        for (LiveChannels.Channel c : channels) byId.put(c.id, c);
        List<LiveChannels.Channel> onShelf = new ArrayList<>();
        for (String id : chosen) {
            LiveChannels.Channel c = byId.get(id);
            if (c != null && !hidden.contains(id)) onShelf.add(c);
        }
        add(SHELF, onShelf);
        List<LiveChannels.Channel> shown = new ArrayList<>();
        for (LiveChannels.Channel c : channels) if (!hidden.contains(c.id)) shown.add(c);
        for (int category = LiveChannels.POPULAR; category <= LiveChannels.ADULT_ONLY; category++) {
            add(category, LiveChannels.inCategory(shown, category));
        }
        List<LiveChannels.Channel> never = new ArrayList<>();
        for (LiveChannels.Channel c : channels) if (hidden.contains(c.id)) never.add(c);
        add(NEVER, never);
    }

    private void add(int id, List<LiveChannels.Channel> in) {
        if (in.isEmpty()) return;
        tabs.add(id);
        tabRows.add(in);
    }

    /** The tab `tab` (or the nearest one left) in the strip and the list. */
    private void showTab() {
        int at = tabs.indexOf(tab);
        if (at < 0) {
            at = 0;
            while (at < tabs.size() - 1 && tabs.get(at) < tab) at++;
            tab = tabs.get(at);
        }
        rows = tabRows.get(at);
        adapter.notifyDataSetChanged();
        String[] categories = activity.getResources().getStringArray(R.array.channel_categories);
        strip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            int id = tabs.get(i);
            TextView t = new TextView(activity);
            t.setText(id == SHELF ? activity.getString(R.string.channels_section_shelf)
                    : id == NEVER ? activity.getString(R.string.channels_section_never) : categories[id]);
            t.setTextColor(i == at ? 0xFFFFFFFF : 0x99FFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(activity.getResources().getFont(i == at ? R.font.outfit_medium : R.font.outfit_regular));
            t.setSingleLine(true);
            t.setPadding(dp(12), dp(5), dp(12), dp(6));
            if (i == at) {
                t.setBackgroundResource(R.drawable.pill_bg);
                t.setActivated(true);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(4));
            strip.addView(t, lp);
        }
        int selected = at;
        stripScroll.post(() -> {
            View t = strip.getChildAt(selected);
            if (t != null) stripScroll.smoothScrollTo(Math.max(0, t.getLeft() - (stripScroll.getWidth() - t.getWidth()) / 2), 0);
        });
    }

    /** Left or Right: the tab next to this one, its first row focused. */
    private void step(int by) {
        int at = tabs.indexOf(tab) + by;
        if (at < 0 || at >= tabs.size()) return;
        tab = tabs.get(at);
        showTab();
        focusRow(0);
    }

    /** Focus on row `at` (or the last one). */
    private void focusRow(int at) {
        if (rows.isEmpty()) return;
        int target = Math.max(0, Math.min(at, rows.size() - 1));
        list.scrollToPosition(target);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(target);
            if (h != null) h.itemView.requestFocus();
        });
    }

    /** A channel changed part (to or from "Never show"): the tabs are rebuilt and focus stays near where it was. */
    private void moved(int at) {
        rebuild();
        showTab();
        focusRow(at);
    }

    private static TextView arrow(Activity activity, String glyph) {
        TextView a = new TextView(activity);
        a.setText(glyph);
        a.setTextColor(0x8CFFFFFF);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        float d = activity.getResources().getDisplayMetrics().density;
        a.setPadding((int) (6 * d), 0, (int) (6 * d), (int) (2 * d));
        return a;
    }

    private int dp(int v) {
        return Math.round(v * d);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() {
            return rows.size();
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(activity);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(4);
            row.setLayoutParams(lp);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.pill_bg);
            row.setFocusable(true);
            row.setClickable(true);
            row.setPadding(dp(8), 0, dp(14), 0);
            FrameLayout logoBox = new FrameLayout(activity);
            GradientDrawable box = new GradientDrawable();
            box.setColor(0xFF1C1C1E);
            box.setCornerRadius(dp(8));
            logoBox.setBackground(box);
            logoBox.setClipToOutline(true);
            ImageView logo = new ImageView(activity);
            logo.setId(R.id.icon);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(5), dp(4), dp(5), dp(4));
            logoBox.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(62), dp(38));
            blp.setMarginEnd(dp(14));
            row.addView(logoBox, blp);
            TextView name = new TextView(activity);
            name.setId(R.id.title);
            name.setTextColor(activity.getResources().getColorStateList(R.drawable.pill_text, null));
            name.setDuplicateParentStateEnabled(true);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTypeface(activity.getResources().getFont(R.font.outfit_regular));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageView tick = new ImageView(activity);
            tick.setId(R.id.dot);
            row.addView(tick, new LinearLayout.LayoutParams(dp(24), dp(24)));
            return new RecyclerView.ViewHolder(row) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            LiveChannels.Channel c = rows.get(position);
            View row = h.itemView;
            TextView name = row.findViewById(R.id.title);
            ImageView logo = row.findViewById(R.id.icon);
            ImageView tick = row.findViewById(R.id.dot);
            boolean never = hidden.contains(c.id);
            name.setText(c.name);
            row.setAlpha(never ? 0.6f : 1f);
            bindTick(tick, c);
            logo.setImageDrawable(null);
            logo.setTag(c.logo);
            if (c.logo != null) images.load(c.logo, (url, bmp) -> {
                if (bmp != null && url.equals(logo.getTag())) logo.setImageBitmap(LiveChannels.trim(bmp));
            });
            row.setOnClickListener(v -> {
                int at = h.getBindingAdapterPosition();
                if (at == RecyclerView.NO_POSITION) return;
                if (hidden.remove(c.id)) {
                    moved(at);   // back from "Never show"
                } else {
                    // The shelf's row holds twenty: one more is not ticked.
                    if (!chosen.remove(c.id) && chosen.size() < LiveChannels.ROW_MAX) chosen.add(c.id);
                    bindTick(tick, c);
                }
            });
            row.setOnLongClickListener(v -> {
                int at = h.getBindingAdapterPosition();
                if (at == RecyclerView.NO_POSITION || hidden.contains(c.id)) return true;
                hidden.add(c.id);
                chosen.remove(c.id);
                moved(at);
                return true;
            });
            // The OK-up that opened the picker must not tick the first row.
            final boolean[] pressed = {false};
            row.setOnKeyListener((v, code, event) -> {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) pressed[0] = true;
                else if (!pressed[0]) return true;
                return false;
            });
        }

        private void bindTick(ImageView tick, LiveChannels.Channel c) {
            if (hidden.contains(c.id)) tick.setImageResource(R.drawable.ic_np_close);
            else tick.setImageResource(chosen.contains(c.id) ? R.drawable.library_check : 0);
            tick.setAlpha(hidden.contains(c.id) ? 0.7f : 1f);
            tick.setDuplicateParentStateEnabled(true);
            tick.setImageTintList(hidden.contains(c.id)
                    ? activity.getResources().getColorStateList(R.drawable.pill_text, null) : null);
        }
    }
}
