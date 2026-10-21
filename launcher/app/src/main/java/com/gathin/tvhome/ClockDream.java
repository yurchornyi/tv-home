package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;

import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * No network, no images: cheap enough for a 1 GB TV.
 * While music plays (NowPlaying) it is the full-screen player instead: the cover turning slowly over a backdrop
 * flowing in the cover's colours, the clock small in a corner.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private NowPlaying nowPlaying;
    private View player;
    private AmbientView playerBg;
    private android.widget.ImageView cover;
    private android.widget.TextView title, artist;
    private android.graphics.Bitmap coverShown;
    private ValueAnimator turn;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        player = findViewById(R.id.dreamPlayer);
        playerBg = findViewById(R.id.dreamAmbientBg);
        cover = findViewById(R.id.dreamCover);
        title = findViewById(R.id.dreamTitle);
        artist = findViewById(R.id.dreamArtist);
        float density = getResources().getDisplayMetrics().density;
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 18 * density);
            }
        });
        cover.setClipToOutline(true);
        cover.setCameraDistance(9000 * density);
        nowPlaying = new NowPlaying(this, this::updatePlayer);
    }

    /** Music with a cover: the player over the clock; otherwise the clock. */
    private void updatePlayer() {
        NowPlaying.Track t = nowPlaying.track();
        boolean show = t != null && t.playing && t.cover != null;   // paused music is just the clock
        if (show) {
            title.setText(t.title);
            artist.setText(t.artist.isEmpty() ? t.app : t.artist + "  ·  " + t.app);
            if (t.cover != coverShown) {
                coverShown = t.cover;
                cover.setImageBitmap(t.cover);
                playerBg.setCover(t.cover);
            }
        }
        if (show == (player.getVisibility() == View.VISIBLE)) return;
        player.animate().cancel();
        if (show) {
            player.setVisibility(View.VISIBLE);
            playerBg.start();
            player.animate().alpha(1f).setDuration(900).start();
            float density = getResources().getDisplayMetrics().density;
            if (turn != null) turn.cancel();
            turn = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            turn.setDuration(14000);
            turn.setRepeatCount(ValueAnimator.INFINITE);
            turn.setInterpolator(new android.view.animation.LinearInterpolator());
            turn.addUpdateListener(a -> {
                float p = (float) a.getAnimatedValue();
                cover.setRotationY(11f * (float) Math.sin(p));
                cover.setRotationX(4f * (float) Math.sin(p * 2 + 1.1f));
                cover.setTranslationY(8 * density * (float) Math.sin(p * 1.5f + 0.4f));
            });
            turn.start();
        } else {
            if (turn != null) {
                turn.cancel();
                turn = null;
            }
            player.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                if (player.getAlpha() == 0f) {
                    player.setVisibility(View.GONE);
                    playerBg.stop();
                }
            }).start();
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
        nowPlaying.start();
        updatePlayer();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        nowPlaying.stop();
        if (turn != null) turn.cancel();
        playerBg.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
