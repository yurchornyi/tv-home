package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The account screen (Launcher settings, Account): signed out, an e-mail and a PIN of four to six digits, and one
 * button that signs in or makes the account; signed in, the e-mail, Sync now, Install the account's apps this TV
 * lacks (one at a time: Google Play's page for each, KIK from its own release), and Sign out. Laid out as Launcher
 * settings: the icon and a line on the left, the fields on the right. RESULT_OK when Home must rebuild (it took in
 * another TV's Home).
 */
public final class AccountActivity extends Activity {

    private static final String KIK = "kik.app";

    private float density;
    private Typeface medium, regular, light;
    private LinearLayout list;
    private TextView hint;
    /** Which of the missing apps OK on Install opens next: each press goes on to the next one. */
    private int installAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        medium = getResources().getFont(R.font.outfit_medium);
        regular = getResources().getFont(R.font.outfit_regular);
        light = getResources().getFont(R.font.outfit_light);
        setContentView(build());
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Google Play: an app installed meanwhile is off the list.
        if (Account.signedIn(this)) show();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B1E24, 0xFF0C0D10, 0xFF050506}));
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(split, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

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
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_person);
        icon.setColorFilter(0xFFFFFFFF);
        tile.addView(icon, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        left.addView(tile, new LinearLayout.LayoutParams(dp(160), dp(160)));
        TextView title = text(getString(R.string.account), 27, medium, 0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.topMargin = dp(22);
        left.addView(title, tlp);
        hint = text("", 16, light, 0xB3FFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        left.addView(hint, hlp);
        split.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_VERTICAL);
        list.setPadding(dp(16), dp(64), dp(72), dp(56));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        split.addView(list, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f));
        return root;
    }

    /** Fills the right side for the state the account is in now. */
    private void show() {
        list.removeAllViews();
        if (!Account.available(this)) {
            hint.setText(R.string.account_unavailable);
            return;
        }
        if (Account.signedIn(this)) showSignedIn();
        else showSignedOut();
    }

    private void showSignedOut() {
        hint.setText(R.string.account_hint);
        EditText email = field(getString(R.string.account_email),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 120);
        email.setText(Account.email(this));
        EditText pin = field(getString(R.string.account_pin),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 6);
        TextView error = text("", 16, regular, 0xFFFF8A80);
        error.setPadding(dp(26), dp(10), 0, 0);
        View go = button(getString(R.string.account_sign_in), null);
        Runnable submit = () -> {
            String e = email.getText().toString().trim();
            String p = pin.getText().toString().trim();
            if (!e.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                error.setText(R.string.account_bad_email);
                email.requestFocus();
                return;
            }
            if (!p.matches("\\d{4,6}")) {
                error.setText(R.string.account_bad_pin);
                pin.requestFocus();
                return;
            }
            error.setText(R.string.account_working);
            go.setEnabled(false);
            Account.signIn(this, e, p, (ok, why) -> {
                go.setEnabled(true);
                if (isFinishing()) return;
                if (!ok) {
                    error.setText(message(why));
                    return;
                }
                setResult(RESULT_OK);   // Home rebuilds with what the account had
                Toast.makeText(this, getString(R.string.account_signed_in, Account.email(this)), Toast.LENGTH_LONG).show();
                show();
            });
        };
        go.setOnClickListener(v -> submit.run());
        email.setOnEditorActionListener((v, action, ev) -> {
            pin.requestFocus();
            return true;
        });
        pin.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO) {
                submit.run();
                return true;
            }
            return false;
        });
        pin.setImeOptions(EditorInfo.IME_ACTION_DONE);
        list.addView(email, row());
        list.addView(pin, row());
        list.addView(go, row());
        list.addView(error, wrap());
        (email.getText().length() > 0 ? pin : email).requestFocus();
    }

    private void showSignedIn() {
        List<Account.App> missing = Account.missing(this);
        hint.setText(missing.isEmpty() ? getString(R.string.account_synced_hint)
                : getString(R.string.account_missing_hint, missing.size()));
        TextView who = text(Account.email(this), 22, medium, 0xFFFFFFFF);
        who.setPadding(dp(26), 0, 0, dp(6));
        list.addView(who, wrap());
        TextView status = text("", 16, regular, 0x99FFFFFF);
        status.setPadding(dp(26), dp(8), 0, 0);
        View sync = button(getString(R.string.account_sync_now), null);
        sync.setOnClickListener(v -> {
            status.setText(R.string.account_working);
            Account.syncNow(this, (ok, why) -> {
                if (isFinishing()) return;
                if (ok) setResult(RESULT_OK);
                status.setText(ok ? getString(R.string.account_synced) : message(why));
                if (!Account.signedIn(this)) show();
            });
        });
        list.addView(sync, row());
        if (!missing.isEmpty()) {
            if (installAt >= missing.size()) installAt = 0;
            Account.App next = missing.get(installAt);
            View install = button(getString(R.string.account_install, missing.size()), next.label);
            install.setOnClickListener(v -> {
                installAt++;
                install(next);
            });
            list.addView(install, row());
        }
        if (Account.admin(this)) {
            View all = button(getString(R.string.account_admin), null);
            all.setOnClickListener(v -> showAdmin());
            list.addView(all, row());
        }
        View out = button(getString(R.string.account_sign_out), null);
        out.setOnClickListener(v -> Account.signOut(this, () -> {
            if (!isFinishing()) show();
        }));
        list.addView(out, row());
        list.addView(status, wrap());
        sync.requestFocus();
    }

    /** In the owner's list: Back goes to the account, not out of the screen. */
    private boolean inAdmin;

    @Override
    public void onBackPressed() {
        if (inAdmin) {
            inAdmin = false;
            show();
            return;
        }
        super.onBackPressed();
    }

    /**
     * The owner's list of every account: its e-mail, when it last synced and from which TV, how many apps. OK on one
     * opens Block (or Unblock) and Delete under it; Delete asks once more, then the account goes with its Home and
     * every TV's sign-in.
     */
    private void showAdmin() {
        inAdmin = true;
        list.removeAllViews();
        hint.setText(R.string.account_admin_hint);
        TextView status = text(getString(R.string.account_working), 16, regular, 0x99FFFFFF);
        status.setPadding(dp(26), 0, 0, 0);
        list.addView(status, wrap());
        Account.accounts(this, (accounts, why) -> {
            if (isFinishing() || !inAdmin) return;
            list.removeAllViews();
            if (why != null) {
                status.setText(message(why));
                list.addView(status, wrap());
                return;
            }
            TextView count = text(getString(R.string.account_admin_count, accounts.size()), 16, regular, 0x99FFFFFF);
            count.setPadding(dp(26), 0, 0, dp(4));
            list.addView(count, wrap());
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setClipToPadding(false);
            scroll.setPadding(0, dp(4), 0, dp(12));
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            // Room for a focused row to grow without its rounded ends cut: drawn into the padding, not clipped at it.
            rows.setClipChildren(false);
            rows.setClipToPadding(false);
            rows.setPadding(dp(12), 0, dp(12), 0);
            scroll.setClipChildren(false);
            scroll.addView(rows);
            java.text.DateFormat when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT);
            View first = null;
            // The owner's own account always heads the list; the others keep the server's order (latest sync first).
            String me = Account.email(this);
            List<Account.Entry> ordered = new java.util.ArrayList<>(accounts);
            java.util.Collections.sort(ordered, (x, y) -> Boolean.compare(!x.email.equalsIgnoreCase(me),
                    !y.email.equalsIgnoreCase(me)));
            for (Account.Entry a : ordered) {
                String detail = a.updated > 0
                        ? when.format(new java.util.Date(a.updated)) + (a.device.isEmpty() ? "" : " · " + a.device)
                                + " · " + getString(R.string.account_admin_apps, a.apps)
                        : getString(R.string.account_admin_never);
                boolean own = a.email.equalsIgnoreCase(Account.email(this));
                String shown = a.blocked ? getString(R.string.account_admin_blocked) + " · " + detail : detail;
                View r = accountRow(a.email, own ? getString(R.string.account_admin_you) : shown, a.blocked, own);
                LinearLayout.LayoutParams tall = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
                tall.topMargin = dp(10);
                rows.addView(r, tall);
                if (first == null) first = r;
                if (own) continue;
                // OK on an account opens its two actions under it; OK again closes them.
                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setVisibility(View.GONE);
                actions.setClipChildren(false);
                actions.setClipToPadding(false);
                actions.setPadding(0, 0, 0, 0);
                View lock = button(getString(a.blocked ? R.string.account_admin_unblock : R.string.account_admin_block), "");
                lock.setOnClickListener(v -> Account.block(this, a.email, !a.blocked, (ok, err) -> {
                    if (isFinishing() || !inAdmin) return;
                    if (ok) showAdmin();
                    else Toast.makeText(this, message(err), Toast.LENGTH_LONG).show();
                }));
                View remove = button(getString(R.string.account_admin_delete), "");
                boolean[] armed = {false};
                TextView removeLabel = (TextView) ((LinearLayout) remove).getChildAt(0);
                remove.setOnClickListener(v -> {
                    if (!armed[0]) {
                        armed[0] = true;
                        removeLabel.setText(R.string.account_admin_confirm);
                        remove.setBackground(danger());
                        removeLabel.setTextColor(0xFFFFFFFF);
                        return;
                    }
                    removeLabel.setText(R.string.account_working);
                    Account.delete(this, a.email, (ok, err) -> {
                        if (isFinishing() || !inAdmin) return;
                        if (ok) showAdmin();
                        else removeLabel.setText(message(err));
                    });
                });
                remove.setOnFocusChangeListener((v, focus) -> {
                    if (!armed[0]) removeLabel.setTextColor(focus ? 0xFFC62828 : 0xFFFF8A80);
                    v.animate().scaleX(focus ? 1.025f : 1f).scaleY(focus ? 1.025f : 1f).setDuration(220).start();
                    if (!focus && armed[0]) {   // moved away: the question is off again
                        armed[0] = false;
                        removeLabel.setText(R.string.account_admin_delete);
                        remove.setBackground(states());
                        removeLabel.setTextColor(0xFFFF8A80);
                    }
                });
                // Side by side under the account, the pair exactly as wide as its row: the left one starts at its left
                // edge, the right one ends at its right edge, a small gap between.
                removeLabel.setTextColor(0xFFFF8A80);
                for (View action : new View[]{lock, remove}) {
                    LinearLayout box = (LinearLayout) action;
                    box.getChildAt(1).setVisibility(View.GONE);   // no value, no chevron: the label sits centred
                    TextView label = (TextView) box.getChildAt(0);
                    label.setGravity(Gravity.CENTER);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                    box.setPadding(dp(12), 0, dp(12), 0);
                }
                LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(56), 1f);
                left.topMargin = dp(8);
                left.setMarginEnd(dp(6));
                LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(56), 1f);
                right.topMargin = dp(8);
                right.setMarginStart(dp(6));
                actions.addView(lock, left);
                actions.addView(remove, right);
                r.setOnClickListener(v -> actions.setVisibility(actions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
                rows.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            list.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if (first != null) first.requestFocus();
        });
    }

    /** An account in the owner's list: the e-mail, and under it, smaller, when and where it last synced. */
    /** The owner's own row: a gold edge and gold note, apart from red, which here means blocked or delete. */
    private static final int OWNER = 0xFFFFC94D;

    private View accountRow(String email, String detail, boolean blocked, boolean own) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(26), 0, dp(22), 0);
        b.setBackground(own ? ownerStates() : states());
        TextView l = text(email, 20, medium, blocked ? 0x99FFFFFF : 0xFFFFFFFF);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.addView(l, wrap());
        int rest = own ? OWNER : blocked ? 0xCCFF8A80 : 0x99FFFFFF;
        TextView v = text(detail, 15, own ? medium : regular, rest);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setPadding(0, dp(6), 0, 0);
        b.addView(v, wrap());
        b.setOnFocusChangeListener((view, focus) -> {
            l.setTextColor(focus ? 0xFF000000 : blocked ? 0x99FFFFFF : 0xFFFFFFFF);
            v.setTextColor(focus ? (own ? 0xFF8A6400 : 0x99000000) : rest);
            view.animate().scaleX(focus ? 1.02f : 1f).scaleY(focus ? 1.02f : 1f).setDuration(220).start();
        });
        return b;
    }

    /** One missing app: KIK from its own GitHub release, any other from its Google Play page. */
    private void install(Account.App app) {
        if (KIK.equals(app.pkg)) {
            Toast.makeText(this, getString(R.string.account_installing, app.label), Toast.LENGTH_SHORT).show();
            AppInstaller.install(this, getString(R.string.kik_update_url), started -> {
                if (!started) Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            });
            return;
        }
        for (Uri uri : new Uri[]{Uri.parse("market://details?id=" + app.pkg),
                Uri.parse("https://play.google.com/store/apps/details?id=" + app.pkg)}) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(this, getString(R.string.account_no_store, app.label), Toast.LENGTH_LONG).show();
    }

    private String message(String error) {
        if ("wrong pin".equals(error)) return getString(R.string.account_wrong_pin);
        if ("locked".equals(error) || "slow down".equals(error)) return getString(R.string.account_locked);
        if ("bad email".equals(error)) return getString(R.string.account_bad_email);
        if ("bad pin".equals(error)) return getString(R.string.account_bad_pin);
        if ("signed out".equals(error)) return getString(R.string.account_signed_out);
        if ("not admin".equals(error)) return getString(R.string.account_admin_denied);
        if ("blocked".equals(error)) return getString(R.string.account_blocked);
        return getString(R.string.account_offline);
    }

    private EditText field(String label, int type, int max) {
        EditText e = new EditText(this);
        e.setHint(label);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        e.setTypeface(medium);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0x66FFFFFF);
        e.setPadding(dp(26), 0, dp(22), 0);
        e.setBackground(states());
        e.setOnFocusChangeListener((v, focus) -> {
            e.setTextColor(focus ? 0xFF000000 : 0xFFFFFFFF);
            e.setHintTextColor(focus ? 0x66000000 : 0x66FFFFFF);
        });
        return e;
    }

    /** A row like Launcher settings' ones: a label, and a value at the end (or none). */
    private View button(String label, String value) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(26), 0, dp(22), 0);
        b.setBackground(states());
        TextView l = text(label, 21, medium, 0xFFFFFFFF);
        l.setSingleLine(true);
        b.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value == null ? "›" : value + "  ›", 19, regular, 0x99FFFFFF);
        v.setSingleLine(true);
        b.addView(v);
        b.setOnFocusChangeListener((view, focus) -> {
            l.setTextColor(focus ? 0xFF000000 : 0xFFFFFFFF);
            v.setTextColor(focus ? 0x99000000 : 0x99FFFFFF);
            view.animate().scaleX(focus ? 1.025f : 1f).scaleY(focus ? 1.025f : 1f).setDuration(220).start();
        });
        return b;
    }

    /** Like states(), with a gold edge whether focused or not. */
    private StateListDrawable ownerStates() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(16));
        on.setColor(0xF5FFFFFF);
        on.setStroke(dp(2), OWNER);
        GradientDrawable off = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFC94D, 0x12FFC94D});
        off.setCornerRadius(dp(16));
        off.setStroke(dp(2), OWNER);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    /** Delete, asked once more: a red button with white text, so the question cannot be missed. */
    private GradientDrawable danger() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        d.setColor(0xFFD32F2F);
        return d;
    }

    private StateListDrawable states() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(16));
        on.setColor(0xF5FFFFFF);
        GradientDrawable off = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1FFFFFFF, 0x0FFFFFFF});
        off.setCornerRadius(dp(16));
        off.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
        lp.topMargin = dp(10);
        return lp;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
}
