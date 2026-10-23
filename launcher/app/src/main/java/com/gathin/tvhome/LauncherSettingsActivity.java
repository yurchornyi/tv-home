package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's own settings, laid out as Apple TV lays out its settings: on the left a large glass icon, the title,
 * and a line on whichever row has focus; on the right the rows in groups, each with its value at the end. A value
 * changes in place with Left and Right (or OK), so nothing opens a dialog: apps per row, motion, the sleep timer.
 * Version checks for an update and installs it. System screens stay one row away.
 */
public final class LauncherSettingsActivity extends Activity {

    static final String EXTRA_THEN = "then", EXTRA_CHANGED = "changed";
    static final String THEN_ADD = "add", THEN_REORDER = "reorder", THEN_MODULES = "modules";
    private static final int[] SLEEP_STEPS = {0, 15, 30, 45, 60, 90, 120};
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** One settings row: its label, the value it shows now, what OK and Left/Right do, and its description. */
    private final class Row {
        final LinearLayout view;
        final TextView label, value, chevron;
        final String hint;
        final Runnable ok;
        final Stepper step;
        java.util.function.Supplier<String> shown;
        java.util.function.BooleanSupplier enabled = () -> true;

        Row(String labelText, String hint, Runnable ok, Stepper step) {
            this.hint = hint;
            this.ok = ok;
            this.step = step;
            view = new LinearLayout(LauncherSettingsActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setFocusable(true);
            view.setClickable(true);
            view.setPadding(dp(26), 0, dp(22), 0);
            label = text(labelText, 21, medium, 0xFFFFFFFF);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            view.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            value = text("", 19, regular, 0x99FFFFFF);
            value.setSingleLine(true);
            view.addView(value);
            chevron = text(step != null ? "‹ ›" : "›", 20, regular, 0x66FFFFFF);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(dp(14));
            view.addView(chevron, clp);
            view.setBackground(rowBackground(false));
            view.setOnFocusChangeListener((v, focus) -> paint(focus));
            view.setOnClickListener(v -> {
                if (!enabled.getAsBoolean()) return;
                if (ok != null) ok.run();
                else if (step != null) step.by(1);
                refresh();
            });
            view.setOnKeyListener((v, code, e) -> {
                if (step == null || e.getAction() != KeyEvent.ACTION_DOWN) return false;
                boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
                if (dir == 0) return false;
                if (enabled.getAsBoolean()) {
                    step.by(rtl ? -dir : dir);
                    refresh();
                }
                return true;   // Left/Right change the value; they never move focus out of the list
            });
        }

        void paint(boolean focus) {
            boolean on = enabled.getAsBoolean();
            view.setBackground(rowBackground(focus));
            label.setTextColor(focus ? 0xFF000000 : on ? 0xFFFFFFFF : 0x66FFFFFF);
            value.setTextColor(focus ? 0x99000000 : on ? 0x99FFFFFF : 0x44FFFFFF);
            chevron.setTextColor(focus ? 0x66000000 : 0x55FFFFFF);
            view.animate().scaleX(focus ? 1.025f : 1f).scaleY(focus ? 1.025f : 1f)
                    .setDuration(220).setInterpolator(EASE).start();
            if (focus) showHint(hint);
        }
    }

