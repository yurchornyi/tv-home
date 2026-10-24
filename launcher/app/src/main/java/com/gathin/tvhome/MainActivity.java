package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** What Launcher settings asked Home to do once it is back; static, so it outlives a recreate(). */
    private static String settingsThen;
    /** The grid's top padding (the dock's place) has been set. */
    private boolean gridPlaced;
    /** Home has drawn its first frame; until then focus moves place rows at once instead of scrolling to them. */
    private boolean firstFrameShown;
    /** recreate() is under way: this instance is about to be replaced. */
    private boolean recreating;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** Top shelf art and the fade over it (top_shelf_art). */
    private ImageView heroArt;
    private View heroFade;
    private boolean topShelfArt;
    /** The drawable the shelf shows now, to skip a crossfade to the same app. */
    private Drawable heroArtShown;
    /** TMDB backdrops for shelf_feed_apps: the app whose carousel runs, its list and the backdrop shown. */
    private ShelfFeed shelfFeed;
    /** The AI features through the Worker (AiClient): spoken commands and picks. */
    private AiClient ai;
    /** Kick's live streams for its shelf (KickFeed). */
    private KickFeed kickFeed;
    /** A line under the search row: "thinking", why a pick fits, or what the AI could not do. */
    private TextView searchNote;
    private final Set<String> feedApps = new HashSet<>();
    /** Apps whose own home-screen channel programs fill the shelf (shelf_channel_apps), read by channelFeed. */
    private final Set<String> channelApps = new HashSet<>();
    private ChannelFeed channelFeed;
    private String feedPkg;
    /** Android's package change counter at the last tile rebuild, and when that was (appsMayHaveChanged). */
    private int packageSeq;
    private long tilesBuiltAt;
    /** Bumped whenever the carousel stops, so backdrops still loading for an earlier visit are dropped. */
    private int feedGen;
    private List<ShelfFeed.Item> feedItems;
    private int feedIndex;
    /** The channel whose programmes the shelf's posters show now (a focused channel tile), null for the app's own. */
    private String feedChannel;
    private float lastDockTop;
    private TextView shelfTitle;
    /** The Watch pill under the shelf title: opens the film or video shown (openFeedItem). */
    private TextView shelfOpen;
    private TextView shelfLive;
    /** The channel row (LiveChannels): its scroller, the tiles, and the app whose channels it shows, or null. */
    private android.widget.HorizontalScrollView shelfChannelsScroll;
    private LinearLayout shelfChannels;
    private LiveChannels liveChannels;
    private Updater updater;
    /** What was opened last (Recents), for the Continue row on the top shelf and the Search pill. */
    private Recents recents;
    /** Search in the top bar: the pill widens into this field; the matches are tiles in the shelf's row (searching). */
    private android.widget.EditText searchField;
    private boolean searching;
    private int searchPillWidth;
    private List<SearchIndex.Result> searchAll;
    private View btnSearch;
    /** A voice query waiting for Home to be resumed, then searched. */
    private String pendingSearch;
    /** The poster put first on an app's shelf: the film or video opened last there (Recents); its pill says Continue. */
    private ShelfFeed.Item continueItem;
    private static final int VOICE_REQUEST = 43;
    private static final int RECORD_AUDIO_REQUEST = 44;
    private static final int TV_LISTINGS_REQUEST = 45;
    /** Spoken search in the field itself (VoiceSearch); listenOnOpen starts it once the field has unfolded. */
    private VoiceSearch voice;
    private boolean listenOnOpen;
    /** The full-screen Now Playing (ambient screen), opened from the card's expand button. */
    private View ambientScreen;
    private AmbientView ambientBg;
    private ImageView ambientCover;
    private TextView ambientTitle, ambientArtist;
    private android.widget.ProgressBar ambientProgress;
    private TextView ambientPlay;
    /** The player's button row; it rests out of sight after a few seconds without a key (ambientRest). */
    private View ambientControls;
    private boolean ambientControlsShown = true;
    private final Runnable ambientRest = this::restAmbientControls;
    private final Runnable ambientGone = this::hideAmbientIfSilent;
    /** The cover's slow turn in space while the full-screen player shows. */
    private android.animation.ValueAnimator ambientTurn;
    private Bitmap ambientCoverShown;
    private String channelsPkg;
    /** Apps whose shelf posters the owner switched off (tile menu), kept in preferences. */
    private final Set<String> postersOff = new HashSet<>();
    /** Now Playing card (NowPlaying), shown on the shelf instead of posters while something plays. */
    private NowPlaying nowPlaying;
    private View npCard;
    private ImageView npCover;
    private TextView npTitle, npArtist;
    private android.widget.ProgressBar npProgress;
    private TextView npPlay;
    private View[] npButtons;
    private Bitmap npCoverShown;
    private Drawable npBackdrop;
    private int lastHeroPosition = -1;
    private final Runnable npTick = new Runnable() {
        @Override public void run() {
            updateNowPlayingProgress();
            if (ambientScreen.getVisibility() == View.VISIBLE) {
                NowPlaying.Track a = nowPlaying.track();
                ambientProgress.setVisibility(a != null && a.duration > 0 ? View.VISIBLE : View.INVISIBLE);
                TextView elapsed = findViewById(R.id.ambientElapsed), remaining = findViewById(R.id.ambientRemaining);
                if (a != null && a.duration > 0) {
                    long pos = Math.max(0, Math.min(a.duration, nowPlaying.position()));
                    ambientProgress.setProgress((int) Math.min(1000, pos * 1000 / a.duration));
                    elapsed.setText(clock(pos));
                    remaining.setText("-" + clock(a.duration - pos));
                }
                elapsed.setVisibility(a != null && a.duration > 0 ? View.VISIBLE : View.INVISIBLE);
                remaining.setVisibility(elapsed.getVisibility());
            }
            // Paused, the bar stands still: no need to ask the player every second.
            NowPlaying.Track t = nowPlaying.track();
            if (t != null && t.playing) handler.postDelayed(this, 1000);
        }
    };
    /** The ease Apple TV moves things with: a quick start that settles very softly. */
    private static final android.animation.TimeInterpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * A song title in the room it has (the view's maxWidth): at full size when it fits, a little smaller when that
     * is enough, and otherwise slowly scrolling along, as Apple TV shows a long title, never cut at the screen edge.
     */
    private void fitTitle(TextView v, float maxSp, float minSp) {
        int room = v.getMaxWidth();
        if (room <= 0 || room == Integer.MAX_VALUE) return;
        android.text.TextPaint p = new android.text.TextPaint(v.getPaint());
        String text = String.valueOf(v.getText());
        float sp = maxSp;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        while (sp > minSp) {
            p.setTextSize(sp * scaled);
            if (p.measureText(text) <= room) break;
            sp -= 1f;
        }
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        p.setTextSize(sp * scaled);
        boolean scroll = p.measureText(text) > room;
        v.setSingleLine(true);
        v.setHorizontalFadingEdgeEnabled(scroll);
        v.setFadingEdgeLength((int) (24 * density));
        v.setEllipsize(scroll ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        v.setMarqueeRepeatLimit(-1);
        v.setSelected(scroll);   // a marquee runs only on a selected view
    }

    /**
     * KIK, the owner's own film app, belongs third on every Home (config.xml dock_apps): on a TV without it, Home
     * offers once to install it from its GitHub release (config.xml kik_update_url); Android asks to confirm.
     */
    private final Runnable kikOffer = this::offerKik;

    private void offerKik() {
        String url = getString(R.string.kik_update_url);
        android.content.SharedPreferences st = getSharedPreferences("setup", MODE_PRIVATE);
        if (url.isEmpty() || installed("kik.app") || st.getBoolean("asked_kik", false)) return;
        if (isFinishing() || !hasWindowFocus() || openPopup != null || searching || !missingPermissions().isEmpty()) {
            return;   // another time Home comes up
        }
        st.edit().putBoolean("asked_kik", true).apply();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        TextView title = new TextView(this);
        title.setText(R.string.kik_offer);
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(getResources().getFont(R.font.outfit_medium));
        title.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        rows.addView(title);
        PopupWindow popup = glassPopup(panel, (int) (380 * density));
        addSettingsMenuRow(rows, popup, R.string.kik_install, () -> {
            Toast.makeText(this, R.string.kik_downloading, Toast.LENGTH_SHORT).show();
            AppInstaller.install(this, url, started -> {
                if (!started) Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
            });
        });
        addSettingsMenuRow(rows, popup, R.string.kik_not_now, () -> { });
        panel.setAlpha(0f);
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start();
        rows.getChildAt(1).requestFocus();
    }

    /** Between onResume and onPause: the shelf draws only then (showHero). */
    private boolean homeInFront;
    /** The full-screen player is fading out after Back: keys and a new open must not treat it as up. */
    private boolean ambientHiding;
    /** The Now Playing card is drawing back after the music stopped: Up must not go to its buttons. */
    private boolean npHiding;

    /** When a key last reached Home: an update waits until the remote has rested a minute. */
    private long lastKeyAt;

    /**
     * The screen went off (the TV going to sleep): the best moment for a waiting update that installs without asking,
     * nobody is watching; with none waiting yet, it looks for one to install next time. The screen coming on looks too, so a TV that is on for days
     * with an app in front still hears of a new build.
     */
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (updater == null) return;
            // Where Android asks first, its question would come up on a dark screen: Home offers it instead.
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || updater.readyVersion() == null
                    || !updater.silent()) {
                updater.check(true);
            } else {
                android.util.Log.i("TvHome", "screen off: installing " + updater.readyVersion());
                updater.install();
            }
        }
    };

    private final Runnable autoUpdate = new Runnable() {
        @Override public void run() {
            if (updater == null || updater.readyVersion() == null || isFinishing() || isDestroyed()) return;
            NowPlaying.Track t = nowPlaying.track();
            boolean idle = hasWindowFocus() && openPopup == null && !searching
                    && ambientScreen.getVisibility() != View.VISIBLE
                    && SystemClock.uptimeMillis() - lastKeyAt > 60_000
                    && (t == null || !t.playing);
            if (idle) {
                // Home restarts for a moment as the new build takes over; with nobody at the remote it goes unseen.
                android.util.Log.i("TvHome", "idle Home: installing " + updater.readyVersion());
                updater.install();
            } else {
                handler.postDelayed(this, 30_000);
            }
        }
    };

    /**
     * A build is downloaded: where Android installs it without asking, Home does so once nobody is at the remote
     * (autoUpdate); elsewhere it offers it over Home, as KIK does (offerUpdate).
     */
    private void onUpdateReady() {
        if (isDestroyed()) return;   // a download that finished after a recreate() reports to the old instance
        handler.removeCallbacks(autoUpdate);
        handler.removeCallbacks(updateOffer);
        if (updater.silent()) handler.postDelayed(autoUpdate, 30_000);
        else handler.postDelayed(updateOffer, 6000);
    }

    /** When Home last offered an update: "Later" holds the next offer off for half an hour. */
    private long updateOfferedAt;
    private final Runnable updateOffer = this::offerUpdate;

    private void offerUpdate() {
        String version = updater == null ? null : updater.readyVersion();
        if (version == null || isFinishing() || isDestroyed() || !homeInFront) return;
        if (updateOfferedAt != 0 && SystemClock.elapsedRealtime() - updateOfferedAt < 30 * 60_000L) return;
        if (!hasWindowFocus() || openPopup != null || searching || ambientScreen.getVisibility() == View.VISIBLE) {
            handler.postDelayed(updateOffer, 30_000);   // after the menu or the player
            return;
        }
        updateOfferedAt = SystemClock.elapsedRealtime();
        android.util.Log.i("TvHome", "offering update " + version);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        TextView title = new TextView(this);
        title.setText(getString(R.string.update_offer, version));
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(getResources().getFont(R.font.outfit_medium));
        title.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        rows.addView(title);
        PopupWindow popup = glassPopup(panel, (int) (380 * density));
        addSettingsMenuRow(rows, popup, R.string.update_now, updater::install);
        addSettingsMenuRow(rows, popup, R.string.update_later, () -> { });   // the Settings menu keeps "Update to"
        panel.setAlpha(0f);
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start();
        rows.getChildAt(1).requestFocus();
    }

    /** m:ss, or h:mm:ss past an hour. */
    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private final Runnable feedTick = new Runnable() {
        @Override public void run() {
            if (feedItems == null || feedItems.isEmpty()) return;
            feedIndex = (feedIndex + 1) % feedItems.size();
            showFeedItem();
        }
    };
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View btnInputs;
    /** Inputs pill allowed at all (config.xml show_inputs); it still hides when there is nothing to list. */
    private boolean showInputs;
    /** wifi_icon_only: the network pill shows its icon alone, like the Settings button. */
    private boolean wifiIconOnly;
    /** The VPN's exit country (ISO code, e.g. "DE") from Cloudflare's trace, "" until known; refreshed per minute. */
    private String vpnCountry = "";
    private long vpnCountryAt;
    private boolean vpnCountryBusy;
    private final AudioDevices.Listener audioListener = this::updateAudioButton;
    private TextView btnAudio;
    private View btnProfile;
    private TextView topTemp;
    private Weather weather;
    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            refreshWeather();
            handler.postDelayed(this, Weather.MAX_AGE_MS);
        }
    };
    private TextView btnWifi;
    private TextView shamsiDate;
    private TextView stats;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** A tile was picked up by holding OK: the release of that same OK must not put it straight down again. */
    private boolean swallowOkUp;
    /** Where the carried tile was picked up, so Back can put it back there. */
    private int pickedFrom = -1;
    /** The move was started from a tile's menu: one tile, and the mode ends as soon as it is placed or cancelled. */
    private boolean singleMove;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    private AppLibraryDialog appLibrary;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // Play updates many apps in a row: rebuild once when the burst is over, not once per app.
            handler.removeCallbacks(packagesChanged);
            handler.postDelayed(packagesChanged, 600);
        }
    };
    private final Runnable packagesChanged = () -> {
        reloadApps(false);
        if (appLibrary != null) appLibrary.refresh();
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // The root is opaque black already: without this the window's own black is one more full-screen pass a frame.
        getWindow().setBackgroundDrawable(null);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Backup.importIfChanged(this);   // a backup.json put in the files folder (restore) before anything reads
        Backup.applyDefaultsIfFresh(this);   // a new TV starts from the owner's usual shelf and channel choices
        SelfSetup.ensure(this);   // the sleep timer and Now Playing stay switched on, update after update
        Config cfg = Config.load(this);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        heroArt = findViewById(R.id.heroArt);
        // The glow lies under the shelf art. While that art is up in full and opaque (a poster, a softened banner)
        // it cannot be seen, yet drawing it still filled the whole screen once more on every frame, a quarter of
        // the shelf's GPU work on a slow TV. Checked right before each frame, since the art fades in and out from
        // many places; the glow is only skipped, its own alpha and fades carry on.
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            Drawable art = heroArt.getDrawable();
            boolean covered = art != null && heroArt.getAlpha() >= 1f && heroArt.getVisibility() == View.VISIBLE
                    && art.getOpacity() == android.graphics.PixelFormat.OPAQUE;
            int want = covered ? View.INVISIBLE : View.VISIBLE;
            if (ambient.getVisibility() != want) ambient.setVisibility(want);
            return true;
        });
        // dock_title false: the name above the dock never shows; the glow still follows the dock.
        if (!cfg.dockTitle) heroName.setVisibility(View.GONE);
        heroFade = findViewById(R.id.heroFade);
        topShelfArt = cfg.topShelfArt;
        shelfTitle = findViewById(R.id.shelfTitle);
        searchNote = findViewById(R.id.searchNote);
        shelfOpen = findViewById(R.id.shelfOpen);
        shelfLive = findViewById(R.id.shelfLive);
        shelfChannelsScroll = findViewById(R.id.shelfChannelsScroll);
        shelfChannels = findViewById(R.id.shelfChannels);
        liveChannels = new LiveChannels(this);
        recents = new Recents(this);
        voice = new VoiceSearch(this);
        ambientScreen = findViewById(R.id.ambientScreen);
        ambientBg = findViewById(R.id.ambientBg);
        ambientCover = findViewById(R.id.ambientCover);
        ambientTitle = findViewById(R.id.ambientTitle);
        ambientArtist = findViewById(R.id.ambientArtist);
        ambientProgress = findViewById(R.id.ambientProgress);
        ambientPlay = findViewById(R.id.ambientPlay);
        ambientPlay.setOnClickListener(v -> nowPlaying.playPause());
        findViewById(R.id.ambientPrev).setOnClickListener(v -> nowPlaying.previous());
        findViewById(R.id.ambientNext).setOnClickListener(v -> nowPlaying.next());
        findViewById(R.id.ambientOpen).setOnClickListener(v -> {
            if (!nowPlaying.openPlayer()) {
                NowPlaying.Track t = nowPlaying.track();
                if (t != null) launchApp(t.pkg);
            }
        });
        findViewById(R.id.ambientClose).setOnClickListener(v -> {
            hideAmbient();
            nowPlaying.close();
        });
        ambientControls = findViewById(R.id.ambientControls);
        View.OnKeyListener ambientKeys = (v, keyCode, event) -> {
            // The row's ends: Left on Previous and Right on Close stay put; Up and Down stay on the row.
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.ambientPrev;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.ambientClose;
        };
        for (int id : new int[]{R.id.ambientPrev, R.id.ambientPlay, R.id.ambientNext, R.id.ambientOpen, R.id.ambientClose}) {
            findViewById(id).setOnKeyListener(ambientKeys);
        }
        ambientCover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 18 * density);
            }
        });
        ambientCover.setClipToOutline(true);
        ambientCover.setCameraDistance(9000 * density);
        findViewById(R.id.npExpand).setOnClickListener(v -> showAmbient());
        // A downloaded build installs itself or is offered over Home (onUpdateReady); UpdateJob looks in the background.
        updater = new Updater(this, cfg.updateUrl, version -> onUpdateReady());
        UpdateJob.schedule(this, cfg.updateUrl);
        IntentFilter screenOff = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenOff.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(screenOffReceiver, screenOff, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOffReceiver, screenOff);
        updater.check(true);   // the launcher starting (after a reboot, say) looks at once
        postersOff.addAll(getSharedPreferences("shelf", MODE_PRIVATE).getStringSet("posters_off", Collections.emptySet()));
        shelfOpen.setOnClickListener(v -> openFeedItem());
        shelfOpen.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_BACK) {
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                firstPill().requestFocus();
                return true;
            }
            // Left and Right turn the pages while the carousel waits.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (feedItems != null && !feedItems.isEmpty()) {
                    int n = feedItems.size();
                    feedIndex = ((feedIndex + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)) % n + n) % n;
                    showFeedItem();
                }
                return true;
            }
            return false;
        });
        shelfOpen.setOnFocusChangeListener((v, focused) -> {
            // The music Play disc grows a little under focus, as the player's buttons do.
            if (v.getTag() == Boolean.TRUE) v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(180).start();
            else if (v.getScaleX() != 1f) v.setScaleX(1f);
            if (v.getTag() != Boolean.TRUE) v.setScaleY(1f);
            // Choosing: the carousel stands still, the dock's highlight steps aside; it moves on again after.
            if (focused) {
                hideGlass();
                handler.removeCallbacks(feedTick);
            } else if (feedPkg != null) {
                handler.removeCallbacks(feedTick);
                handler.postDelayed(feedTick, 8000);
            }
        });
        npCard = findViewById(R.id.nowPlaying);
        npCover = findViewById(R.id.npCover);
        npCover.setClipToOutline(true);
        npTitle = findViewById(R.id.npTitle);
        npArtist = findViewById(R.id.npArtist);
        npProgress = findViewById(R.id.npProgress);
        nowPlaying = new NowPlaying(this, this::onNowPlayingChanged);
        npPlay = findViewById(R.id.npPlay);
        npButtons = new View[]{findViewById(R.id.npPrev), npPlay, findViewById(R.id.npNext), findViewById(R.id.npExpand),
                findViewById(R.id.npOpen), findViewById(R.id.npClose)};
        findViewById(R.id.npOpen).setOnClickListener(v -> {
            NowPlaying.Track t = nowPlaying.track();
            if (!nowPlaying.openPlayer() && t != null) launchApp(t.pkg);
        });
        findViewById(R.id.npPrev).setOnClickListener(v -> nowPlaying.previous());
        npPlay.setOnClickListener(v -> nowPlaying.playPause());
        findViewById(R.id.npNext).setOnClickListener(v -> nowPlaying.next());
        findViewById(R.id.npClose).setOnClickListener(v -> nowPlaying.close());
        // The row is a dead end at both sides; Down goes back to the dock tile Up came from.
        View.OnKeyListener npKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v == npButtons[0];
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return v == npButtons[npButtons.length - 1];
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                findViewById(R.id.btnSettings).requestFocus();
                return true;
            }
            return false;
        };
        for (View b : npButtons) b.setOnKeyListener(npKeys);
        feedApps.addAll(cfg.shelfFeedApps);
        channelApps.addAll(cfg.shelfChannelApps);
        shelfFeed = new ShelfFeed(this, BuildConfig.TMDB_KEY, getString(R.string.tmdb_proxy));
        ai = new AiClient(this, getString(R.string.tmdb_proxy));
        kickFeed = new KickFeed(getString(R.string.tmdb_proxy));
        channelFeed = new ChannelFeed(this);
        if (!cfg.showClock) findViewById(R.id.leftBlock).setVisibility(View.INVISIBLE);
        greeting = findViewById(R.id.greeting);
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        adapter.setSavedOrder(Order.ranks(Order.load(Order.file(this)), dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? heroName.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                // The first time: the rows go straight to their place. RecyclerView otherwise keeps the tiles where
                // the old padding had them and slides them down, which looked like Home coming up wrong.
                if (!gridPlaced) grid.scrollToPosition(0);
            }
            // A layout asked for during layout runs again before anything draws, so the next frame has the rows in
            // place: from here the first frame may draw (firstFrame).
            gridPlaced = true;
        });

        // The first frame waits for the tiles: their row's place (the padding above) is only known after a first
        // layout, so without this Home showed the clock alone, then the tiles too high, then moved them down.
        android.view.ViewTreeObserver.OnPreDrawListener firstFrame = new android.view.ViewTreeObserver.OnPreDrawListener() {
            private final long since = SystemClock.uptimeMillis();
            @Override public boolean onPreDraw() {
                boolean ready = gridPlaced && tilesReady && adapter != null && adapter.getItemCount() > 0
                        && grid.getChildCount() > 0 && !grid.isLayoutRequested() && !root.isLayoutRequested();
                if (ready || SystemClock.uptimeMillis() - since > 1500) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    firstFrameShown = true;
                    return true;
                }
                return false;
            }
        };
        root.getViewTreeObserver().addOnPreDrawListener(firstFrame);

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                // Quick and almost settled, like the focus moving on Apple TV: no wobble after it lands.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 260f)
                        .setDampingRatio(advancedEffects ? 0.86f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnSearch = findViewById(R.id.btnSearch);
        searchField = findViewById(R.id.searchField);
        View searchMic = findViewById(R.id.searchMic);
        searchMic.setOnClickListener(v -> {
            if (voice.listening()) stopListening(false);
            else startVoice();
        });
        searchMic.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { searchField.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) return focusSearchResult();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { closeSearch(true); return true; }
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        // OK on Search starts with the microphone; a word not heard (or Back) leaves the field open for typing.
        btnSearch.setOnClickListener(v -> startVoice());
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) { if (searching) showSearchResults(e.toString()); }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (focusSearchResult()) return true;
                String typed = searchField.getText().toString().trim();
                if (!typed.isEmpty() && ai.enabled()) {
                    runAiCommand(typed);
                    return true;
                }
                return false;
            }
            return false;
        });
        searchField.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) return focusSearchResult();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { closeSearch(true); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
            // Left at the start of the text would wander off to the clock; Right at its end goes to the microphone.
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return searchField.getSelectionStart() == 0;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && searchField.getSelectionEnd() == searchField.length()) {
                findViewById(R.id.searchMic).requestFocus();
                return true;
            }
            return false;
        });
        searchField.setOnLongClickListener(v -> {
            startVoice();
            return true;
        });
        btnSearch.setOnLongClickListener(v -> {
            openSearch("");   // straight to typing
            return true;
        });
        showInputs = cfg.showInputs;
        if (!showInputs) btnInputs.setVisibility(View.GONE);
        if (!cfg.showWifi) btnWifi.setVisibility(View.GONE);
        wifiIconOnly = cfg.wifiIconOnly;
        if (wifiIconOnly) {
            // The same round 46dp button as Settings (TopIconButton); it widens into a capsule for the VPN badge.
            LinearLayout.LayoutParams wlp = (LinearLayout.LayoutParams) btnWifi.getLayoutParams();
            wlp.width = (int) (46 * density);
            wlp.height = (int) (46 * density);
            wlp.setMarginStart((int) (14 * density));
            wlp.gravity = Gravity.CENTER_VERTICAL;
            btnWifi.setLayoutParams(wlp);
            btnWifi.setBackgroundResource(R.drawable.icon_button_bg);
            btnWifi.setPaddingRelative((int) (12 * density), 0, (int) (12 * density), 0);
            btnWifi.setCompoundDrawablePadding(0);
            btnWifi.setMaxWidth(Integer.MAX_VALUE);
        }
        if (cfg.topClock || cfg.weather) {
            findViewById(R.id.topInfo).setVisibility(View.VISIBLE);
            if (!cfg.topClock) findViewById(R.id.topClock).setVisibility(View.GONE);
        }
        topTemp = findViewById(R.id.topTemp);
        if (cfg.weather) {
            weather = new Weather(this);
            showWeather(weather.cached());
        }
        btnAudio = findViewById(R.id.btnAudio);
        btnAudio.setOnClickListener(v -> openAccessories());
        btnProfile = findViewById(R.id.btnProfile);
        if (cfg.profileButton) {
            btnProfile.setVisibility(View.VISIBLE);
            btnProfile.setOnClickListener(v -> open(systemIntent("android.settings.USER_SETTINGS")));
            loadProfilePhoto();
        }
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            // Left and Right go to the next button of the bar on screen, by hand: the focus finder can pick a
            // channel tile below instead (it is nearer than the next pill), and at the bar's ends it would jump down.
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            List<View> bar = new ArrayList<>();
            for (View b : new View[]{btnSearch, btnInputs, btnAudio, btnWifi, btnReorder, btnSettings, btnProfile}) {
                if (b.getVisibility() == View.VISIBLE && b.isFocusable() && b.isShown()) bar.add(b);
            }
            int[] at = new int[2];
            Map<View, Integer> left = new HashMap<>();
            for (View b : bar) {
                b.getLocationOnScreen(at);
                left.put(b, at[0]);
            }
            Collections.sort(bar, (a, b) -> Integer.compare(left.get(a), left.get(b)));
            int next = bar.indexOf(v) + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (next >= 0 && next < bar.size()) bar.get(next).requestFocus();
            return true;
        };
        btnSearch.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnProfile.setOnKeyListener(pillKeys);
        btnAudio.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnSearch, btnInputs, btnAudio, btnWifi, btnReorder, btnSettings, btnProfile}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null) openLauncherSettingsIfAsked(getIntent());
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        if (recreating) {
            // recreate() was asked for in onActivityResult; this instance still gets onResume before it goes. The new
            // one does the work (and runs settingsThen): doing it here too reloaded the tiles twice and lost the action.
            super.onResume();
            return;
        }
        if (settingsThen != null) {
            String then = settingsThen;
            settingsThen = null;
            handler.postDelayed(() -> runSettingsAction(then), 350);
        }
        super.onResume();
        homeInFront = true;
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        promptsPaused = false;
        updater.check();
        // Signed in to an account: another TV's newer Home comes in, and this one rebuilds with it.
        Account.refresh(this, applied -> {
            if (applied && homeInFront && !isFinishing() && !recreating) {
                recreating = true;
                recreate();
            }
        });
        if (updater.readyVersion() != null) onUpdateReady();
        askForTvListings();
        handler.removeCallbacks(kikOffer);
        handler.postDelayed(kikOffer, 4000);
        if (pendingSearch != null) {
            String q = pendingSearch;
            pendingSearch = null;
            handler.post(() -> {
                if (!hasWindowFocus() || isFinishing()) return;
                openSearch(q);
                if (!q.isEmpty() && SearchIndex.filter(searchAll, q).isEmpty()) openInKik(q);
            });
        }
        nowPlaying.start();
        nowPlaying.stopPaused();   // a film or song left paused must not wake on the headphones' Play
        AudioDevices.addListener(audioListener);
        AudioDevices.refresh(this);
        updateAudioButton();
        if (weather != null) {
            handler.removeCallbacks(weatherTick);
            handler.post(weatherTick);
        }
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true, appsMayHaveChanged());
        } else {
            // Back from a dialog over Home (not another app): the shelf that onPause cleared comes back as it was.
            grid.post(() -> {
                View f = getCurrentFocus();
                if (homeInFront && f != null && f.getId() == R.id.card && lastFocused >= 0 && lastFocused < COLUMNS) {
                    showHero(lastFocused, lastDockTop);
                }
            });
        }
        rebuildOnResume = false;
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        if (showStats) startStats();
        sky.update();
        wallpaperPlayer.start();
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST) {
            // A row that acts on Home itself (add an app, reorder, module cards) closes Launcher settings and runs
            // here; when a setting changed too, Home is rebuilt first and runs it once it is back (onResume).
            String then = data == null ? null : data.getStringExtra(LauncherSettingsActivity.EXTRA_THEN);
            boolean changed = resultCode == RESULT_OK
                    || (data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_CHANGED, false));
            if (then != null) settingsThen = then;
            if (changed) {
                recreating = true;
                recreate();
            }
        }
        if (requestCode == VOICE_REQUEST) {
            java.util.ArrayList<String> heard = data == null ? null
                    : data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            pendingSearch = resultCode == RESULT_OK && heard != null && !heard.isEmpty() ? heard.get(0) : "";
        }
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /**
     * Back on Home from an app: false when no package was installed, updated or removed meanwhile, so the tiles stay
     * as they are and only focus and the entrance are redone (a full rebuild reads every app's label and banner, a
     * visible hitch on a slow TV). Every quarter of an hour a full rebuild happens anyway, for changes Android's
     * sequence number may not count (an app disabled from adb).
     */
    private boolean appsMayHaveChanged() {
        if (SystemClock.elapsedRealtime() - tilesBuiltAt > 15 * 60_000L) return true;
        try {
            return getPackageManager().getChangedPackages(packageSeq) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** rebuild false: keep the tiles, redo focus and the entrance only (see appsMayHaveChanged). */
    private void reloadApps(boolean animate, boolean rebuild) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
            hideShelfArt(false);
        }
        if (rebuild) reloadTiles();
        // The tiles rise in once, when Home starts. Coming back from an app they are simply there, as on Apple TV.
        if (animate && animations && !reducedMotion && !enteredOnce) grid.scheduleLayoutAnimation();
        enteredOnce = true;

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        try {
            android.content.pm.ChangedPackages changed = getPackageManager().getChangedPackages(packageSeq);
            if (changed != null) packageSeq = changed.getSequenceNumber();
        } catch (RuntimeException ignored) {
        }
        tilesBuiltAt = SystemClock.elapsedRealtime();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        long t0 = SystemClock.uptimeMillis();
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        android.util.Log.i("TvHome", "tiles built in " + (SystemClock.uptimeMillis() - t0) + " ms");
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        homeInFront = false;
        endReorder();   // Home or another app while reordering keeps the order so far
        Backup.exportLater(this);
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroFade.animate().cancel();
        stopFeed();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(weatherTick);
        hideAmbient();
        AudioDevices.removeListener(audioListener);
        nowPlaying.stop();
        hideNowPlaying(false);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        closeSearch(false);
        voice.stop();
        handler.removeCallbacks(autoUpdate);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(packagesChanged);
        rebuildOnResume = true;
        // Another app is in front: let go of the big pictures (a backdrop is ~2 MB); they reload when Home returns.
        heroArt.animate().cancel();
        heroArt.setImageDrawable(null);
        heroArt.setAlpha(0f);
        heroFade.setAlpha(0f);
        heroArtShown = null;
        npCoverShown = null;
        npBackdrop = null;
        if (shelfFeed != null) shelfFeed.trimMemory();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (shelfFeed != null) shelfFeed.close();
        if (ai != null) ai.close();
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    /** The full-screen player's next (or previous) shown button gets focus; the row's ends stay where they are. */
    private void stepAmbientControls(boolean forward) {
        ViewGroup row = findViewById(R.id.ambientControls);
        List<View> buttons = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View b = row.getChildAt(i);
            if (b.getVisibility() == View.VISIBLE && b.isFocusable()) buttons.add(b);
        }
        if (row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) forward = !forward;
        int at = buttons.indexOf(row.getFocusedChild());
        int next = at < 0 ? buttons.indexOf(ambientPlay) : at + (forward ? 1 : -1);
        if (next >= 0 && next < buttons.size()) buttons.get(next).requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastKeyAt = SystemClock.uptimeMillis();
        if (ambientScreen.getVisibility() == View.VISIBLE && !ambientHiding) {
            // The full-screen player: its buttons take the arrows and OK, the media keys work from anywhere, Back
            // (or Home) brings Home back. The buttons rest out of sight after a few seconds; the first key that
            // comes then only brings them back.
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            boolean wereHidden = !ambientControlsShown;
            if (down) wakeAmbientControls();
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (down) nowPlaying.playPause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (down) nowPlaying.next();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (down) nowPlaying.previous();
                    return true;
                case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                    if (!down) hideAmbient();
                    return true;
                default:
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (wereHidden) return true;   // this press only woke the buttons
                        if (!ambientScreen.hasFocus()) ambientPlay.requestFocus();
                        int code = event.getKeyCode();
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            // Along the row by hand: the focus finder stopped at Next, the gap before Open and Close
                            // too wide for it beside the larger Play button.
                            if (down) stepAmbientControls(code == KeyEvent.KEYCODE_DPAD_RIGHT);
                            return true;
                        }
                        return super.dispatchKeyEvent(event);
                    }
                    return true;
            }
        }
        if (handleSettingsKey(event)) return true;
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_SEARCH || key == KeyEvent.KEYCODE_VOICE_ASSIST || key == KeyEvent.KEYCODE_ASSIST) {
            // The remote's microphone: say what to open.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) startVoice();
            return true;
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (swallowOkUp && ok) {
            // The held OK that picked the tile up: eat its repeats and release, and clear the pressed look.
            if (!down) {
                swallowOkUp = false;
                View f = getCurrentFocus();
                if (f != null) f.setPressed(false);
            }
            return true;
        }
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) {
                            // OK places the tile here; Back cancels the move and returns it to where it was.
                            if (back) cancelMove();
                            else drop();
                            if (singleMove) endReorder();
                        }
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) pickUp(pos);
            return true;   // never opens the app while reordering
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        pickedFrom = pos;
        dimOthers();
    }

    /** Back while carrying: the tile goes back to where it was picked up. */
    private void cancelMove() {
        int from = adapter.positionOf(movingKey);
        int to = pickedFrom;
        drop();
        if (from >= 0 && to >= 0 && from != to) {
            keepScroll(() -> adapter.move(from, to));
            refocusAfterLayout(to);
        }
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        swallowOkUp = false;
        singleMove = false;
        pickedFrom = -1;
        dockEditing = false;
        dockEditSlot = -1;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        if (!keys.equals(before)) {
            Order order = new Order(Order.dockKey(dockApps), keys);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        // Holding an arrow sends a move per key repeat: only the newest one may set focus, or an older wait could
        // land after it and pull focus back to the tile just swapped.
        int gen = ++refocusGen;
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if (gen != refocusGen) return;
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    private int refocusGen;
    /** The tiles' entrance has played (reloadApps). */
    private boolean enteredOnce;

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere, except out of a search.
        if (searching) closeSearch(true);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations) glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                // Before Home's first frame the row goes straight to its place: the first layout used the old
                // padding, and a smooth scroll from there showed the tiles coming up from below.
                if (!animations || reducedMotion || !firstFrameShown) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // A plain grid (dock_row false) has no dock to carry the glow, so it follows every tile there.
            if (dockRow) hideHero();
            else showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        lastDockTop = dockTopInRoot;
        lastHeroPosition = position;
        // Not while another app is in front (onPause moving focus would start a carousel nobody sees).
        if (searching || !homeInFront) return;
        if (!showNowPlaying()) {
            boolean channels = showChannels(position);   // the row sits over the posters, so both may show
            // No posters: the app's own softened banner, also under a channel row (never the last app's picture).
            if (!showFeed(position) && !showShelfArt(position) && !channels) hideShelfArt(animations);
        }
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(1f);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) heroName.animate().cancel();
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * dock_row false: the glow behind the grid takes the focused tile's colour; the big name above the dock does not
     * show (every tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        hideShelfArt(animations);
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroName.setAlpha(0f);
        heroShown = false;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    /**
     * Washes the screen behind the shelf with the dock app's softened banner, crossfading from the previous app.
     * The views are full screen and never resized, so nothing relayouts the grid while focus moves.
     * False when the shelf art is off or the app has no banner.
     */
    private boolean showShelfArt(int position) {
        if (!topShelfArt) return false;
        Drawable art = adapter.shelfArtAt(position);
        if (art == null) return false;
        if (art == heroArtShown && heroArt.getAlpha() > 0f) return true;
        heroArt.animate().cancel();
        heroFade.animate().cancel();
        boolean wasShown = heroArtShown != null && heroArt.getAlpha() > 0f;
        heroArtShown = art;
        if (!animations || reducedMotion) {
            heroArt.setScaleType(ImageView.ScaleType.FIT_XY);
                heroArt.setImageDrawable(art);
            heroArt.setAlpha(1f);
            heroFade.setAlpha(1f);
            return true;
        }
        heroFade.animate().alpha(1f).setDuration(200).start();
        if (wasShown) {
            heroArt.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                heroArt.setScaleType(ImageView.ScaleType.FIT_XY);
                heroArt.setImageDrawable(art);
                heroArt.animate().alpha(1f).setDuration(260).start();
            }).start();
        } else {
            heroArt.setScaleType(ImageView.ScaleType.FIT_XY);
                heroArt.setImageDrawable(art);
            heroArt.animate().alpha(1f).setDuration(320).start();
        }
        return true;
    }

    /**
     * Starts the TMDB carousel when the focused dock app is in shelf_feed_apps: a backdrop every 8 s with its title
     * above the dock. Until the first backdrop arrives the softened banner stands in. False for other apps.
     */
    private boolean showFeed(int position) {
        String pkg = adapter.pkgAt(position);
        boolean fromChannels = channelApps.contains(pkg) && channelFeed.enabled();
        boolean fromKick = KickFeed.PACKAGE.equals(pkg) && kickFeed.enabled();
        if (!topShelfArt || postersOff.contains(pkg)
                || !(fromChannels || fromKick || (shelfFeed.enabled() && feedApps.contains(pkg)))) {
            stopFeed();
            return false;
        }
        if (pkg.equals(feedPkg) && feedChannel == null) {
            positionShelfTitle();
            showShelfOpen(feedItems != null && !feedItems.isEmpty(), true);   // back when a channel row went away
            return true;
        }
        stopFeed();
        // The last app's title goes at once: placed for this app's shelf (a channel row, another Watch pill) while
        // still holding the old, maybe two-line text, it jumped up and back down as the new title came in.
        shelfTitle.animate().cancel();
        shelfTitle.setAlpha(0f);
        shelfTitle.setText("");
        shelfLive.animate().cancel();
        shelfLive.setAlpha(0f);
        positionShelfTitle();
        feedPkg = pkg;
        // Its own softened banner right away (from another feed app the previous poster must not linger), the
        // first backdrop dissolves in over it.
        showShelfArt(position);   // the last app's banner, poster or cover must never linger under this one
        int gen = feedGen;
        ShelfFeed.ItemsListener onItems = items -> {
            if (gen != feedGen || !pkg.equals(feedPkg)) return;
            boolean first = feedItems == null;
            ShelfFeed.Item shown = first || feedItems.isEmpty() ? null : feedItems.get(feedIndex % feedItems.size());
            // Music is no film to go back to: a music app's shelf has no Continue entry, only what is popular.
            feedItems = ShelfFeed.music(pkg) ? items : withContinue(pkg, items);
            if (first) {
                feedIndex = 0;
                showFeedItem();
            } else if (shown != null) {
                // A fresher list: Watch must still open the film on screen, so keep its place, or show the new first.
                int at = -1;
                for (int i = 0; i < feedItems.size(); i++) if (feedItems.get(i).title.equals(shown.title)) at = i;
                if (at >= 0) feedIndex = at;
                else {
                    feedIndex = 0;
                    showFeedItem();
                }
            }
        };
        if (fromKick) kickFeed.items(onItems);
        else if (fromChannels) {
            // An app that also has TMDB posters (KIK) shows them while it publishes nothing to its channels.
            boolean tmdbToo = shelfFeed.enabled() && feedApps.contains(pkg);
            channelFeed.items(pkg, items -> {
                if (!items.isEmpty()) onItems.onItems(items);
                else if (tmdbToo && gen == feedGen && pkg.equals(feedPkg)) shelfFeed.items(pkg, onItems);
            });
        }
        else shelfFeed.items(pkg, onItems);
        return true;
    }

    /**
     * The film or video opened last in this app (Recents) put first, so the shelf opens on it and its pill says
     * Continue; the list's own copy of it, if any, is dropped. The list as it came when nothing was opened there.
     */
    private List<ShelfFeed.Item> withContinue(String pkg, List<ShelfFeed.Item> items) {
        continueItem = null;
        for (Recents.Entry e : recents.all()) {
            if (!e.pkg.equals(pkg) || !Recents.Entry.ITEM.equals(e.kind)) continue;
            String type = "";
            long id = 0;
            if (e.open == null) {
                String[] parts = e.key.split(":", 3);
                if (parts.length == 3) {
                    type = parts[0];
                    try { id = Long.parseLong(parts[1]); } catch (NumberFormatException ignored) { }
                }
            }
            continueItem = new ShelfFeed.Item(e.title, e.image, e.open, type, id);
            break;
        }
        if (continueItem == null || continueItem.backdrop == null) return items;
        List<ShelfFeed.Item> out = new ArrayList<>();
        out.add(continueItem);
        for (ShelfFeed.Item i : items) {
            boolean same = continueItem.open != null ? continueItem.open.equals(i.open) : continueItem.title.equals(i.title);
            if (!same) out.add(i);
        }
        return out;
    }

    /**
     * Bottom-left over the dock: the Watch pill just above the dock, the title above it; a long title wraps to a
     * second line and grows upwards. A poster with no pill (shelfLabel) has its title down in the pill's place.
     */
    private void positionShelfTitle() {
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        shelfOpen.measure(unspecified, unspecified);
        float openTop = lastDockTop - 26 * density - shelfOpen.getMeasuredHeight();
        if (channelsPkg != null) {
            shelfChannelsScroll.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY), unspecified);
            openTop = lastDockTop - 14 * density - shelfChannelsScroll.getMeasuredHeight() + 6 * density;
        }
        shelfOpen.setY(openTop);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(shelfTitle.getMaxWidth(), View.MeasureSpec.AT_MOST);
        shelfTitle.measure(widthSpec, unspecified);
        float titleTop = channelsPkg == null && !shelfOpens()
                ? openTop + shelfOpen.getMeasuredHeight() - shelfTitle.getMeasuredHeight()
                : openTop - 14 * density - shelfTitle.getMeasuredHeight();
        shelfLive.measure(unspecified, unspecified);
        moveShelfText(shelfTitle, titleTop);
        moveShelfText(shelfLive, titleTop - 10 * density - shelfLive.getMeasuredHeight());
    }

    /**
     * A title already on screen glides to its new height (Netflix's pill turning up a moment after the poster);
     * a hidden one is just placed. Its own animator, so the title's fade carries on.
     */
    private void moveShelfText(View v, float y) {
        Object running = v.getTag(R.id.shelfTitle);
        if (running instanceof android.animation.Animator) ((android.animation.Animator) running).cancel();
        if (v.getAlpha() == 0f || !animations || reducedMotion || Math.abs(v.getY() - y) < 1f) {
            v.setY(y);
            return;
        }
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.Y, y);
        a.setDuration(300);
        a.setInterpolator(EASE);
        v.setTag(R.id.shelfTitle, a);
        a.start();
    }

    // ---- the ambient screen (full-screen Now Playing) ---------------------------------------------

    /** The full-screen player, only ever on the owner's own press (the card's expand button). */
    private void showAmbient() {
        NowPlaying.Track t = nowPlaying.track();
        if (t == null || t.cover == null) return;   // nothing to show without a cover
        fillAmbient();
        if (ambientScreen.getVisibility() == View.VISIBLE && !ambientHiding) return;
        ambientHiding = false;
        ambientScreen.animate().cancel();
        ambientScreen.setVisibility(View.GONE);   // a fade-out still running is cut short; the opening starts clean
        ambientScreen.setVisibility(View.VISIBLE);
        ambientBg.start();
        ambientPlay.requestFocus();
        wakeAmbientControls();
        holdScreen(true);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (ambientTurn != null) ambientTurn.cancel();
        ambientCover.animate().cancel();
        ambientCover.setRotationX(0f);
        ambientCover.setRotationY(0f);
        ambientCover.setTranslationY(0f);
        ambientScreen.animate().cancel();
        if (animations && !reducedMotion) {
            // In: the screen dissolves in while the cover grows from a little smaller, as a sheet opens on Apple TV;
            // then the cover floats slowly, as if hung in the light: a long gentle sway, a slight turn, a breath in
            // size, each starting from rest so nothing jumps when the float takes over.
            ambientScreen.setAlpha(0f);
            ambientCover.setScaleX(0.88f);
            ambientCover.setScaleY(0.88f);
            ambientScreen.animate().alpha(1f).setDuration(550).setInterpolator(EASE).start();
            ambientCover.animate().scaleX(1f).scaleY(1f).setDuration(1000).setInterpolator(EASE)
                    .withEndAction(this::startCoverFloat).start();
        } else {
            ambientScreen.setAlpha(1f);
            ambientCover.setScaleX(1f);
            ambientCover.setScaleY(1f);
        }
        handler.removeCallbacks(npTick);
        handler.post(npTick);
    }

    private void startCoverFloat() {
        if (ambientScreen.getVisibility() != View.VISIBLE || !animations || reducedMotion) return;
        if (ambientTurn != null) ambientTurn.cancel();
        ambientTurn = android.animation.ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        ambientTurn.setDuration(32000);
        ambientTurn.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        ambientTurn.setInterpolator(new android.view.animation.LinearInterpolator());
        ambientTurn.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            ambientCover.setRotationY(4f * (float) Math.sin(p));
            ambientCover.setRotationX(2f * (float) Math.sin(p * 2f));
            ambientCover.setTranslationY(5 * density * (float) Math.sin(p * 3f));
            float breath = 1f + 0.01f * (float) Math.sin(p * 4f);
            ambientCover.setScaleX(breath);
            ambientCover.setScaleY(breath);
        });
        ambientTurn.start();
    }

    private void fillAmbient() {
        NowPlaying.Track t = nowPlaying.track();
        if (t == null) return;
        String artist = t.artist.isEmpty() ? t.app : t.artist + "  ·  " + t.app;
        boolean smooth = animations && !reducedMotion && ambientScreen.getVisibility() == View.VISIBLE
                && ambientScreen.getAlpha() > 0.5f;
        if (!t.title.contentEquals(ambientTitle.getText()) || !artist.contentEquals(ambientArtist.getText())) {
            if (smooth && ambientTitle.length() > 0) {
                // The next track's name dissolves in where the last one was, as Apple Music does it.
                for (TextView v : new TextView[]{ambientTitle, ambientArtist}) {
                    String text = v == ambientTitle ? t.title : artist;
                    v.animate().cancel();
                    v.animate().alpha(0f).setDuration(160).setInterpolator(EASE).withEndAction(() -> {
                        v.setText(text);
                        if (v == ambientTitle) fitTitle(v, 28f, 22f);
                        v.animate().alpha(1f).setDuration(420).setInterpolator(EASE).start();
                    }).start();
                }
            } else {
                ambientTitle.setText(t.title);
                fitTitle(ambientTitle, 28f, 22f);
                ambientArtist.setText(artist);
                ambientTitle.setAlpha(1f);
                ambientArtist.setAlpha(1f);
            }
        }
        ambientPlay.setCompoundDrawablesRelativeWithIntrinsicBounds(t.playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play, 0, 0, 0);
        // A track that came without its cover yet (the next one, a moment before its art) keeps the last cover
        // up; the player never drops out of the full screen over it.
        if (t.cover == null) return;
        if (t.cover != ambientCoverShown) {
            ambientCoverShown = t.cover;
            BitmapDrawable next = new BitmapDrawable(getResources(), t.cover);
            Drawable now = ambientCover.getDrawable();
            if (now instanceof android.graphics.drawable.TransitionDrawable) {
                now = ((android.graphics.drawable.TransitionDrawable) now).getDrawable(1);
            }
            if (smooth && now != null) {
                // One cover dissolves into the next; nothing blinks in between.
                android.graphics.drawable.TransitionDrawable cross =
                        new android.graphics.drawable.TransitionDrawable(new Drawable[]{now, next});
                cross.setCrossFadeEnabled(true);
                ambientCover.setImageDrawable(cross);
                cross.startTransition(700);
                handler.postDelayed(() -> {
                    if (ambientCover.getDrawable() == cross) ambientCover.setImageDrawable(next);
                }, 760);
            } else {
                ambientCover.setImageDrawable(next);
            }
            ambientBg.setCover(t.cover);   // its colours glide over to the new cover's (AmbientView)
        }
    }

    /** A wake lock for the full-screen player, so neither sleep nor the screensaver comes while it is up. */
    private android.os.PowerManager.WakeLock screenHold;

    @SuppressWarnings("deprecation")   // the screen-bright lock is what keeps a TV's dream away; no newer way exists
    private void holdScreen(boolean on) {
        if (on) {
            if (screenHold == null) {
                android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
                if (pm == null) return;
                screenHold = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | android.os.PowerManager.ON_AFTER_RELEASE, "TvHome:player");
                screenHold.setReferenceCounted(false);
            }
            screenHold.acquire(4 * 60 * 60 * 1000L);   // four hours at most, in case Home is left without a key
        } else if (screenHold != null && screenHold.isHeld()) {
            screenHold.release();
        }
    }

    private void hideAmbientIfSilent() {
        if (nowPlaying.track() == null) hideAmbient();
    }

    private void restAmbientControls() {
        if (ambientScreen.getVisibility() != View.VISIBLE || !ambientControlsShown) return;
        ambientControlsShown = false;
        ambientControls.animate().cancel();
        // The row fades, and the cover with its titles settles into the middle of the screen, the row's space gone.
        View block = (View) ambientControls.getParent();
        float shift = (ambientControls.getHeight()
                + ((ViewGroup.MarginLayoutParams) ambientControls.getLayoutParams()).topMargin) / 2f;
        block.animate().cancel();
        if (animations && !reducedMotion) {
            ambientControls.animate().alpha(0f).translationY(10 * density).setDuration(500).setInterpolator(EASE).start();
            block.animate().translationY(shift).setDuration(1100).setInterpolator(EASE).start();
        } else {
            ambientControls.setAlpha(0f);
            block.setTranslationY(shift);
        }
    }

    /** The button row back in view, and resting again after four seconds without a key. */
    private void wakeAmbientControls() {
        handler.removeCallbacks(ambientRest);
        if (!ambientControlsShown) {
            ambientControlsShown = true;
            ambientControls.animate().cancel();
            View block = (View) ambientControls.getParent();
            block.animate().cancel();
            if (animations && !reducedMotion) {
                ambientControls.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(EASE).start();
                block.animate().translationY(0f).setDuration(480).setInterpolator(EASE).start();
            } else {
                ambientControls.setAlpha(1f);
                ambientControls.setTranslationY(0f);
                block.setTranslationY(0f);
            }
        }
        handler.postDelayed(ambientRest, 4000);
    }

    private void hideAmbient() {
        if (ambientScreen.getVisibility() != View.VISIBLE || ambientHiding) return;
        handler.removeCallbacks(ambientRest);
        holdScreen(false);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The card may be a track behind (it only follows while the dock has focus): bring it up to date first.
        if (nowPlaying.track() != null) showNowPlaying();
        else hideNowPlaying(false);
        // Focus back on the card's expand button, where the player was opened from, else on the dock.
        if (ambientScreen.hasFocus()) {
            View expand = findViewById(R.id.npExpand);
            if (npCard.getVisibility() == View.VISIBLE && npCard.getAlpha() > 0f) expand.requestFocus();
            else focusTile(Math.max(0, lastFocused));
        }
        if (ambientTurn != null) {
            ambientTurn.cancel();
            ambientTurn = null;
        }
        ambientScreen.animate().cancel();
        // The button row and the centred block go back to their resting places for the next time.
        ambientControlsShown = true;
        ambientControls.animate().cancel();
        ambientControls.setAlpha(1f);
        ambientControls.setTranslationY(0f);
        ((View) ambientControls.getParent()).animate().cancel();
        ((View) ambientControls.getParent()).setTranslationY(0f);
        if (animations && !reducedMotion) {
            // Out: the sheet dissolves as the cover settles back a little, the reverse of its opening.
            ambientCover.animate().cancel();
            ambientCover.animate().scaleX(0.94f).scaleY(0.94f).setDuration(380).setInterpolator(EASE).start();
            ambientHiding = true;
            ambientScreen.animate().alpha(0f).setDuration(380).setInterpolator(EASE).withEndAction(() -> {
                if (!ambientHiding) return;   // opened again meanwhile
                ambientHiding = false;
                ambientScreen.setVisibility(View.GONE);
                ambientBg.stop();
            }).start();
        } else {
            ambientScreen.setAlpha(0f);
            ambientScreen.setVisibility(View.GONE);
            ambientBg.stop();
        }
    }

    // ---- the channel row ---------------------------------------------------------------------------

    /**
     * The focused dock app's chosen live channels as a row of tiles over its softened banner, when its shelf is in
     * channel mode (LiveChannels). False for every other app.
     */
    private boolean showChannels(int position) {
        String pkg = adapter.pkgAt(position);
        // No channels chosen or opened yet: the app's posters, not an empty row with a hint over them. Channels come
        // with a choice (hold OK on the tile) or an opening (Search, a voice command).
        if (!topShelfArt || pkg == null || !liveChannels.available(pkg) || !liveChannels.channelMode(pkg)
                || liveChannels.row(pkg).isEmpty()) {
            stopChannels(animations);
            return false;
        }
        if (pkg.equals(channelsPkg)) return true;
        channelsPkg = pkg;
        showShelfOpen(false, animations);   // Up goes to the channels; the poster's title stays
        buildChannelRow(pkg, position);
        shelfChannelsScroll.setVisibility(View.VISIBLE);
        shelfChannelsScroll.animate().cancel();
        // In place before the first frame (measured now), then checked once laid out: no frame in the wrong spot.
        shelfChannelsScroll.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        shelfChannelsScroll.setY(lastDockTop - 14 * density - shelfChannelsScroll.getMeasuredHeight());
        shelfChannelsScroll.post(() -> shelfChannelsScroll.setY(lastDockTop - 14 * density - shelfChannelsScroll.getHeight()));
        if (animations && !reducedMotion) shelfChannelsScroll.animate().alpha(1f).setDuration(300).start();
        else shelfChannelsScroll.setAlpha(1f);
        return true;
    }

    private void buildChannelRow(String pkg, int position) {
        shelfChannels.removeAllViews();
        shelfChannelsScroll.scrollTo(0, 0);
        List<LiveChannels.Channel> chosen = liveChannels.row(pkg);
        if (chosen.isEmpty()) {
            View tile = channelTile(null, pkg);
            tile.setOnClickListener(v -> openChannelPicker(pkg, position));
            shelfChannels.addView(tile);
            return;
        }
        // The row as built last time for this app, while its channels are the same: moving along the dock and back
        // re-attaches those tiles (their logos already in) instead of building a dozen views and decoding logos again.
        StringBuilder sig = new StringBuilder();
        for (LiveChannels.Channel c : chosen) sig.append(c.id).append('|').append(c.name).append('|').append(c.logo).append('|').append(c.open).append('\n');
        String signature = sig.toString();
        ChannelRow cached = channelRows.get(pkg);
        if (cached == null || !cached.signature.equals(signature)) {
            List<View> tiles = new ArrayList<>();
            for (LiveChannels.Channel c : chosen) tiles.add(channelTile(c, pkg));
            channelRows.put(pkg, cached = new ChannelRow(signature, tiles));
        }
        for (View tile : cached.tiles) {
            tile.animate().cancel();
            tile.setScaleX(1f);
            tile.setScaleY(1f);
            tile.setTranslationZ(0f);
            tile.setTranslationY(0f);
            tile.setAlpha(1f);
            shelfChannels.addView(tile);
        }
    }

    /** A built channel row and the channels it was built from. */
    private static final class ChannelRow {
        final String signature;
        final List<View> tiles;

        ChannelRow(String signature, List<View> tiles) {
            this.signature = signature;
            this.tiles = tiles;
        }
    }

    private final Map<String, ChannelRow> channelRows = new HashMap<>();

    /** One channel tile: its logo on a dark card, or its name; null makes the "choose channels" tile. */
    private View channelTile(LiveChannels.Channel c, String pkg) {
        android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
        // Five fill the row edge to edge (156 + 10 each, in the row's 830dp between its paddings); fewer sit centred.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (156 * density), (int) (92 * density));
        lp.setMarginStart((int) (5 * density));
        lp.setMarginEnd((int) (5 * density));
        tile.setLayoutParams(lp);
        tile.setBackgroundResource(R.drawable.tile_bg);
        tile.setFocusable(true);
        tile.setClipToOutline(true);
        TextView name = new TextView(this);
        name.setText(c == null ? getString(R.string.channels_empty) : c.name);
        name.setTextColor(0xF2FFFFFF);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, c == null ? 11 : 13);
        name.setTypeface(getResources().getFont(R.font.outfit_medium));
        name.setGravity(android.view.Gravity.CENTER);
        name.setMaxLines(3);
        name.setPadding((int) (10 * density), 0, (int) (10 * density), 0);
        tile.addView(name, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (c != null && c.logo != null) {
            ImageView logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), (int) (10 * density));
            tile.addView(logo, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            shelfFeed.image(c.logo, (url, bmp) -> {
                if (bmp == null || logo.getParent() == null) return;
                logo.setImageBitmap(LiveChannels.trim(bmp));
                name.setVisibility(View.GONE);
            });
        }
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            float s = focused ? 1.1f : 1f;
            if (animations && !reducedMotion) {
                v.animate().scaleX(s).scaleY(s).translationZ(focused ? 6 * density : 0f).setDuration(200)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {
                v.setScaleX(s);
                v.setScaleY(s);
            }
            v.setAlpha(1f);
            v.setTranslationY(0f);   // a search tile still rising into place lands at once
            if (focused) hideGlass();
            if (focused && c != null) showChannelPosters(pkg, c);
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_BACK) {
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                firstPill().requestFocus();
                return true;
            }
            // Along the row by hand: the focus finder, from a tile scaled up and a row partly scrolled, can pick the
            // pills above (Search) instead of the next tile.
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            View next = shelfChannels.getChildAt(shelfChannels.indexOfChild(v) + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
            if (next != null) next.requestFocus();
            return true;
        });
        if (c != null) tile.setOnClickListener(v -> openChannel(pkg, c));
        // Hold OK on a channel of an app's channel row: take it off the shelf, or choose the channels.
        if (c != null && liveChannels.available(pkg)) {
            tile.setOnLongClickListener(v -> {
                showChannelMenu(pkg, c, v);
                return true;
            });
        }
        return tile;
    }

    private LiveChannels.Channel postersFor;
    private String postersPkg;
    private final Runnable channelPosters = this::loadChannelPosters;

    /**
     * A channel tile has focus: the posters behind the row become the programmes popular on that channel (Kyivstar's
     * Worker route), after a moment, so running along the row does not ask for every channel passed. A channel with
     * none keeps the posters shown.
     */
    private void showChannelPosters(String pkg, LiveChannels.Channel c) {
        if (!pkg.equals(feedPkg) || c.id.equals(feedChannel)) return;
        postersFor = c;
        postersPkg = pkg;
        handler.removeCallbacks(channelPosters);
        handler.postDelayed(channelPosters, 350);
    }

    private void loadChannelPosters() {
        LiveChannels.Channel c = postersFor;
        String pkg = postersPkg;
        if (c == null || pkg == null || !pkg.equals(feedPkg)) return;
        int gen = feedGen;
        shelfFeed.channelItems(c.id, c.name, items -> {
            if (gen != feedGen || !pkg.equals(feedPkg) || postersFor != c || items.isEmpty()) return;
            View f = getCurrentFocus();
            if (f == null || f.getParent() != shelfChannels) return;   // focus already back on the dock
            feedChannel = c.id;
            feedItems = new ArrayList<>(items);
            feedIndex = 0;
            showFeedItem();
        });
    }

    /** The small menu over a held channel tile: remove it from the shelf, or open the channel picker. */
    private void showChannelMenu(String pkg, LiveChannels.Channel c, View anchor) {
        if (openPopup != null) openPopup.dismiss();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (240 * density));
        addSettingsMenuRow(rows, popup, getString(R.string.channel_remove, c.name), () -> {
            liveChannels.remove(pkg, c.id);
            stopChannels(false);
            if (lastFocused >= 0 && lastFocused < COLUMNS) showHero(lastFocused, lastDockTop);
            View first = shelfChannels.getChildCount() > 0 ? shelfChannels.getChildAt(0) : null;
            if (first != null) first.requestFocus();
            else focusTile(Math.max(0, lastFocused));
        });
        addSettingsMenuRow(rows, popup, R.string.choose_channels, () -> openChannelPicker(pkg, lastFocused));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (240 * density), Math.min(panel.getMeasuredWidth(), (int) (420 * density))));
        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private void openChannel(String pkg, LiveChannels.Channel c) {
        liveChannels.recordOpen(pkg, c.id);
        recents.channel(pkg, c);
        android.util.Log.i("TvHome", "open channel " + c.name + " in " + pkg);
        open(new Intent(Intent.ACTION_VIEW, Uri.parse(c.open)).setPackage(pkg));
    }

    private void stopChannels(boolean animate) {
        if (channelsPkg == null) return;
        channelsPkg = null;
        // Focus moves last: moved first, the dock tile redrew the shelf (a new row) and this then hid that new row.
        boolean hadFocus = shelfChannelsScroll.hasFocus();
        shelfChannelsScroll.animate().cancel();
        if (animate && !reducedMotion) {
            shelfChannelsScroll.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (shelfChannelsScroll.getAlpha() == 0f) shelfChannelsScroll.setVisibility(View.GONE);
            }).start();
        } else {
            shelfChannelsScroll.setAlpha(0f);
            shelfChannelsScroll.setVisibility(View.GONE);
        }
        if (hadFocus) focusTile(lastFocused % COLUMNS);
    }

    private void openChannelPicker(String pkg, int position) {
        if (openPopup != null) openPopup.dismiss();
        String label = adapter.labelAt(position);
        openPopup = ChannelPicker.show(this, label != null ? label : pkg, liveChannels.all(pkg), liveChannels.chosenIds(pkg),
                liveChannels.hiddenIds(pkg), shelfFeed::image, (chosen, hidden) -> {
            openPopup = null;
            liveChannels.setChoice(pkg, chosen, hidden);
            stopChannels(false);
            refreshShelf(position);
            focusTile(position);
        });
    }

    /** Redraws the shelf after a menu choice, only for a dock tile that has it (never over a second-row tile). */
    private void refreshShelf(int position) {
        if (position >= 0 && position < COLUMNS && position == lastFocused) showHero(position, lastDockTop);
        else hideHero();
    }

    /** Opens what the shelf shows: the video itself, the film's page where the app has one, else the app. */
    private void openFeedItem() {
        if (feedPkg == null || feedItems == null || feedItems.isEmpty()) return;
        openItem(feedPkg, feedItems.get(feedIndex % feedItems.size()));
    }

    /** Opens a shelf item (also from Search and the Continue row) and remembers it. */
    private static final String NETFLIX_PKG = "com.netflix.ninja";
    private static final String KIK_PKG = "kik.app";

    /**
     * A film or series by the name said or typed, in KIK: it opens the title when its catalogue has a sure match,
     * else its search with the name typed in. False without KIK (or an older one that cannot).
     */
    private boolean openInKik(String name) {
        name = name == null ? "" : name.trim();
        if (name.length() < 2) return false;
        Intent kik = new Intent("kik.intent.action.OPEN_TITLE").setPackage(KIK_PKG).putExtra(android.app.SearchManager.QUERY, name);
        if (kik.resolveActivity(getPackageManager()) == null) return false;
        android.util.Log.i("TvHome", "kik: " + name);
        closeSearch(false);
        open(kik);
        return true;
    }

    /** What was heard: the app, channel or shelf title it names, else straight to KIK when nothing here matches. */
    private boolean openHeard(String words) {
        boolean known = searchAll != null && !SearchIndex.filter(searchAll, words).isEmpty();
        if (!known && openInKik(words)) return true;
        return focusSearchResult();
    }

    /**
     * The shelf button's word, or 0 for no button. There is a button only where it opens that very title: a link of
     * its own (SmartTube, KIK's channels, Kick, a Continue entry) or Netflix with the title's Netflix id. A poster
     * that could only open the app (Kyivstar) or a search by its name (TMDB posters over KIK) has none.
     */
    /**
     * A song on a music app's shelf reads as the player does: its title large, the artist under it, and a round Play
     * button like the player's instead of a film's labelled pill. The Worker's titles are "Song · Artist".
     */
    private void showAsSong(String title) {
        int dot = title.lastIndexOf(" · ");
        String song = dot > 0 ? title.substring(0, dot) : title;
        String artist = dot > 0 ? title.substring(dot + 3) : "";
        android.text.SpannableStringBuilder t = new android.text.SpannableStringBuilder(song);
        if (!artist.isEmpty()) {
            int start = t.length();
            t.append('\n').append(artist);
            t.setSpan(new android.text.style.RelativeSizeSpan(0.55f), start, t.length(), 0);
            t.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), start, t.length(), 0);
        }
        shelfTitle.setText(t);
        playerPill(true);
    }

    private Drawable pillBackground;
    private android.content.res.ColorStateList pillText;

    /**
     * The shelf's pill as a music player's Play button: a white disc with a dark triangle, as Apple Music and the
     * Spotify app have it; or back as the labelled glass Watch pill for films.
     */
    private void playerPill(boolean round) {
        if (pillBackground == null) {
            pillBackground = shelfOpen.getBackground();
            pillText = shelfOpen.getTextColors();
        }
        if (round) shelfOpen.setText("");   // each poster sets its label first
        if (round == (shelfOpen.getTag() == Boolean.TRUE)) return;
        shelfOpen.setTag(round);
        int size = (int) (42 * density);
        if (round) {
            // The disc and its triangle in one drawable, the triangle at the disc's centre (a compound drawable sits
            // at the padding's edge, which never quite centres it); nudged right a dp, where its weight looks right.
            android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
            disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            disc.setColor(0xFFFFFFFF);
            Drawable play = getDrawable(R.drawable.ic_np_play).mutate();
            play.setTint(0xFF111111);
            android.graphics.drawable.LayerDrawable face = new android.graphics.drawable.LayerDrawable(new Drawable[]{disc, play});
            int icon = (int) (20 * density);
            face.setLayerSize(1, icon, icon);
            face.setLayerGravity(1, Gravity.CENTER);
            face.setLayerInsetStart(1, (int) (1.5f * density));
            shelfOpen.setBackground(face);
            shelfOpen.setCompoundDrawablesRelative(null, null, null, null);
            shelfOpen.setText("");
            shelfOpen.setContentDescription(getString(R.string.listen_now));
            shelfOpen.setPaddingRelative(0, 0, 0, 0);
            shelfOpen.setMinWidth(size);
            shelfOpen.setMinHeight(size);
            shelfOpen.setMinimumWidth(size);
            shelfOpen.setMinimumHeight(size);
        } else {
            shelfOpen.setBackground(pillBackground);
            shelfOpen.setTextColor(pillText);
            shelfOpen.setContentDescription(null);
            shelfOpen.setPaddingRelative((int) (20 * density), 0, (int) (22 * density), 0);
            shelfOpen.setCompoundDrawablePadding((int) (10 * density));
            shelfOpen.setMinWidth(0);
            shelfOpen.setMinHeight((int) (42 * density));
            shelfOpen.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_np_play, 0, 0, 0);
        }
    }

    private int shelfLabel(String pkg, ShelfFeed.Item item) {
        boolean title = item.open != null;
        if (!title && NETFLIX_PKG.equals(pkg) && item.id > 0) {
            String id = shelfFeed.knownNetflixId(item.type, item.id);
            title = id != null && !id.isEmpty();
        }
        if (!title) return 0;
        if (item == continueItem) return R.string.continue_row;
        return ShelfFeed.music(pkg) ? R.string.listen_now : R.string.watch_now;
    }

    /** Whether the poster on the shelf now has a button (see shelfLabel). */
    private boolean shelfOpens() {
        if (feedPkg == null || feedItems == null || feedItems.isEmpty()) return false;
        return shelfLabel(feedPkg, feedItems.get(feedIndex % feedItems.size())) != 0;
    }

    /**
     * Opening the title itself inside the app, by name: KIK finds it in its catalogue and opens its page
     * (kik.intent.action.OPEN_TITLE, with the original title and year to tell same-named films apart); HDRezka opens
     * its search, landing on the film when there is one match. Null where the app can do neither (older KIK builds).
     */
    private Intent titleSearch(String pkg, ShelfFeed.Item item) {
        if (!pkg.equals(KIK_PKG)) return null;
        // A Continue entry keeps only the name and TMDB id: the original title and year come from the poster list.
        if (item.original.isEmpty() && item.id > 0) {
            for (ShelfFeed.Item known : shelfFeed.cached(pkg)) {
                if (known.id == item.id && known.type.equals(item.type)) {
                    item.original = known.original;
                    item.year = known.year;
                    break;
                }
            }
        }
        Intent kik = new Intent("kik.intent.action.OPEN_TITLE").setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, item.title)
                .putExtra("kik.original", item.original)
                .putExtra("kik.year", item.year);
        if (kik.resolveActivity(getPackageManager()) != null) return kik;
        Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        ComponentName home = tv != null ? tv.getComponent() : null;
        if (home == null) return null;
        Intent search = new Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, item.title).setPackage(pkg);
        for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(search, 0)) {
            if (ri.activityInfo != null && home.getClassName().equals(ri.activityInfo.name)) return search.setComponent(home);
        }
        return null;
    }

    private void openItem(String pkg, ShelfFeed.Item item) {
        recents.item(pkg, item);
        if (item.open != null && item.open.startsWith("kick:")) {
            playKick(item.open.substring(5));
            return;
        }
        if (item.open != null) {
            openUri(item.open, pkg);
            return;
        }
        if ("com.netflix.ninja".equals(pkg) && item.id > 0 && shelfFeed.enabled()) {
            shelfOpen.setAlpha(0.5f);
            shelfFeed.netflixId(item.type, item.id, id -> {
                shelfOpen.setAlpha(1f);
                if (id.isEmpty()) launchApp(pkg);
                else open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/" + id)).setPackage(pkg));
            });
            return;
        }
        Intent search = titleSearch(pkg, item);
        if (search != null) {
            open(search);
            return;
        }
        launchApp(pkg);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A Kick stream: Kick's TV app opens no particular channel, so the stream itself goes to a video player on the
     * TV (Just Player, ViMu, VLC, MX), with a fresh link; without one, or when it went offline, Kick opens.
     */
    private void playKick(String slug) {
        shelfOpen.setAlpha(0.5f);
        kickFeed.play(slug, url -> {
            shelfOpen.setAlpha(1f);
            if (url == null) {
                launchApp(KickFeed.PACKAGE);
                return;
            }
            for (String player : new String[]{"com.brouken.player", "net.gtvbox.videoplayer", "org.videolan.vlc",
                    "com.mxtech.videoplayer.ad", "is.xyz.mpv"}) {
                if (!installed(player)) continue;
                android.util.Log.i("TvHome", "kick " + slug + " in " + player);
                open(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "application/x-mpegURL").setPackage(player));
                return;
            }
            launchApp(KickFeed.PACKAGE);
        });
    }

    /**
     * An intent: or plain URI into its app; the app itself when the URI is broken. The URI comes from outside (another
     * app's home-screen programs, the Worker's shelf), so it may only open that app: never another one, never the
     * launcher's own screens, and it hands no file access on.
     */
    private void openUri(String uri, String pkg) {
        try {
            Intent i = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.setSelector(null);
            if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) i.setComponent(null);
            i.setPackage(pkg);
            i.setFlags(i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            open(i);
        } catch (java.net.URISyntaxException e) {
            launchApp(pkg);
        }
    }

    // ---- search -------------------------------------------------------------------------------------

    /**
     * Search in place: the Search pill stretches left along the top bar, as far as the clock, and becomes the text
     * field (the TV's keyboard comes up by itself); the matches appear as tiles in the shelf's row under it. A query
     * from voice is searched at once. Back puts everything back.
     */
    private void openSearch(String query) {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        if (searching) {
            searchField.setText(query);
            searchField.setSelection(query.length());
            if (query.isEmpty() || !focusSearchResult()) searchField.requestFocus();
            return;
        }
        searching = true;
        searchAll = searchIndex();
        hideHero();
        hideGlass();
        View topInfo = findViewById(R.id.topInfo);
        View leftBlock = findViewById(R.id.leftBlock);
        // From the pill's right edge to just past the clock (or the greeting block when the clock is not up there).
        int leftEdge = topInfo.getVisibility() == View.VISIBLE ? topInfo.getRight() + (int) (28 * density)
                : leftBlock.getLeft() + (int) (320 * density);
        int target = Math.max(btnSearch.getWidth(), btnSearch.getRight()
                + ((View) btnSearch.getParent()).getLeft() - leftEdge - (int) (60 * density));
        searchPillWidth = btnSearch.getWidth();
        searchField.setText("");
        searchField.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) searchField.getLayoutParams();
        flp.width = searchPillWidth;
        searchField.setLayoutParams(flp);
        findViewById(R.id.searchMic).setVisibility(View.VISIBLE);
        searchField.requestFocus();   // the pill lets go of its white focus before it goes, so nothing flashes
        btnSearch.setVisibility(View.GONE);
        Runnable done = () -> {
            if (!searching) return;
            if (!query.isEmpty()) {
                searchField.setText(query);
                searchField.setSelection(query.length());
                if (!focusSearchResult()) searchField.requestFocus();
            } else if (listenOnOpen) {
                listenOnOpen = false;
                beginListening();
            } else {
                android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (imm != null) imm.showSoftInput(searchField, 0);
            }
        };
        if (animations && !reducedMotion) {
            ValueAnimator grow = ValueAnimator.ofInt(searchPillWidth, target);
            grow.setDuration(320);
            grow.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
            grow.addUpdateListener(a -> {
                flp.width = (int) a.getAnimatedValue();
                searchField.setLayoutParams(flp);
            });
            grow.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { done.run(); }
            });
            grow.start();
        } else {
            flp.width = target;
            searchField.setLayoutParams(flp);
            done.run();
        }
    }

    /** The matches for what is typed, as tiles in the shelf's row right under the top bar. */
    private void showSearchResults(String query) {
        List<SearchIndex.Result> found = SearchIndex.filter(searchAll, query);
        shelfChannels.removeAllViews();
        shelfChannelsScroll.scrollTo(0, 0);
        if (found.isEmpty()) {
            if (!query.trim().isEmpty()) {
                TextView none = new TextView(this);
                none.setText(R.string.search_nothing);
                none.setTextColor(0x8CFFFFFF);
                none.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                none.setTypeface(getResources().getFont(R.font.outfit_regular));
                none.setGravity(Gravity.CENTER_VERTICAL);
                none.setPadding((int) (6 * density), 0, 0, 0);
                none.setMinHeight((int) (84 * density));
                shelfChannels.addView(none);
            }
        } else {
            int n = 0;
            for (SearchIndex.Result r : found) {
                View tile = searchTile(r);
                shelfChannels.addView(tile);
                if (animations && !reducedMotion && n < 8) {
                    // The first tiles rise into place one after another, the way results arrive on Apple TV.
                    tile.setAlpha(0f);
                    tile.setTranslationY(14 * density);
                    tile.animate().alpha(1f).translationY(0f).setDuration(280).setStartDelay(n * 35L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
                }
                n++;
            }
        }
        String name = query.trim();
        if (name.length() >= 2 && installed(KIK_PKG)) {
            // Any film or series, not only the ones on the shelves: KIK finds the name in its own catalogue.
            Drawable banner = null, art;
            try { banner = getPackageManager().getApplicationBanner(KIK_PKG); } catch (Exception ignored) { }
            try { art = banner != null ? banner : getPackageManager().getApplicationIcon(KIK_PKG); } catch (Exception gone) { art = null; }
            SearchIndex.Result r = new SearchIndex.Result(name, getString(R.string.search_in_kik), art, null, banner != null, 4,
                    () -> openInKik(name));
            if (shelfChannels.getChildCount() > 0 && !shelfChannels.getChildAt(0).isFocusable()) shelfChannels.removeAllViews();
            View tile = searchTile(r);
            if (art != null) {
                // The banner says KIK; the name said or typed goes along its bottom.
                TextView label = (TextView) ((android.widget.FrameLayout) tile).getChildAt(1);
                label.setText(getString(R.string.search_in_kik_tile, name));
                label.setVisibility(View.VISIBLE);
                label.setGravity(Gravity.BOTTOM | Gravity.START);
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                label.setPadding((int) (10 * density), 0, (int) (10 * density), (int) (7 * density));
                label.setBackground(new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xC0000000}));
            }
            shelfChannels.addView(tile);
        }
        channelsPkg = "\u0000search";
        shelfChannelsScroll.setVisibility(View.VISIBLE);
        shelfChannelsScroll.animate().cancel();
        shelfChannelsScroll.post(() -> {
            View bar = (View) findViewById(R.id.topButtons).getParent();
            shelfChannelsScroll.setY(bar.getBottom() + ((View) bar.getParent()).getTop() + 16 * density);
        });
        shelfChannelsScroll.setAlpha(1f);
    }

    /** A match as a tile: its picture (an app's banner or icon, a channel's logo, a film's backdrop) and its name. */
    private View searchTile(SearchIndex.Result r) {
        View tile = channelTile(new LiveChannels.Channel(r.title, r.title, r.icon == null ? r.image : null, null), "");
        android.widget.FrameLayout f = (android.widget.FrameLayout) tile;
        TextView name = (TextView) f.getChildAt(0);
        if (r.icon != null) {
            ImageView art = new ImageView(this);
            art.setImageDrawable(r.icon);
            if (r.fill) art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            else {
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = (int) (18 * density);
                art.setPadding(pad, pad, pad, pad);
            }
            f.addView(art, 0, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            name.setVisibility(View.GONE);
        } else if (r.fill && r.image != null) {
            // A backdrop: channelTile would fit a logo with room around it; a film fills the tile, its title along the bottom.
            ImageView logo = (ImageView) f.getChildAt(1);
            logo.setPadding(0, 0, 0, 0);
            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            name.setGravity(Gravity.BOTTOM | Gravity.START);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            name.setPadding((int) (10 * density), 0, (int) (10 * density), (int) (7 * density));
            shelfFeed.image(r.image, (url, bmp) -> {
                if (bmp == null || logo.getParent() == null) return;
                name.setVisibility(View.VISIBLE);
                name.setBackground(new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xC0000000}));
            });
        }
        tile.setOnClickListener(v -> {
            closeSearch(false);
            r.open.run();
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeSearch(true); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { searchField.requestFocus(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            // Along the row by hand: the focus finder, from a tile scaled up and a row partly scrolled, can pick the
            // pills above (Search) instead of the next tile.
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            View next = shelfChannels.getChildAt(shelfChannels.indexOfChild(v) + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
            if (next != null) next.requestFocus();
            return true;
        });
        return tile;
    }

    /** Focus on the first match, the keyboard put away so the tiles are all in view; false without matches. */
    private boolean focusSearchResult() {
        if (!searching || shelfChannels.getChildCount() == 0 || !shelfChannels.getChildAt(0).isFocusable()) return false;
        shelfChannels.getChildAt(0).requestFocus();
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        return true;
    }

    /** The field shrinks back into the pill and the shelf shows the focused dock app again. */
    private void closeSearch(boolean refocus) {
        if (!searching) return;
        searching = false;
        searchAll = null;
        aiGen++;
        showSearchNote(null);
        stopListening(true);
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        stopChannels(animations);
        LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) searchField.getLayoutParams();
        findViewById(R.id.searchMic).setVisibility(View.GONE);
        Runnable done = () -> {
            searchField.setVisibility(View.GONE);
            searchField.setText("");
            btnSearch.setVisibility(View.VISIBLE);
            if (refocus) btnSearch.requestFocus();
        };
        if (animations && !reducedMotion && searchField.getVisibility() == View.VISIBLE && refocus) {
            ValueAnimator shrink = ValueAnimator.ofInt(flp.width, searchPillWidth);
            shrink.setDuration(260);
            shrink.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
            shrink.addUpdateListener(a -> {
                flp.width = (int) a.getAnimatedValue();
                searchField.setLayoutParams(flp);
            });
            shrink.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { done.run(); }
            });
            shrink.start();
        } else {
            done.run();
        }
        if (refocus) {
            // The shelf for the dock tile that was focused before, without moving focus there.
            if (lastFocused < COLUMNS && lastFocused >= 0 && adapter.appCount() > 0) showHero(lastFocused, lastDockTop);
        }
    }

    /** Everything Search can find: the installed apps, the shipped live channels, the films and videos on the shelves. */
    private List<SearchIndex.Result> searchIndex() {
        List<SearchIndex.Result> out = new ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        Map<String, android.content.pm.ResolveInfo> apps = new java.util.LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !apps.containsKey(pkg)) apps.put(pkg, info);
            }
        }
        Map<String, String> labels = new java.util.HashMap<>();
        for (Map.Entry<String, android.content.pm.ResolveInfo> e : apps.entrySet()) {
            String pkg = e.getKey();
            String label;
            Drawable icon;
            try {
                label = Cards.cleanLabel(String.valueOf(e.getValue().loadLabel(pm)), pkg);
                icon = e.getValue().loadIcon(pm);
            } catch (RuntimeException unavailable) {
                continue;
            }
            labels.put(pkg, label);
            Drawable banner = null;
            try { banner = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
            out.add(new SearchIndex.Result(label, getString(R.string.search_app), banner != null ? banner : icon, null,
                    banner != null, 0, () -> {
                recents.app(pkg, label);
                launchApp(pkg);
            }));
        }
        for (String pkg : liveChannelPackages()) {
            String app = labels.get(pkg);
            if (app == null) continue;   // not installed
            Set<String> never = liveChannels.hiddenIds(pkg);
            for (LiveChannels.Channel c : liveChannels.all(pkg)) {
                if (never.contains(c.id)) continue;   // "Never show" holds for Search too
                out.add(new SearchIndex.Result(c.name, getString(R.string.search_channel, app), null, c.logo, false, 1,
                        () -> openChannel(pkg, c)));
            }
        }
        for (String pkg : feedApps) {
            String app = labels.get(pkg);
            if (app == null) continue;
            for (ShelfFeed.Item item : shelfFeed.cached(pkg)) {
                out.add(new SearchIndex.Result(item.title, getString("tv".equals(item.type) ? R.string.search_series
                        : R.string.search_film, app), null, item.backdrop, true, 2, () -> openItem(pkg, item)));
            }
        }
        for (String pkg : channelApps) {
            String app = labels.get(pkg);
            if (app == null) continue;
            for (ShelfFeed.Item item : channelFeed.cached(pkg)) {
                out.add(new SearchIndex.Result(item.title, getString(R.string.search_video, app), null, item.backdrop, true, 3,
                        () -> openItem(pkg, item)));
            }
        }
        return out;
    }

    /** The apps the launcher ships channel lists for (assets/channels). */
    private List<String> liveChannelPackages() {
        List<String> out = new ArrayList<>();
        try {
            for (String f : getAssets().list("channels")) {
                if (f.endsWith(".json")) out.add(f.substring(0, f.length() - 5));
            }
        } catch (java.io.IOException ignored) {
        }
        return out;
    }

    /** The TV's voice input; what it hears is searched when Home is back. */
    /**
     * A spoken search: the field opens (or stays) and listens by itself, the words showing up in it as they are
     * heard, the microphone button pulsing with the voice; no screen of the TV's own. Without the microphone
     * permission it is asked for once; without a recognition service the TV's voice screen is used instead.
     */
    private void startVoice() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST);
            return;
        }
        if (!VoiceSearch.available(this)) {
            startVoiceScreen();
            return;
        }
        if (searching) {
            beginListening();
        } else {
            listenOnOpen = true;
            openSearch("");
        }
    }

    /**
     * What build-and-install grants over adb, asked for on a TV set up any other way: Android's own "Allow", once
     * per permission, the first times Home comes up. The TV's channel listings (the shelf's SmartTube posters),
     * the location (the temperature and the Wi-Fi name; Wi-Fi gives it, there is no GPS), Bluetooth (the
     * headphones button). A "no" is not asked again; the launcher works on without it.
     */
    private void askForTvListings() {
        handler.removeCallbacks(tvListingsAsk);
        if (!missingPermissions().isEmpty()) handler.postDelayed(tvListingsAsk, 1500);
    }

    private List<String> missingPermissions() {
        android.content.SharedPreferences st = getSharedPreferences("setup", MODE_PRIVATE);
        List<String> out = new ArrayList<>();
        boolean channels = false;
        for (String pkg : channelApps) if (installed(pkg)) channels = true;
        if (channels) out.add("android.permission.READ_TV_LISTINGS");
        if (weather != null || btnWifi.getVisibility() == View.VISIBLE) {
            out.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            out.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) out.add("android.permission.BLUETOOTH_CONNECT");
        List<String> missing = new ArrayList<>();
        for (String p : out) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !st.getBoolean("asked_" + p, false)) missing.add(p);
        }
        return missing;
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
    }

    /** The Now Playing card reads media sessions only as an allowed notification listener. */
    private boolean notificationAccess() {
        String on = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(getPackageName());
    }

    /** Any of the setup permissions still not granted, asked or not. */
    private boolean anyPermissionMissing() {
        for (String p : new String[]{"android.permission.READ_TV_LISTINGS", android.Manifest.permission.ACCESS_FINE_LOCATION}) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    /** Asked only over Home itself (not a popup, not another app); otherwise the next time Home comes up. */
    private final Runnable tvListingsAsk = () -> {
        if (isFinishing() || !hasWindowFocus() || openPopup != null || searching) return;
        List<String> missing = missingPermissions();
        if (missing.isEmpty()) return;
        android.content.SharedPreferences.Editor e = getSharedPreferences("setup", MODE_PRIVATE).edit();
        for (String p : missing) e.putBoolean("asked_" + p, true);
        e.apply();
        requestPermissions(missing.toArray(new String[0]), TV_LISTINGS_REQUEST);
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == TV_LISTINGS_REQUEST) {
            // Whatever was allowed shows at once: the posters, the temperature and Wi-Fi name, the headphones.
            stopFeed();
            if (lastFocused >= 0 && lastFocused < COLUMNS && adapter.appCount() > 0) showHero(lastFocused, lastDockTop);
            if (weather != null) refreshWeather();
            updateWifiLabel();
            AudioDevices.refresh(this);
            updateAudioButton();
        }
        if (requestCode == RECORD_AUDIO_REQUEST) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice();
            else if (!searching) openSearch("");   // typing, then
        }
    }

    private void beginListening() {
        if (!searching) return;
        View mic = findViewById(R.id.searchMic);
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.setText("");
        searchField.setHint(R.string.search_listening);
        searchField.requestFocus();
        mic.setActivated(true);
        boolean started = voice.start(new VoiceSearch.Listener() {
            @Override public void onHeard(String words, boolean done) {
                if (!searching) return;
                searchField.setText(words);
                searchField.setSelection(words.length());
                if (done) {
                    stopListening(false);
                    android.util.Log.i("TvHome", "heard: " + words);
                    if (ai.enabled()) {
                        runAiCommand(words);   // what was meant, not only the names that match
                    } else if (!openHeard(words)) {
                        // Nothing matched what was heard: the keyboard, to put it right.
                        if (imm != null) imm.showSoftInput(searchField, 0);
                    }
                }
            }
            @Override public void onLevel(float level) {
                if (!voice.listening()) return;
                float sc = 1.05f + 0.22f * level;
                mic.animate().cancel();
                mic.animate().scaleX(sc).scaleY(sc).setDuration(120).start();
            }
            @Override public void onFailed() {
                if (!searching) return;
                stopListening(false);
                searchField.setHint(R.string.search_not_heard);
                if (imm != null && searchField.length() == 0) imm.showSoftInput(searchField, 0);
            }
        });
        if (!started) {
            stopListening(false);
            startVoiceScreen();
        }
    }

    /** Listening over: the microphone rests; the hint goes back unless the field is being closed anyway. */
    private void stopListening(boolean closing) {
        voice.stop();
        View mic = findViewById(R.id.searchMic);
        mic.setActivated(false);
        mic.animate().cancel();
        mic.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        if (!closing) searchField.setHint(R.string.search_hint);
    }

    // ---- AI: spoken commands and picks -----------------------------------------------------------------

    /** Bumped when search closes or a newer command starts, so a late answer does nothing. */
    private int aiGen;

    /**
     * What was said (or typed and found nothing) sent to the AI with what this TV has: the installed apps, the
     * live channels, the titles on its shelves. Its one action is carried out here; with no answer the plain
     * search results stay, as without AI.
     */
    private void runAiCommand(String words) {
        int gen = ++aiGen;
        showSearchNote(getString(R.string.ai_thinking));
        JSONObjectBuilder b = new JSONObjectBuilder();
        Map<String, String> labels = installedLabels();
        for (Map.Entry<String, String> e : labels.entrySet()) b.add("apps", "pkg", e.getKey(), "label", e.getValue());
        for (String pkg : liveChannelPackages()) {
            if (!labels.containsKey(pkg)) continue;
            Set<String> never = liveChannels.hiddenIds(pkg);
            for (LiveChannels.Channel c : liveChannels.all(pkg)) {
                if (!never.contains(c.id)) b.add("channels", "id", c.id, "name", c.name, "app", pkg);
            }
        }
        for (Map.Entry<String, List<ShelfFeed.Item>> e : shelfTitles(labels).entrySet()) {
            for (ShelfFeed.Item item : e.getValue()) b.addTitle(item.title, e.getKey(), item.live);
        }
        ai.command(b.done(words), a -> {
            if (gen != aiGen || !searching) return;
            showSearchNote(null);
            if (a == null || !doAiAction(a)) {
                if (!(a == null ? openHeard(words) : focusSearchResult())) {
                    android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (imm != null) imm.showSoftInput(searchField, 0);
                }
            }
        });
    }

    /** Carries out the AI's action; false when it could not, so the plain results stay. */
    private boolean doAiAction(org.json.JSONObject a) {
        String action = a.optString("action"), app = a.optString("app"), query = a.optString("query").trim();
        android.util.Log.i("TvHome", "ai: " + action + " " + app + " " + query + " " + a.optString("title"));
        switch (action) {
            case "open_channel":
                for (LiveChannels.Channel c : liveChannels.all(app)) {
                    if (c.id.equals(a.optString("channel_id"))) {
                        closeSearch(false);
                        openChannel(app, c);
                        return true;
                    }
                }
                return false;
            case "open_title": {
                List<ShelfFeed.Item> items = shelfTitles(installedLabels()).get(app);
                if (items != null) {
                    for (ShelfFeed.Item item : items) {
                        if (item.title.equals(a.optString("title"))) {
                            closeSearch(false);
                            openItem(app, item);
                            return true;
                        }
                    }
                }
                return KIK_PKG.equals(app) && openInKik(a.optString("title"));
            }
            case "youtube_search": {
                String pkg = installed(app) ? app : installed("org.smarttube.stable") ? "org.smarttube.stable"
                        : "com.google.android.youtube.tv";
                if (!installed(pkg) || query.isEmpty()) return false;
                closeSearch(false);
                openUri("https://www.youtube.com/results?search_query=" + Uri.encode(query), pkg);
                return true;
            }
            case "spotify": {
                String pkg = installed(app) ? app : "com.spotify.tv.android";
                if (!installed(pkg)) return false;
                closeSearch(false);
                if (query.isEmpty()) launchApp(pkg);
                else openUri("spotify:search:" + Uri.encode(query), pkg);
                return true;
            }
            case "picks":
                showPicks(query.isEmpty() ? searchField.getText().toString() : query);
                return true;
            case "open_app":
                if (!installed(app)) return false;
                closeSearch(false);
                recents.app(app, installedLabels().getOrDefault(app, app));
                launchApp(app);
                return true;
            case "search":
                if (query.isEmpty()) return false;
                searchField.setText(query);
                searchField.setSelection(query.length());
                return openHeard(query);
            case "none":
                showSearchNote(a.optString("say"));
                return false;
            default:
                return false;
        }
    }

    /**
     * Films or series for a mood or company (AiClient.picks), as backdrop tiles in the search row; the focused one
     * says why in the line under the row, OK opens it (HDRezka finds it by name, else Netflix).
     */
    private void showPicks(String request) {
        int gen = ++aiGen;
        showSearchNote(getString(R.string.ai_picking));
        shelfChannels.removeAllViews();
        ai.picks(request, a -> {
            if (gen != aiGen || !searching) return;
            org.json.JSONArray picks = a == null ? null : a.optJSONArray("picks");
            if (picks == null || picks.length() == 0) {
                showSearchNote(getString(R.string.ai_no_picks));
                return;
            }
            showSearchNote(null);
            shelfChannels.removeAllViews();
            shelfChannelsScroll.scrollTo(0, 0);
            for (int i = 0; i < picks.length(); i++) {
                org.json.JSONObject p = picks.optJSONObject(i);
                if (p == null) continue;
                ShelfFeed.Item item = new ShelfFeed.Item(p.optString("title"), p.optString("backdrop"), null,
                        p.optString("type"), p.optLong("id"));
                String why = p.optString("why");
                View tile = searchTile(new SearchIndex.Result(item.title, "", null, item.backdrop, true, 2, () -> openPick(item)));
                View.OnFocusChangeListener was = tile.getOnFocusChangeListener();
                tile.setOnFocusChangeListener((v, focused) -> {
                    if (was != null) was.onFocusChange(v, focused);
                    if (focused) showSearchNote(why);
                });
                if (animations && !reducedMotion) {
                    tile.setAlpha(0f);
                    tile.setTranslationY(14 * density);
                    tile.animate().alpha(1f).translationY(0f).setDuration(320).setStartDelay(i * 45L).setInterpolator(EASE).start();
                }
                shelfChannels.addView(tile);
            }
            channelsPkg = "\u0000search";
            shelfChannelsScroll.setVisibility(View.VISIBLE);
            shelfChannelsScroll.setAlpha(1f);
            shelfChannelsScroll.post(() -> {
                View bar = (View) findViewById(R.id.topButtons).getParent();
                shelfChannelsScroll.setY(bar.getBottom() + ((View) bar.getParent()).getTop() + 16 * density);
                focusSearchResult();
            });
        });
    }

    private void openPick(ShelfFeed.Item item) {
        if (installed(KIK_PKG)) openItem(KIK_PKG, item);
        else if (installed("com.netflix.ninja")) openItem("com.netflix.ninja", item);
        else if (installed("org.smarttube.stable")) openUri("https://www.youtube.com/results?search_query="
                + Uri.encode(item.title + " trailer"), "org.smarttube.stable");
    }

    /** The line under the search row: a hint while the AI works, why a pick fits; null hides it. */
    private void showSearchNote(String text) {
        if (text == null || text.isEmpty()) {
            searchNote.animate().cancel();
            searchNote.setVisibility(View.GONE);
            return;
        }
        searchNote.setText(text);
        if (searchNote.getVisibility() != View.VISIBLE) {
            searchNote.setVisibility(View.VISIBLE);
            searchNote.setAlpha(0f);
            searchNote.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start();
        }
        searchNote.post(() -> {
            View bar = (View) findViewById(R.id.topButtons).getParent();
            float rowTop = bar.getBottom() + ((View) bar.getParent()).getTop() + 16 * density;
            float rowBottom = rowTop + (shelfChannelsScroll.getVisibility() == View.VISIBLE ? shelfChannelsScroll.getHeight() : 0);
            searchNote.setY(rowBottom + 10 * density);
        });
    }

    /** Package to label of every app with a launcher entry. */
    private Map<String, String> installedLabels() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
                try {
                    out.put(pkg, Cards.cleanLabel(String.valueOf(info.loadLabel(pm)), pkg));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    /** What the shelves hold now, per installed app: TMDB lists and the apps' own channel programs. */
    private Map<String, List<ShelfFeed.Item>> shelfTitles(Map<String, String> labels) {
        Map<String, List<ShelfFeed.Item>> out = new java.util.LinkedHashMap<>();
        for (String pkg : channelApps) if (labels.containsKey(pkg)) out.put(pkg, new ArrayList<>(channelFeed.cached(pkg)));
        for (String pkg : feedApps) if (labels.containsKey(pkg)) out.put(pkg, new ArrayList<>(shelfFeed.cached(pkg)));
        if (labels.containsKey(KickFeed.PACKAGE)) out.put(KickFeed.PACKAGE, new ArrayList<>(kickFeed.cached()));
        return out;
    }

    /** The command request's JSON, built without a checked exception at every step. */
    private static final class JSONObjectBuilder {
        private final org.json.JSONObject o = new org.json.JSONObject();
        private int titles;

        void add(String list, String... kv) {
            try {
                org.json.JSONObject e = new org.json.JSONObject();
                for (int i = 0; i + 1 < kv.length; i += 2) e.put(kv[i], kv[i + 1]);
                org.json.JSONArray a = o.optJSONArray(list);
                if (a == null) o.put(list, a = new org.json.JSONArray());
                a.put(e);
            } catch (org.json.JSONException ignored) {
            }
        }

        void addTitle(String title, String app, boolean live) {
            if (titles++ >= 80) return;
            add("titles", "title", title, "app", app);
            if (live) {
                org.json.JSONArray a = o.optJSONArray("titles");
                try {
                    a.getJSONObject(a.length() - 1).put("live", true);
                } catch (org.json.JSONException ignored) {
                }
            }
        }

        org.json.JSONObject done(String text) {
            try {
                o.put("text", text).put("lang", AiClient.language());
            } catch (org.json.JSONException ignored) {
            }
            return o;
        }
    }

    /** The TV's own voice screen, for a TV without a recognition service the launcher can use directly. */
    private void startVoiceScreen() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint))
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Google TV's own voice input (the Google app's), made for the remote's microphone, rather than the chooser
        // between it and the speech-services module; any other TV gets whatever answers the intent.
        for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(i, 0)) {
            if (ri.activityInfo != null && "com.google.android.katniss".equals(ri.activityInfo.packageName)) {
                i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                break;
            }
        }
        try {
            startActivityForResult(i, VOICE_REQUEST);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.search_no_voice, Toast.LENGTH_SHORT).show();
            if (!searching) openSearch("");
        }
    }

    private void launchApp(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) open(i);
    }

    /** The Watch pill in or out with the shelf's title. */
    private void showShelfOpen(boolean show, boolean animate) {
        if (show && (channelsPkg != null || !shelfOpens())) show = false;
        if (show) {
            if (shelfOpen.getVisibility() != View.VISIBLE) {
                shelfOpen.setVisibility(View.VISIBLE);
                shelfOpen.setAlpha(0f);
            }
            if (shelfOpen.getAlpha() < 1f) {
                shelfOpen.animate().cancel();
                if (animate) shelfOpen.animate().alpha(1f).setDuration(400).start();
                else shelfOpen.setAlpha(1f);
            }
        } else if (shelfOpen.getVisibility() == View.VISIBLE) {
            boolean hadFocus = shelfOpen.hasFocus();
            shelfOpen.animate().cancel();
            if (animate) {
                shelfOpen.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                    if (shelfOpen.getAlpha() == 0f) shelfOpen.setVisibility(View.GONE);   // not when cancelled by a show
                }).start();
            } else {
                shelfOpen.setAlpha(0f);
                shelfOpen.setVisibility(View.GONE);
            }
            if (hadFocus) focusTile(lastFocused % COLUMNS);   // last, as in stopChannels
        }
    }

    /** Shows feedItems[feedIndex], crossfading picture and title, then schedules the next one. */
    private void showFeedItem() {
        handler.removeCallbacks(feedTick);
        if (feedPkg == null || feedItems == null || feedItems.isEmpty()) return;
        ShelfFeed.Item item = feedItems.get(feedIndex % feedItems.size());
        String pkg = feedPkg;
        int gen = feedGen;
        shelfFeed.image(item.backdrop, (backdrop, bitmap) -> {
            if (gen != feedGen || !pkg.equals(feedPkg)) return;
            // Paged on meanwhile (Left/Right on the pill): only the item now chosen may draw.
            if (feedItems == null || feedItems.isEmpty() || feedItems.get(feedIndex % feedItems.size()) != item) return;
            if (bitmap == null) {
                // This backdrop failed to load: move on to the next one later instead of stopping here.
                if (!shelfOpen.hasFocus()) handler.postDelayed(feedTick, 8000);
                return;
            }
            // Centre-cropped by itself, so it shares the banners' FIT_XY and a dissolve never changes its shape.
            Drawable art = new CropDrawable(new BitmapDrawable(getResources(), bitmap));
            heroArt.animate().cancel();
            heroFade.animate().cancel();
            shelfTitle.animate().cancel();
            heroFade.setAlpha(1f);
            boolean animate = animations && !reducedMotion && heroArt.getAlpha() > 0f;
            Runnable title = () -> {
                String tidy = ShelfText.clean(item.title);
                boolean song = ShelfFeed.music(pkg);
                shelfTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ShelfText.size(tidy));
                shelfTitle.setText(tidy);
                int label = shelfLabel(pkg, item);
                if (label != 0) shelfOpen.setText(label);
                if (song) showAsSong(tidy);
                else playerPill(false);
                // Netflix: whether the title can be opened is known once its Netflix id is looked up (once per title).
                if (NETFLIX_PKG.equals(pkg) && item.id > 0 && item.open == null
                        && shelfFeed.knownNetflixId(item.type, item.id) == null) {
                    shelfFeed.netflixId(item.type, item.id, id -> {
                        if (feedItems != null && !feedItems.isEmpty() && pkg.equals(feedPkg)
                                && feedItems.get(feedIndex % feedItems.size()) == item) {
                            int found = shelfLabel(pkg, item);
                            if (found != 0) shelfOpen.setText(found);
                            positionShelfTitle();
                            showShelfOpen(found != 0, animations && !reducedMotion);
                        }
                    });
                }
                shelfLive.setVisibility(item.live ? View.VISIBLE : View.GONE);
                positionShelfTitle();
                shelfLive.animate().cancel();
                // Under a channel row the channels are what the shelf offers: the poster stays a backdrop, with no
                // title (it sat higher there than over the Watch pill, so moving along the dock made it jump).
                float shown = channelsPkg == null ? 1f : 0f;
                if (animate) {
                    shelfTitle.animate().alpha(shown).setDuration(400).start();
                    shelfLive.animate().alpha(shown).setDuration(400).start();
                } else {
                    shelfTitle.setAlpha(shown);
                    shelfLive.setAlpha(shown);
                }
                showShelfOpen(true, animate);
            };
            // Whatever is on the shelf now (the app's softened banner, or the previous backdrop, even one still
            // dissolving in) is the picture the new one dissolves from.
            Drawable from = heroArt.getDrawable();
            if (from instanceof android.graphics.drawable.TransitionDrawable) {
                from = ((android.graphics.drawable.TransitionDrawable) from).getDrawable(1);
            }
            if (animate && from != null) {
                // One backdrop dissolves into the next, as the top shelf does on Apple TV: both stay on screen,
                // nothing dips to black in between.
                android.graphics.drawable.TransitionDrawable cross = new android.graphics.drawable.TransitionDrawable(
                        new Drawable[]{from, art});
                cross.setCrossFadeEnabled(true);
                heroArt.setScaleType(ImageView.ScaleType.FIT_XY);
                heroArt.setImageDrawable(cross);
                heroArt.setAlpha(1f);
                cross.startTransition(700);
                heroArtShown = art;
                // Once the dissolve is over, hold the new picture alone, so the old bitmap can go.
                handler.postDelayed(() -> {
                    if (heroArtShown == art && heroArt.getDrawable() == cross) heroArt.setImageDrawable(art);
                }, 760);
                if (shelfTitle.getAlpha() > 0f) {
                    shelfLive.animate().alpha(0f).setDuration(220).start();
                    shelfTitle.animate().alpha(0f).setDuration(220).withEndAction(title).start();
                } else {
                    title.run();
                }
            } else {
                heroArt.setScaleType(ImageView.ScaleType.FIT_XY);
                heroArt.setImageDrawable(art);
                heroArt.setAlpha(1f);
                heroArtShown = art;
                title.run();
            }
            if (!shelfOpen.hasFocus()) handler.postDelayed(feedTick, 8000);   // standing still while choosing
            // Fetch the next backdrop now, so the swap does not wait on the network.
            ShelfFeed.Item next = feedItems.get((feedIndex + 1) % feedItems.size());
            shelfFeed.image(next.backdrop, (b, bmp) -> { });
        });
    }

    private void stopFeed() {
        handler.removeCallbacks(feedTick);
        handler.removeCallbacks(channelPosters);
        feedChannel = null;
        feedGen++;
        if (feedPkg == null) return;
        feedPkg = null;
        feedItems = null;
        shelfTitle.animate().cancel();
        shelfTitle.animate().alpha(0f).setDuration(160).start();
        shelfLive.animate().cancel();
        shelfLive.animate().alpha(0f).setDuration(160).start();
        showShelfOpen(false, true);
    }

    /** Something started or stopped playing: redraw the shelf if the dock has focus (it shows there). */
    private void onNowPlayingChanged() {
        Listened.record(this, nowPlaying.track());   // a song in a music app joins its shelf
        if (ambientScreen.getVisibility() == View.VISIBLE) {
            // A player between two tracks reports nothing for a moment: the full screen waits it out and leaves
            // only when nothing plays a few seconds later.
            handler.removeCallbacks(ambientGone);
            if (nowPlaying.track() == null) handler.postDelayed(ambientGone, 3000);
            else fillAmbient();
        }
        boolean onCard = npCard.hasFocus();
        if (onCard) {
            // Using the card's buttons: update it in place, or back to the dock once it closes.
            if (nowPlaying.track() != null) {
                showNowPlaying();
            } else {
                hideNowPlaying(animations);
                focusTile(lastFocused % COLUMNS);
            }
        } else if (lastHeroPosition >= 0 && lastHeroPosition < COLUMNS && heroShown && grid.hasFocus()) {
            showHero(lastHeroPosition, lastDockTop);
        } else if (nowPlaying.track() == null) {
            hideNowPlaying(animations);
        }
    }

    /**
     * The Now Playing card while a track plays: its cover sharp on the left and blurred across the shelf, title,
     * artist with the app, a progress bar ticking each second. False when nothing plays.
     */
    private boolean showNowPlaying() {
        NowPlaying.Track t = nowPlaying.track();
        if (t == null) {
            hideNowPlaying(animations);
            return false;
        }
        npHiding = false;
        stopFeed();
        stopChannels(false);
        shelfTitle.animate().cancel();
        shelfTitle.setAlpha(0f);
        shelfLive.setAlpha(0f);
        showShelfOpen(false, false);
        // Full screen only with a cover (showAmbient): no button that does nothing.
        findViewById(R.id.npExpand).setVisibility(t.cover != null ? View.VISIBLE : View.GONE);
        npTitle.setText(t.title);
        fitTitle(npTitle, 32f, 24f);
        npPlay.setCompoundDrawablesRelativeWithIntrinsicBounds(t.playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play, 0, 0, 0);
        npArtist.setText(t.artist.isEmpty() ? t.app : t.artist + "  ·  " + t.app);
        if (t.cover != npCoverShown) {
            npCoverShown = t.cover;
            npCover.setImageBitmap(t.cover);
            // A song's square cover: behind the player a field of its colours, not the cover a second time (the
            // card shows it); a video's frame is softened as before.
            boolean square = t.cover != null && Math.abs(t.cover.getWidth() - t.cover.getHeight()) * 10 < t.cover.getWidth();
            npBackdrop = t.cover == null ? null : square
                    ? new BitmapDrawable(getResources(), ShelfFeed.glow(t.cover)) : NowPlaying.soft(this, t.cover);
        }
        npCover.setVisibility(t.cover != null ? View.VISIBLE : View.GONE);
        if (npBackdrop != null && heroArtShown != npBackdrop) {
            heroArt.animate().cancel();
            heroArt.setScaleType(ImageView.ScaleType.FIT_XY);
            heroArt.setImageDrawable(npBackdrop);
            heroArtShown = npBackdrop;
            heroFade.setAlpha(1f);
            if (animations && !reducedMotion) heroArt.animate().alpha(1f).setDuration(400).start();
            else heroArt.setAlpha(1f);
        }
        boolean arriving = npCard.getVisibility() != View.VISIBLE || npCard.getAlpha() < 1f;
        npCard.setVisibility(View.VISIBLE);
        npCard.post(() -> {
            float y = lastDockTop - 34 * density - npCard.getHeight();
            if (Math.abs(npCard.getY() - y) > 1) npCard.setY(y);
        });
        if (arriving) {
            npCard.animate().cancel();
            if (animations && !reducedMotion) {
                // Like the island on a phone: a small pill at the cover's end swells into the card with a
                // little give, its contents arriving as it opens.
                npCard.setPivotX(0f);
                npCard.setPivotY(npCard.getHeight() > 0 ? npCard.getHeight() / 2f : 60 * density);
                npCard.setScaleX(0.28f);
                npCard.setScaleY(0.55f);
                npCard.setAlpha(0f);
                npCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
            } else {
                npCard.setScaleX(1f);
                npCard.setScaleY(1f);
                npCard.setAlpha(1f);
            }
        }
        updateNowPlayingProgress();
        handler.removeCallbacks(npTick);
        handler.postDelayed(npTick, 1000);
        return true;
    }

    private void updateNowPlayingProgress() {
        NowPlaying.Track t = nowPlaying.track();
        if (t == null || t.duration <= 0) {
            npProgress.setVisibility(View.GONE);
            return;
        }
        npProgress.setVisibility(View.VISIBLE);
        npProgress.setProgress((int) Math.min(1000, nowPlaying.position() * 1000 / t.duration));
    }

    private void hideNowPlaying(boolean animate) {
        handler.removeCallbacks(npTick);
        if (npCard.getVisibility() != View.VISIBLE) return;
        boolean hadFocus = npCard.hasFocus();
        npHiding = true;
        npCard.animate().cancel();
        if (animate && !reducedMotion) {
            // Music over: the card draws back into a pill and is gone.
            npCard.setPivotX(0f);
            npCard.setPivotY(npCard.getHeight() / 2f);
            npCard.animate().alpha(0f).scaleX(0.28f).scaleY(0.55f).setDuration(340)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                    .withEndAction(() -> {
                        if (npCard.getAlpha() > 0f) return;   // cancelled by a new show
                        npCard.setVisibility(View.GONE);
                        npCard.setScaleX(1f);
                        npCard.setScaleY(1f);
                    }).start();
        } else if (animate) {
            npCard.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (npCard.getAlpha() == 0f) npCard.setVisibility(View.GONE);
            }).start();
        } else {
            npCard.setAlpha(0f);
            npCard.setVisibility(View.GONE);
        }
        if (hadFocus) focusTile(lastFocused % COLUMNS);   // never left on a card that is going away
    }

    private void hideShelfArt(boolean animate) {
        hideNowPlaying(animate);
        stopFeed();
        stopChannels(animate);
        if (heroArtShown == null) return;
        heroArtShown = null;
        heroArt.animate().cancel();
        heroFade.animate().cancel();
        if (animate) {
            heroArt.animate().alpha(0f).setDuration(reducedMotion ? 100 : 200).start();
            heroFade.animate().alpha(0f).setDuration(reducedMotion ? 100 : 200).start();
        } else {
            heroArt.setAlpha(0f);
            heroFade.setAlpha(0f);
        }
    }

    private void hideHero() {
        hideShelfArt(animations);
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long mem = memAvailableMb();
        if (mem >= 0) appendStat(line, getString(R.string.stats_memory, mem));
        String free = formatGb(Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    private long memAvailableMb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] t = line.split("\\s+");
                    return t.length >= 2 ? Long.parseLong(t[1]) / 1024 : -1;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? String.format(Locale.US, "%.0f GB", gb)
                : String.format(Locale.US, "%.1f GB", gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                // Dock row: the Now Playing buttons when the card is up, otherwise the top bar.
                if (searching) {   // search is open with focus left on the dock: back to its field
                    searchField.requestFocus();
                    return true;
                }
                if (npCard.getVisibility() == View.VISIBLE && npCard.getAlpha() > 0f && !npHiding) {
                    npPlay.requestFocus();
                    grid.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                }
                // A poster on the shelf: its Watch pill.
                if (shelfOpen.getVisibility() == View.VISIBLE && shelfOpen.getAlpha() > 0f && feedPkg != null
                        && channelsPkg == null) {
                    shelfOpen.requestFocus();
                    grid.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                }
                // The channel row: its first tile.
                if (channelsPkg != null && shelfChannels.getChildCount() > 0) {
                    shelfChannels.getChildAt(0).requestFocus();
                    grid.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                }
                return false;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onTileOpened(String pkg, String label) {
        recents.app(pkg, label);
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /** Hold OK on a tile: starts reorder mode with that tile already picked up, so the arrows move it at once. */
    @Override public boolean onTileLongPress(int position) {
        if (reordering || position >= adapter.movableCount()) return false;
        String key = adapter.keyAt(position);
        String pkg = adapter.pkgAt(position);
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        View anchor = vh != null ? vh.itemView.findViewById(R.id.card) : null;
        if (key == null || anchor == null) return false;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (240 * density));
        addSettingsMenuRow(rows, popup, R.string.reorder, () -> startSingleMove(key));
        boolean channelMode = liveChannels.available(pkg) && liveChannels.channelMode(pkg);
        if (liveChannels.available(pkg)) {
            addSettingsMenuRow(rows, popup, channelMode ? R.string.shelf_mode_posters : R.string.shelf_mode_channels, () -> {
                liveChannels.setChannelMode(pkg, !channelMode);
                stopChannels(false);
                stopFeed();
                refreshShelf(position);
            });
            if (channelMode) {
                addSettingsMenuRow(rows, popup, R.string.choose_channels, () -> openChannelPicker(pkg, position));
                boolean auto = liveChannels.automatic(pkg);
                addSettingsMenuRow(rows, popup, auto ? R.string.channels_auto_on : R.string.channels_auto_off, () -> {
                    liveChannels.setAutomatic(pkg, !auto);
                    stopChannels(false);
                    refreshShelf(position);
                });
            }
        }
        if (feedApps.contains(pkg) || channelApps.contains(pkg)) {   // posters show under a channel row too
            boolean off = postersOff.contains(pkg);
            addSettingsMenuRow(rows, popup, off ? R.string.shelf_posters_show : R.string.shelf_posters_hide, () -> {
                if (off) postersOff.remove(pkg);
                else postersOff.add(pkg);
                getSharedPreferences("shelf", MODE_PRIVATE).edit().putStringSet("posters_off", new HashSet<>(postersOff)).apply();
                if (off) stopFeed();   // so the carousel starts afresh on the next focus
                refreshShelf(position);
            });
        }
        addSettingsMenuRow(rows, popup, R.string.library_remove_shortcut, () -> hideFromHome(pkg));
        addSettingsMenuRow(rows, popup, R.string.library_app_info, () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (240 * density), panel.getMeasuredWidth()));
        // Under the tile, or over it when the tile sits low on the screen.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        boolean above = at[1] + anchor.getHeight() + panel.getMeasuredHeight() + 20 * density > root.getHeight();
        if (above) {
            popup.showAsDropDown(anchor, 0, -anchor.getHeight() - panel.getMeasuredHeight() - (int) (10 * density),
                    Gravity.START);
        } else {
            dropIn(popup, panel, anchor, Gravity.START);
        }
        rows.getChildAt(0).requestFocus();
        return true;
    }

    /** Move from the tile menu: pick that tile up; OK places it, Back puts it back, either way the mode ends. */
    private void startSingleMove(String key) {
        if (reordering || !beginReorder()) return;
        int pos = adapter.positionOf(key);
        if (pos < 0) {
            endReorder();
            return;
        }
        singleMove = true;
        focusTile(pos);
        pickUp(pos);
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** Hide from the tile menu: the app leaves Home (the App library at the end of the grid brings it back). */
    private void hideFromHome(String pkg) {
        if (pkg.isEmpty()) return;
        if (LibraryApps.save(this, pkg, false)) reloadApps(false);
        else Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_SHORT).show();
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstPill().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstPill().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        String festiveGreeting = festive ? festival.greetingText() : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today());
        // A sleep timer running: said after the greeting, so it is never forgotten.
        int left = SleepTimer.minutesLeft(this);
        if (left > 0) greeting.setText(greeting.getText() + "   ·   " + getString(R.string.sleep_in, left));
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        if (wifiIconOnly) {
            // The name goes to accessibility only; the icon says Wi-Fi or Ethernet, dimmed while offline.
            // With a VPN up the button widens just enough for the exit country's flag next to the icon.
            btnWifi.setContentDescription(text);
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            btnWifi.setAlpha(online ? 1f : 0.45f);
            ViewGroup.LayoutParams wlp = btnWifi.getLayoutParams();
            if (vpn) {
                requestVpnCountry();
                btnWifi.setText(vpnCountry.isEmpty() ? getString(R.string.vpn) : flagOf(vpnCountry));
                btnWifi.setCompoundDrawablePadding((int) (8 * density));
                btnWifi.setPaddingRelative((int) (12 * density), 0, (int) (14 * density), 0);
                wlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                vpnCountry = "";
                vpnCountryAt = 0;
                btnWifi.setText("");
                btnWifi.setCompoundDrawablePadding(0);
                btnWifi.setPaddingRelative((int) (12 * density), 0, (int) (12 * density), 0);
                wlp.width = (int) (46 * density);
            }
            btnWifi.setLayoutParams(wlp);
            return;
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        }
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        boolean show = any && showInputs;
        boolean hadFocus = btnInputs.hasFocus();
        btnInputs.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show && hadFocus) firstPill().requestFocus();
    }

    /** The leftmost visible pill of the top bar (Settings is always there). */
    private View firstPill() {
        for (View v : new View[]{btnSearch, btnInputs, btnWifi, btnAudio, btnReorder}) {
            if (v.getVisibility() == View.VISIBLE) return v;
        }
        return findViewById(R.id.btnSettings);
    }

    /** profile.jpg (or .png) in the launcher's files folder, cut to a circle; the person glyph stays without one. */
    private void loadProfilePhoto() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        for (String name : new String[]{"profile.jpg", "profile.png"}) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            while (Math.min(bounds.outWidth, bounds.outHeight) / (o.inSampleSize * 2) >= 160) o.inSampleSize *= 2;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) continue;
            int size = Math.min(src.getWidth(), src.getHeight());
            Bitmap round = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setTranslate(-(src.getWidth() - size) / 2f, -(src.getHeight() - size) / 2f);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            new Canvas(round).drawCircle(size / 2f, size / 2f, size / 2f, p);
            src.recycle();
            ((ImageView) btnProfile).setScaleType(ImageView.ScaleType.FIT_CENTER);
            ((ImageView) btnProfile).setImageBitmap(round);
            return;
        }
    }

    /**
     * Asks Cloudflare's public trace endpoint which country this connection exits from (its "loc=" line), at most
     * once a minute while a VPN is up, and redraws the network button with it.
     */
    private void requestVpnCountry() {
        long now = System.currentTimeMillis();
        if (vpnCountryBusy || now - vpnCountryAt < 60_000) return;
        vpnCountryBusy = true;
        vpnCountryAt = now;
        new Thread(() -> {
            String loc = "";
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("loc=")) loc = line.substring(4).trim().toUpperCase(Locale.ROOT);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            String found = loc.matches("[A-Z]{2}") ? loc : "";
            handler.post(() -> {
                vpnCountryBusy = false;
                if (!found.equals(vpnCountry)) {
                    vpnCountry = found;
                    updateWifiLabel();
                }
            });
        }, "vpn-country").start();
    }

    /** "DE" -> the German flag (two regional indicator symbols). */
    private static String flagOf(String code) {
        return new String(Character.toChars(0x1F1E6 + code.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + code.charAt(1) - 'A'));
    }

    /** The round headphones (or speaker) button next to Settings, only while such a device is connected. */
    private void updateAudioButton() {
        String name = AudioDevices.connected();
        boolean on = name != null;
        if (on) {
            btnAudio.setCompoundDrawablesRelativeWithIntrinsicBounds(AudioDevices.icon(AudioDevices.speaker()), 0, 0, 0);
            btnAudio.setContentDescription(name);
        }
        if (on == (btnAudio.getVisibility() == View.VISIBLE)) return;
        boolean hadFocus = btnAudio.hasFocus();
        btnAudio.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on && hadFocus) firstPill().requestFocus();   // never leave the remote with nothing focused
    }

    /**
     * The headphones button's panel: the connected device with its battery, then pairing and Settings. Google TV's own
     * Remotes and accessories page only opens for apps that may write secure settings, so the launcher shows its own.
     */
    private void openAccessories() {
        String name = AudioDevices.connected();
        if (name == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (300 * density));
        addInputRow(rows, popup, AudioDevices.icon(AudioDevices.speaker()), name, TvInputManager.INPUT_STATE_CONNECTED, () -> { });
        int level = AudioDevices.battery();
        if (level >= 0) {
            TextView status = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
            status.setText(getString(R.string.connected) + " · " + level + "%");
        }
        addSeparator(rows);
        addSettingsMenuRow(rows, popup, R.string.add_accessory, () -> open(new Intent("com.google.android.intent.action.CONNECT_INPUT")));
        addSettingsMenuRow(rows, popup, R.string.tv_settings, () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (300 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, btnAudio, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void refreshWeather() {
        if (weather != null) weather.refresh(this::showWeather);
    }

    /** A Weather reading ("code|temperature") as symbol and degrees after the clock; nothing when there is none. */
    private void showWeather(String reading) {
        int bar = reading.indexOf('|');
        boolean shown = bar > 0;
        if (shown) {
            try {
                ((ImageView) findViewById(R.id.topWeatherIcon))
                        .setImageResource(Weather.icon(Integer.parseInt(reading.substring(0, bar))));
                topTemp.setText(Integer.parseInt(reading.substring(bar + 1)) + "°");
            } catch (NumberFormatException e) {
                shown = false;
            }
        }
        int v = shown ? View.VISIBLE : View.GONE;
        topTemp.setVisibility(v);
        findViewById(R.id.topWeatherIcon).setVisibility(v);
        // The divider only separates two things: it shows when the clock is there too.
        findViewById(R.id.topDivider).setVisibility(shown && findViewById(R.id.topClock).getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            // SecurityException: some system screens only open for privileged apps; never take Home down for it.
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) {
                    label = custom + " \u00B7 " + label;
                }
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        String update = updater.readyVersion();
        if (update != null) addSettingsMenuRow(rows, popup, getString(R.string.update_to, update), updater::install);
        else updater.check(true);   // opening this menu is as good a moment as any to look for one
        // The essentials only: the TV's own settings first, as the gear's single press used to open them.
        addSettingsMenuRow(rows, popup, R.string.tv_settings, () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        // A TV set up without adb where something was not allowed: one row to where it can be turned on.
        if (!notificationAccess()) {
            addSettingsMenuRow(rows, popup, R.string.access_now_playing, () -> {
                Intent i = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                        .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                new ComponentName(this, NowPlayingListener.class).flattenToString());
                if (i.resolveActivity(getPackageManager()) == null) i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                if (i.resolveActivity(getPackageManager()) == null) i = appDetails();
                open(i);
            });
        } else if (anyPermissionMissing()) {
            addSettingsMenuRow(rows, popup, R.string.access_permissions, () -> open(appDetails()));
        }
        int left = SleepTimer.minutesLeft(this);
        addSettingsMenuRow(rows, popup, left > 0 ? getString(R.string.sleep_timer_left, left) : getString(R.string.sleep_timer),
                this::showSleepMenu);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void runSettingsAction(String what) {
        if (isFinishing() || isDestroyed()) return;
        switch (what) {
            case LauncherSettingsActivity.THEN_ADD: onLibraryRequested(); break;
            case LauncherSettingsActivity.THEN_REORDER: toggleReorder(); break;
            case LauncherSettingsActivity.THEN_MODULES: if (moduleCards) showModuleCards(); break;
            default: break;
        }
    }

    /** The sleep timer's choices (SleepTimer): in so many minutes the TV goes to standby. */
    private void showSleepMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        for (int minutes : new int[]{15, 30, 45, 60, 90, 120}) {
            addSettingsMenuRow(rows, popup, getString(R.string.sleep_minutes, minutes), () -> {
                if (!SleepService.running()) {
                    // Switched on again by the launcher itself where adb gave it the right (SelfSetup); the service
                    // binds within a moment, long before the timer ends. Otherwise straight to where it is turned on.
                    SelfSetup.ensure(this);
                    if (!SelfSetup.canWriteSettings(this)) {
                        Toast.makeText(this, R.string.sleep_service_off, Toast.LENGTH_LONG).show();
                        open(systemIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        return;
                    }
                }
                SleepTimer.set(this, minutes);
                updateGreeting();
                Toast.makeText(this, getString(R.string.sleep_set, minutes), Toast.LENGTH_SHORT).show();
            });
        }
        if (SleepTimer.endsAt(this) != 0) {
            addSeparator(rows);
            addSettingsMenuRow(rows, popup, R.string.sleep_off, () -> {
                SleepTimer.cancel(this);
                updateGreeting();
            });
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        addSettingsMenuRow(rows, popup, getString(title), action);
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, CharSequence title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
