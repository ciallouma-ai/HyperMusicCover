package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import dev.kawarp.KawarpEngine;

/** Lit card backdrop. The existing wallpaper remains visible until the first shader frame. */
final class CoverFlowRuntime extends View {
    private static CoverFlowRuntime sView;
    private static String sConfig = CoverFlowConfig.defaultJson();
    private static CoverFlowConfig.Settings sSettings = CoverFlowConfig.INSTANCE.fromJson(sConfig);
    private static boolean sPlaying;
    private KawarpEngine engine;
    private long waitingSince;
    private boolean failed;

    private CoverFlowRuntime(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    static String configJson() { return sConfig; }

    static void applyConfig(String json) {
        final String normalized = CoverFlowConfig.normalizedJson(json);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(() -> applyConfig(normalized));
            return;
        }
        CoverFlowConfig.Settings before = sSettings;
        CoverFlowConfig.Settings after = CoverFlowConfig.INSTANCE.fromJson(normalized);
        sConfig = normalized;
        sSettings = after;
        CoverFlowRuntime v = sView;
        if (v == null) return;
        if (v.engine != null) {
            v.configure(after);
            if (before.getBlur() != after.getBlur() || before.getPreset() != after.getPreset()) {
                Bitmap art = CoverCardLayer.currentFlowArt();
                if (art != null && !art.isRecycled()) publish(art);
            }
        }
        v.refreshView();
    }

    static void attach(ViewGroup host, View below) {
        if (host == null || below == null) return;
        CoverFlowRuntime v = sView;
        if (v == null || v.getContext() != host.getContext()) {
            if (v != null && v.getParent() instanceof ViewGroup)
                ((ViewGroup) v.getParent()).removeView(v);
            v = new CoverFlowRuntime(host.getContext());
            sView = v;
        }
        if (v.getParent() != host) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            host.addView(v, Math.max(0, host.indexOfChild(below)), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            int target = host.indexOfChild(below);
            if (target >= 0 && host.indexOfChild(v) > target) {
                host.removeView(v);
                host.addView(v, target, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        v.refreshView();
    }

    static void publish(Bitmap art) {
        CoverFlowRuntime v = sView;
        if (v == null || art == null || art.isRecycled()
                || !sSettings.getEnabled()) return;
        v.ensureEngine();
        if (v.engine != null && !v.failed) {
            // KawarpEngine.createScaledBitmap returns its input at exactly 128px. Give it a
            // 129px temporary so its queued worker owns a fresh 128px copy after this returns.
            Bitmap copy = null;
            try {
                copy = Bitmap.createBitmap(129, 129, Bitmap.Config.ARGB_8888);
                new Canvas(copy).drawBitmap(art, null, new Rect(0, 0, 129, 129),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                v.engine.setCover(copy);
                v.waitingSince = SystemClock.uptimeMillis();
                v.refreshView();
            } catch (Throwable t) {
                v.failed = true;
                v.setVisibility(GONE);
                Xp.log("[MCFlow] cover preparation failed: " + t);
            } finally {
                if (copy != null) copy.recycle();
            }
        }
    }

    static void playback(boolean playing) {
        sPlaying = playing;
        CoverFlowRuntime v = sView;
        if (v != null) {
            if (v.engine != null) v.engine.setPlaying(playing);
            v.refreshView();
        }
    }

    static void refresh() {
        CoverFlowRuntime v = sView;
        if (v != null) v.refreshView();
    }

    static void hide() {
        CoverFlowRuntime v = sView;
        if (v != null) v.setVisibility(GONE);
    }

    private boolean eligible() {
        return sSettings.getEnabled()
                && Main.sCoverCardStyle.mode == CoverCardStyle.CARD
                && Main.coverCardVisible() && Main.screenOnCached()
                && !LockLyrics.blurWanted() && !LockLyrics.wantsAttached();
    }

    private void ensureEngine() {
        if (engine != null || failed || !KawarpEngine.isSupported()) return;
        try {
            engine = new KawarpEngine();
            engine.setTransitionDuration(180);
            engine.setPlaybackReactive(true);
            engine.setPlaying(sPlaying);
            configure(sSettings);
        } catch (Throwable t) {
            failed = true;
            Xp.log("[MCFlow] shader unavailable: " + t);
        }
    }

    private void configure(CoverFlowConfig.Settings settings) {
        engine.setWarpIntensity(settings.getWarp());
        engine.setAnimationSpeed(settings.getSpeed());
        engine.setBlurPasses(settings.getBlur());
        if (settings.getPreset() == CoverFlowConfig.SOFT) {
            engine.setSaturation(1.1f);
            engine.setAutoDarken(0.55f);
        } else if (settings.getPreset() == CoverFlowConfig.VIVID) {
            engine.setSaturation(1.8f);
            engine.setAutoDarken(0.1f);
        } else {
            engine.setSaturation(1.5f);
            engine.setAutoDarken(0f);
        }
    }

    private void refreshView() {
        if (failed || !eligible()) {
            setVisibility(GONE);
            return;
        }
        ensureEngine();
        if (engine == null) return;
        if (waitingSince == 0L) {
            Bitmap art = CoverCardLayer.currentFlowArt();
            if (art != null && !art.isRecycled()) publish(art);
        }
        // Keep the wallpaper fallback exposed until the first blurred cover is ready.
        // Transparent while processing; a GONE view would never receive the polling draw.
        setVisibility(VISIBLE);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!eligible() || engine == null || failed) {
            setVisibility(GONE);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (engine.isProcessing() && waitingSince != 0L && now - waitingSince >= 5000L) {
            failed = true;
            setVisibility(GONE);
            Xp.log("[MCFlow] cover preparation timed out");
            return;
        }
        if (!engine.isReady()) {
            if (waitingSince != 0L && now - waitingSince < 5000L)
                postInvalidateDelayed(33L);
            return;
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        try {
            if (!engine.draw(canvas, getWidth(), getHeight())) return;
        } catch (Throwable t) {
            failed = true;
            setVisibility(GONE);
            Xp.log("[MCFlow] draw failed: " + t);
            return;
        }
        if (engine.isAnimating() || (engine.isProcessing()
                && now - waitingSince < 5000L))
            postInvalidateDelayed(33L);
    }
}
