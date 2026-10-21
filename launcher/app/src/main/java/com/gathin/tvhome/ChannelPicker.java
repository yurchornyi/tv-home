package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of an app's channels go on its shelf (LiveChannels): a glass list of every channel, a tick on the chosen
 * ones; OK ticks or unticks, Back closes and keeps the choice. The order chosen is the order on the shelf.
 */
final class ChannelPicker {

    interface Done { void onDone(Set<String> chosen); }

    private final Set<String> chosen;

    private ChannelPicker(Set<String> chosen) {
        this.chosen = chosen;
    }

    static PopupWindow show(Activity activity, String appName, List<LiveChannels.Channel> channels, Set<String> chosenNow,
                            Done done) {
        float d = activity.getResources().getDisplayMetrics().density;
        ChannelPicker picker = new ChannelPicker(new LinkedHashSet<>(chosenNow));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding((int) (10 * d), (int) (14 * d), (int) (10 * d), (int) (10 * d));
        panel.setElevation(12 * d);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.channels_on_shelf, appName));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(activity.getResources().getFont(R.font.outfit_medium));
        title.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (8 * d));
        panel.addView(title);

        TextView hint = new TextView(activity);
        hint.setText(R.string.channels_hint);
        hint.setTextColor(0x8CFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (10 * d));
        panel.addView(hint);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(picker.new Rows(channels, d));
        list.setVerticalScrollBarEnabled(false);
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (420 * d)));

        PopupWindow popup = new PopupWindow(panel, (int) (420 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> done.onDone(picker.chosen));
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        list.post(() -> {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
        });
        return popup;
    }

    private final class Rows extends RecyclerView.Adapter<Row> {
        private final List<LiveChannels.Channel> channels;
        private final float d;

        Rows(List<LiveChannels.Channel> channels, float d) {
            this.channels = channels;
            this.d = d;
        }

        @NonNull @Override
        @android.annotation.SuppressLint("ResourceType")   // pill_text is a colour selector that lives in drawable/
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView t = new TextView(parent.getContext());
            t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            t.setBackgroundResource(R.drawable.pill_bg);
            // The same text colours as the top bar's pills: dark on the white focused pill, light otherwise.
            t.setTextColor(parent.getContext().getResources().getColorStateList(R.drawable.pill_text, null));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTypeface(parent.getContext().getResources().getFont(R.font.outfit_regular));
            t.setPadding((int) (14 * d), (int) (9 * d), (int) (14 * d), (int) (9 * d));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setFocusable(true);
            t.setCompoundDrawablePadding((int) (10 * d));
            t.setGravity(Gravity.CENTER_VERTICAL);
            return new Row(t);
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int position) {
            LiveChannels.Channel c = channels.get(position);
            h.bind(c, chosen.contains(c.id));
            h.text.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                LiveChannels.Channel ch = channels.get(pos);
                if (!chosen.remove(ch.id)) chosen.add(ch.id);
                h.bind(ch, chosen.contains(ch.id));
            });
            // The OK-up that opened the picker must not tick the first row.
            final boolean[] pressed = {false};
            h.text.setOnKeyListener((v, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) pressed[0] = true;
                else if (!pressed[0]) return true;
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return channels.size();
        }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final TextView text;

        Row(TextView t) {
            super(t);
            text = t;
        }

        void bind(LiveChannels.Channel c, boolean on) {
            text.setText(c.name);
            text.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, on ? R.drawable.library_check : 0, 0);
            text.setAlpha(on ? 1f : 0.8f);
        }
    }
}