    private interface Stepper { void by(int direction); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private SharedPreferences choices;
    private int columns;
    private boolean animations, advancedEffects, reducedMotion, moduleCards, showInputs;
    /** A setting changed: Home rebuilds when this screen closes. */
    private boolean changed;
    private Typeface medium, regular, light;
    private TextView hintView;
    private Updater updater;
    private String updateState;   // null: version only; else what the Version row says now
    private float density;
    /** The signature's turning mark; stopped when the screen goes, or it would keep ticking on Home's thread. */
    private android.animation.ObjectAnimator turn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        medium = getResources().getFont(R.font.outfit_medium);
        regular = getResources().getFont(R.font.outfit_regular);
        light = getResources().getFont(R.font.outfit_light);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        reducedMotion = config.reducedMotion;
        moduleCards = config.moduleCards;
        showInputs = config.showInputs;
        updater = new Updater(this, config.updateUrl, version -> {
            updateState = getString(R.string.update_to, version);
            refresh();
        });
        updater.onUpToDate(() -> {
            updateState = getString(R.string.up_to_date);
            refresh();
        });
        if (updater.readyVersion() != null) updateState = getString(R.string.update_to, updater.readyVersion());
        setContentView(build());
        refresh();
        rows.get(0).view.requestFocus();
        // The sleep timer's minutes left tick down while this screen is open.
        handler.postDelayed(tick, 30_000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        updater.onUpToDate(null);
        if (turn != null) turn.cancel();
        super.onDestroy();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B1E24, 0xFF0C0D10, 0xFF050506});
        root.setBackground(bg);

        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(split, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Left: the icon, the title, and the focused row's description, as Apple TV's settings do.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setPadding(dp(56), dp(72), dp(32), dp(30));
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x12FFFFFF});
        glass.setCornerRadius(dp(34));
        glass.setStroke(Math.max(1, dp(1)), 0x30FFFFFF);
        tile.setBackground(glass);
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setColorFilter(0xFFFFFFFF);
        tile.addView(gear, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        left.addView(tile, new LinearLayout.LayoutParams(dp(160), dp(160)));
        TextView title = text(getString(R.string.launcher_settings), 27, medium, 0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(22);
        left.addView(title, tlp);
        hintView = text("", 16, light, 0xB3FFFFFF);
        hintView.setGravity(Gravity.CENTER);
        hintView.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        left.addView(hintView, hlp);
        // The signature, small and grey at the foot of the panel; its mark turns slowly unless motion is off.
        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout madeBy = new LinearLayout(this);
        madeBy.setOrientation(LinearLayout.HORIZONTAL);
        madeBy.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.made_by);
        madeBy.addView(mark, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView sign = text(getString(R.string.made_by), 13, regular, 0xFF9A98A3);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMarginStart(dp(7));
        madeBy.addView(sign, mlp);
        left.addView(madeBy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (animations && !reducedMotion) {
            turn = android.animation.ObjectAnimator.ofFloat(mark, View.ROTATION, 0f, 360f);
            turn.setDuration(6000);
            turn.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            turn.setInterpolator(new android.view.animation.LinearInterpolator());
            turn.start();
        }
        split.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f));

        // Right: the rows, in groups.
        // The fading edge stays on for its length only: ScrollView keeps the focused row that far from the top and
        // bottom, as on Apple TV. The fade itself is not drawn: Realtek's GPU (TCL UnionTV) drew it as a black band
        // across the rows, and over the white focused row it greyed the text out.
        ScrollView scroll = new ScrollView(this) {
            @Override protected float getTopFadingEdgeStrength() { return 0f; }
            @Override protected float getBottomFadingEdgeStrength() { return 0f; }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(36));
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(64), dp(56), dp(56));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        // Room for the focused row to grow (it scales up a little) without its rounded ends being cut.
        list.setPadding(dp(16), dp(6), dp(16), dp(6));
        list.setClipToPadding(false);
        scroll.addView(list);
        split.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f));

        section(list, R.string.settings_section_home, true);
        Row cols = row(list, getString(R.string.columns_label), getString(R.string.columns_hint), null, dir -> {
            columns = Math.max(4, Math.min(7, columns + dir));
            choices.edit().putInt("columns", columns).apply();
            markChanged();
        });
        cols.shown = () -> String.valueOf(columns);
        Row theme = row(list, getString(R.string.follow_theme_short), getString(R.string.follow_theme_hint), () -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").remove("show_inputs").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            showInputs = current.showInputs;
            markChanged();
        }, null);
        theme.shown = () -> getString(hasOnTvChoices() ? R.string.settings_reset : R.string.settings_in_use);
        theme.enabled = this::hasOnTvChoices;
        // Rows that act on Home itself: this screen closes and Home does it (MainActivity.runSettingsAction).
        row(list, getString(R.string.add_to_home), getString(R.string.add_to_home_hint), () -> back(THEN_ADD), null)
                .shown = () -> "";
        row(list, getString(R.string.reorder), getString(R.string.reorder_settings_hint), () -> back(THEN_REORDER), null)
                .shown = () -> "";
        if (moduleCards) {
            row(list, getString(R.string.module_cards), getString(R.string.module_cards_hint), () -> back(THEN_MODULES), null)
                    .shown = () -> "";
        }

        Row inputs = row(list, getString(R.string.inputs_button), getString(R.string.inputs_button_hint), null, dir -> {
            showInputs = !showInputs;
            choices.edit().putBoolean("show_inputs", showInputs).apply();
            markChanged();
        });
        inputs.shown = () -> getString(showInputs ? R.string.on : R.string.off);

        section(list, R.string.settings_section_motion, false);
        Row anim = row(list, getString(R.string.animations_label), getString(R.string.animations_hint), null, dir -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations).putBoolean("advanced_effects", advancedEffects).apply();
            markChanged();
        });
        anim.shown = () -> getString(animations ? R.string.on : R.string.off);
        Row adv = row(list, getString(R.string.advanced_effects), getString(R.string.advanced_effects_warning), null, dir -> {
            advancedEffects = !advancedEffects;
            choices.edit().putBoolean("advanced_effects", advancedEffects).apply();
            markChanged();
        });
        adv.shown = () -> getString(advancedEffects ? R.string.on : R.string.off);
        adv.enabled = () -> animations;

        section(list, R.string.settings_section_tv, false);
        Row sleep = row(list, getString(R.string.sleep_timer), getString(R.string.sleep_hint), null, this::stepSleep);
        sleep.shown = () -> {
            int left2 = SleepTimer.minutesLeft(this);
            return left2 > 0 ? getString(R.string.sleep_value_left, left2) : getString(R.string.off);
        };
        Row home = row(list, getString(R.string.home_settings), getString(R.string.home_settings_hint), () -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        }, null);
        home.shown = () -> "";
        Row tv = row(list, getString(R.string.tv_settings), getString(R.string.tv_settings_hint),
                () -> open(new Intent(Settings.ACTION_SETTINGS)), null);
        tv.shown = () -> "";

        section(list, R.string.settings_section_about, false);
        Row version = row(list, getString(R.string.version_label), getString(R.string.version_hint), () -> {
            if (updater.readyVersion() != null) {
                updater.install();
                return;
            }
            updateState = getString(R.string.checking);
            updater.check(true);
        }, null);
        version.shown = () -> updateState != null ? BuildConfig.VERSION_NAME + "  ·  " + updateState : BuildConfig.VERSION_NAME;
        return root;
    }

    /** Off, 15, 30, 45, 60, 90, 120 minutes: Right goes on to the next, Left back; it takes effect at once. */
    private void stepSleep(int dir) {
        // A running timer sits between steps (23 minutes left): Right goes to the next step above it, Left to the
        // one below, down to Off; from Off, Right is 15 minutes and Left the longest.
        int left = SleepTimer.minutesLeft(this);
        int minutes = 0;
        if (dir > 0) {
            for (int s : SLEEP_STEPS) if (s > left) { minutes = s; break; }
        } else if (left == 0) {
            minutes = SLEEP_STEPS[SLEEP_STEPS.length - 1];
        } else {
            for (int s : SLEEP_STEPS) if (s < left) minutes = s;
        }
        if (minutes == 0) {
            SleepTimer.cancel(this);
        } else {
            if (!SleepService.running()) {
                SelfSetup.ensure(this);
                if (!SelfSetup.canWriteSettings(this)) {
                    Toast.makeText(this, R.string.sleep_service_off, Toast.LENGTH_LONG).show();
                    open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return;
                }
            }
            SleepTimer.set(this, minutes);
        }
        // Not markChanged(): the timer needs no rebuild of Home, whose clock line picks it up on return.
    }

    private void markChanged() {
        changed = true;
        setResult(RESULT_OK);
    }

    /** Closes this screen and has Home do something (add an app, reorder, module cards). */
    private void back(String then) {
        setResult(RESULT_FIRST_USER, new Intent().putExtra(EXTRA_THEN, then).putExtra(EXTRA_CHANGED, changed));
        finish();
    }

    private Row row(LinearLayout list, String label, String hint, Runnable ok, Stepper step) {
        Row r = new Row(label, hint, ok, step);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        lp.topMargin = dp(8);
        list.addView(r.view, lp);
        rows.add(r);
        return r;
    }

    private void section(LinearLayout list, int title, boolean first) {
        TextView t = text(getString(title).toUpperCase(java.util.Locale.getDefault()), 14, medium, 0x80FFFFFF);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(26), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? 0 : dp(30);
        lp.bottomMargin = dp(2);
        list.addView(t, lp);
    }

    private void refresh() {
        for (Row r : rows) {
            r.value.setText(r.shown != null ? r.shown.get() : "");
            r.view.setAlpha(r.enabled.getAsBoolean() ? 1f : 0.55f);
            r.paint(r.view.hasFocus());
        }
    }

    private void showHint(String hint) {
        if (hintView == null || hint == null || hint.contentEquals(hintView.getText())) return;
        hintView.animate().cancel();
        hintView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            hintView.setText(hint);
            hintView.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start();
        }).start();
    }

    private GradientDrawable rowBackground(boolean focus) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        if (focus) {
            d.setColor(0xF5FFFFFF);
        } else {
            d.setColors(new int[]{0x1FFFFFFF, 0x0FFFFFFF});
            d.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            d.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        }
        return d;
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects") || choices.contains("show_inputs");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
