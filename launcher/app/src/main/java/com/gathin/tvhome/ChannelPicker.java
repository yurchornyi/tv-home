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
 * Which of an app's channels go on its shelf (LiveChannels), as a glass list in three parts: "On the shelf" (the
 * chosen ones, in their order), "All channels" by category (LiveChannels.byCategory), and "Never show" at the bottom. Each row has the channel's logo,
 * name and a tick; OK ticks or unticks, holding OK sends a channel to "Never show" (the automatic order never brings
 * it back), OK on one there brings it back. Back closes and keeps the choice. Channels listed twice under one name
 * show once.
 */
@SuppressLint("ResourceType")   // pill_text is a colour selector kept in drawable/
final class ChannelPicker {

    interface Done { void onDone(Set<String> chosen, Set<String> hidden); }

    interface Images { void load(String url, ShelfFeed.ImageListener listener); }

    private static final int HEADER = 0, ROW = 1;

    private final Activity activity;
    private final float d;
    private final List<LiveChannels.Channel> channels;
    private final Set<String> chosen;
    private final Set<String> hidden;
    private final Images images;
    /** Rows as shown: a String is a section title, a Channel a row. Rebuilt when a channel changes section. */
    private final List<Object> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private RecyclerView list;

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
        channels = LiveChannels.byCategory(unique);
        rebuild();
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

        RecyclerView list = new RecyclerView(activity);
        picker.list = list;
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(picker.adapter);
        list.setItemAnimator(null);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, (int) (8 * d));
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (430 * d)));

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

    /** The three parts from the current choice; the chosen ones in their order first. */
    private void rebuild() {
        rows.clear();
        Map<String, LiveChannels.Channel> byId = new HashMap<>();
        for (LiveChannels.Channel c : channels) byId.put(c.id, c);
        List<LiveChannels.Channel> onShelf = new ArrayList<>();
        for (String id : chosen) {
            LiveChannels.Channel c = byId.get(id);
            if (c != null && !hidden.contains(id)) onShelf.add(c);
        }
        if (!onShelf.isEmpty()) {
            rows.add(activity.getString(R.string.channels_section_shelf));
            rows.addAll(onShelf);
        }
        // "All channels" as its categories, each under its own title (channels is sorted by category).
        String[] categories = activity.getResources().getStringArray(R.array.channel_categories);
        int shown = -1;
        for (LiveChannels.Channel c : channels) {
            if (onShelf.contains(c) || hidden.contains(c.id)) continue;
            int category = LiveChannels.category(c);
            if (category != shown) {
                rows.add(categories[category]);
                shown = category;
            }
            rows.add(c);
        }
        List<LiveChannels.Channel> never = new ArrayList<>();
        for (LiveChannels.Channel c : channels) if (hidden.contains(c.id)) never.add(c);
        if (!never.isEmpty()) {
            rows.add(activity.getString(R.string.channels_section_never));
            rows.addAll(never);
        }
    }

    /** Focus on the first channel row at or after `from`. */
    private void focusRow(int from) {
        for (int i = Math.max(0, from); i < rows.size(); i++) {
            if (!(rows.get(i) instanceof LiveChannels.Channel)) continue;
            int target = i;
            list.scrollToPosition(target);
            list.post(() -> {
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(target);
                if (h != null) h.itemView.requestFocus();
            });
            return;
        }
    }

    /** A channel changed part (to or from "Never show"): the list is rebuilt and focus stays near where it was. */
    private void moved(int at) {
        rebuild();
        adapter.notifyDataSetChanged();
        focusRow(Math.min(at, rows.size() - 1));
    }

    private int dp(int v) {
        return Math.round(v * d);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? HEADER : ROW;
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == HEADER) {
                TextView t = new TextView(activity);
                t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                t.setTextColor(0x99FFFFFF);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setAllCaps(true);
                t.setLetterSpacing(0.14f);
                t.setTypeface(activity.getResources().getFont(R.font.outfit_medium));
                t.setPadding(dp(14), dp(14), dp(14), dp(6));
                return new RecyclerView.ViewHolder(t) { };
            }
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
            Object item = rows.get(position);
            if (item instanceof String) {
                ((TextView) h.itemView).setText((String) item);
                return;
            }
            LiveChannels.Channel c = (LiveChannels.Channel) item;
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
                if (bmp != null && url.equals(logo.getTag())) logo.setImageBitmap(bmp);
            });
            row.setOnClickListener(v -> {
                int at = h.getBindingAdapterPosition();
                if (at == RecyclerView.NO_POSITION) return;
                if (hidden.remove(c.id)) {
                    moved(at);   // back from "Never show"
                } else {
                    if (!chosen.remove(c.id)) chosen.add(c.id);
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
