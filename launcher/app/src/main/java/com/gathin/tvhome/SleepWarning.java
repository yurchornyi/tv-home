package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A minute before the sleep timer ends (SleepTimer), a card rises at the bottom of the screen over whatever app is
 * on: "The TV will sleep in 0:59", counting down, with Extend by 15 minutes (focused), 30 minutes, 1 hour. OK on one
 * adds that much to what is left; Back hides the card and the timer runs out as set. Nothing pressed: the card
 * goes at zero and the TV sleeps. It takes the remote's keys while up, so it needs the overlay permission
 * (build-and-install grants it); without it there is no warning and the timer works as before.
 */
final class SleepWarning {

    /** The alarm a minute before the end; SleepTimer sets it beside the sleep alarm itself. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            show(context);
        }
    }

    static final long LEAD_MS = 60_000L;
    private static final int[] EXTEND = {15, 30, 60};
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static View shown;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SleepWarning() { }

    static void show(Context context) {
        Context ctx = context.getApplicationContext();
        if (!Settings.canDrawOverlays(ctx)) return;
        main.post(() -> showNow(ctx));
    }

    /** Takes the card down (the timer was extended or cancelled elsewhere). */
    static void hide() {
        main.post(() -> {
            if (shown != null) close(shown.getContext(), shown, false);
        });
    }

    private static void showNow(Context ctx) {
        long end = SleepTimer.endsAt(ctx);
        if (end == 0 || end - System.currentTimeMillis() < 3_000) return;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (shown != null) {
            try { wm.removeView(shown); } catch (RuntimeException ignored) { }
            shown = null;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        Typeface medium = ctx.getResources().getFont(R.font.outfit_medium);
        Typeface regular = ctx.getResources().getFont(R.font.outfit_regular);

        // The card catches Back itself: a focusable overlay window has no activity to hand it to.
        LinearLayout card = new LinearLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) close(ctx, this, true);
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding((int) (40 * d), (int) (28 * d), (int) (40 * d), (int) (30 * d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2A2A2E, 0xFF161618});
        bg.setCornerRadius(28 * d);
        bg.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
        card.setBackground(bg);
        card.setElevation(24 * d);

        TextView title = new TextView(ctx);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(medium);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        TextView sub = new TextView(ctx);
        sub.setText(R.string.sleep_warning_hint);
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        sub.setTypeface(regular);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (6 * d);
        card.addView(sub, slp);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (22 * d);
        card.addView(buttons, blp);
        TextView first = null;
        for (int minutes : EXTEND) {
            TextView b = new TextView(ctx);
            b.setText(minutes >= 60 ? ctx.getString(R.string.sleep_extend_hours, minutes / 60)
                    : ctx.getString(R.string.sleep_extend, minutes));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            b.setTypeface(medium);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setPadding((int) (26 * d), (int) (12 * d), (int) (26 * d), (int) (12 * d));
            paint(b, false, d);
            b.setOnFocusChangeListener((v, focus) -> {
                paint((TextView) v, focus, d);
                v.animate().scaleX(focus ? 1.06f : 1f).scaleY(focus ? 1.06f : 1f).setDuration(200)
                        .setInterpolator(EASE).start();
            });
            b.setOnClickListener(v -> {
                SleepTimer.extend(ctx, minutes);
                close(ctx, card, true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (first != null) lp.setMarginStart((int) (14 * d));
            buttons.addView(b, lp);
            if (first == null) first = b;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Focusable: the remote's OK and arrows go to the card while it is up; the app behind keeps playing.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (64 * d);
        try {
            wm.addView(card, lp);
        } catch (RuntimeException e) {
            return;
        }
        shown = card;
        TextView focus = first;
        card.post(() -> { if (focus != null) focus.requestFocus(); });
        card.setAlpha(0f);
        card.setTranslationY(60 * d);
        card.animate().alpha(1f).translationY(0f).setDuration(520).setInterpolator(EASE).start();

        // The countdown, once a second, from what is really left; the card goes at zero, when the TV sleeps.
        Runnable count = new Runnable() {
            @Override public void run() {
                if (shown != card) return;
                long at = SleepTimer.endsAt(ctx);
                long left = at == 0 ? 0 : at - System.currentTimeMillis();
                if (left <= 0) {
                    close(ctx, card, false);
                    return;
                }
                long s = (left + 999) / 1000;
                title.setText(ctx.getString(R.string.sleep_warning, s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60)));
                main.postDelayed(this, 1000 - (left % 1000) + 5);
            }
        };
        count.run();
    }

    private static void paint(TextView b, boolean focus, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(24 * d);
        if (focus) {
            g.setColor(0xF5FFFFFF);
        } else {
            g.setColors(new int[]{0x2EFFFFFF, 0x14FFFFFF});
            g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            g.setStroke(Math.max(1, (int) d), 0x38FFFFFF);
        }
        b.setBackground(g);
        b.setTextColor(focus ? 0xFF000000 : 0xFFFFFFFF);
    }

    private static void close(Context ctx, View card, boolean animate) {
        if (shown == card) shown = null;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Runnable remove = () -> {
            try { wm.removeView(card); } catch (RuntimeException ignored) { }
        };
        if (!animate) {
            remove.run();
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        card.animate().alpha(0f).translationY(40 * d).setDuration(260).withEndAction(remove).start();
    }
}
