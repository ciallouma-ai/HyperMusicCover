package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

/** Album-colour backdrop. The static wallpaper stays visible until its first hardware frame. */
final class CoverFlowRuntime extends View {
    private static final long READY_FADE_MS = 220L;
    private static CoverFlowRuntime sView;
    private static String sConfig = CoverFlowConfig.defaultJson();
    private static CoverFlowConfig.Settings sSettings = CoverFlowConfig.INSTANCE.fromJson(sConfig);

    private final Paint shadePaint = new Paint();
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            frameScheduled = false;
            updateFrame();
        }
    };
    private AppleMusicFlowEngine engine;
    private AppleMusicFlowEngine.Frame frameState;
    private long lastFrame;
    private float readiness;
    private boolean hadReady;
    private boolean hasCover;
    private boolean frameScheduled;
    private boolean sceneWasVisible;
    private boolean lyricsWereVisible;
    private boolean failed;
    private ImageView videoFallback;

    private CoverFlowRuntime(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
        setAlpha(0f);
    }

    static String configJson() { return sConfig; }

    static void applyConfig(String json) {
        final String normalized = CoverFlowConfig.normalizedJson(json);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Main.main().post(() -> applyConfig(normalized));
            return;
        }
        CoverFlowConfig.Settings after = CoverFlowConfig.INSTANCE.fromJson(normalized);
        sConfig = normalized;
        sSettings = after;
        CoverFlowRuntime v = sView;
        if (v == null) return;
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
        int anchor = host.indexOfChild(below);
        if (anchor < 0) return;
        int desired = MiniPlayerRuntime.flowLayerIndex(host, anchor);
        int current = host.indexOfChild(v);
        if (v.getParent() != host || current > desired) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            host.addView(v, Math.min(desired, host.getChildCount()), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Xp.log("[MCFlow] layer attached at " + host.indexOfChild(v)
                    + ", shortcut/keyguard anchor " + anchor);
        }
        v.refreshView();
    }

    static void publish(Bitmap art) {
        CoverFlowRuntime v = sView;
        if (v == null || art == null || art.isRecycled() || !sSettings.getEnabled()) return;
        v.ensureEngine();
        if (v.engine == null || v.failed) return;
        try {
            v.engine.setCover(art);
            v.hasCover = true;
            v.refreshView();
        } catch (Throwable t) {
            v.fail("cover preparation", t);
        }
    }

    static void refresh() {
        CoverFlowRuntime v = sView;
        if (v != null) v.refreshView();
    }

    static void hide() {
        CoverFlowRuntime v = sView;
        if (v != null) {
            v.removeCallbacks(v.frame);
            v.frameScheduled = false;
            v.setAlpha(0f);
            v.setVisibility(GONE);
            v.frameState = null;
            if (v.engine != null) v.engine.pause();
            v.restoreVideoFallback();
        }
        CoverFlowCards.clear();
    }

    private boolean sceneVisible() {
        return sSettings.getEnabled() && Main.sCoverCardStyle.mode == CoverCardStyle.CARD
                && Main.coverCardVisible();
    }

    private void ensureEngine() {
        if (engine != null || failed) return;
        try {
            engine = new AppleMusicFlowEngine();
        } catch (Throwable t) {
            fail("shader creation", t);
        }
    }

    private void refreshView() {
        if (failed) { hide(); return; }
        if (sceneVisible()) {
            ensureEngine();
            if (engine != null && !hasCover) {
                Bitmap art = CoverCardLayer.currentFlowArt();
                if (art != null && !art.isRecycled()) publish(art);
            }
        }
        updateFrame();
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        postDelayed(frame, AppleFlowMath.FRAME_MS);
    }

    private void updateFrame() {
        if (failed) { hide(); return; }
        boolean scene = sceneVisible();
        if (scene != sceneWasVisible) {
            sceneWasVisible = scene;
            Xp.log("[MCFlow] scene " + (scene ? "enter" : "exit")
                    + " at card=" + Main.cardProgress());
        }
        long now = SystemClock.uptimeMillis();
        boolean ready = engine != null && engine.isReady();
        if (ready && !hadReady) {
            hadReady = true;
            lastFrame = now;
            Xp.log("[MCFlow] first shader ready at card=" + Main.cardProgress());
        }
        long dt = lastFrame == 0L ? 0L : Math.min(50L, Math.max(0L, now - lastFrame));
        lastFrame = now;
        if (ready && readiness < 1f)
            readiness = Math.min(1f, readiness + dt / (float) READY_FADE_MS);
        float progress = scene ? Main.cardProgress() : 0f;
        float opacity = CoverFlowScene.opacity(progress, CoverCardLayer.flowLit(), readiness);
        try {
            frameState = ready && scene ? engine.frame(now, getWidth(), getHeight(),
                    getResources().getDisplayMetrics().density) : null;
            if (!scene && engine != null) engine.pause();
        } catch (Throwable t) {
            fail("Apple renderer frame", t);
            return;
        }
        if (getVisibility() != VISIBLE && (scene || opacity > 0f)) setVisibility(VISIBLE);
        if (getAlpha() != opacity) setAlpha(opacity);
        if (getVisibility() == VISIBLE && !scene && opacity == 0f) setVisibility(GONE);
        float lyricShow = LockLyrics.flowShow();
        boolean lyrics = lyricShow > 0.01f;
        if (lyrics != lyricsWereVisible) {
            lyricsWereVisible = lyrics;
            Xp.log("[MCFlow] lyric shade " + (lyrics ? "enter" : "exit"));
        }
        updateVideoFallback(opacity);
        CoverFlowCards.update(this, frameState,
                CoverFlowScene.cardOpacity(opacity, scene && Main.flowCardsEligible()),
                0.4f * lyricShow);
        if (opacity > 0f && frameState != null) postInvalidateOnAnimation();
        if (scene && (!ready || readiness < 1f || opacity > 0f)) scheduleFrame();
    }

    /** The video path has a static ImageView inside the keyguard, above the flow in the root. */
    private void updateVideoFallback(float flowOpacity) {
        ImageView current = Main.sVideoWallpaper ? Main.sCover : null;
        if (videoFallback != current) {
            restoreVideoFallback();
            videoFallback = current;
        }
        if (videoFallback != null) {
            float fallbackAlpha = 1f - flowOpacity;
            if (videoFallback.getTransitionAlpha() != fallbackAlpha)
                videoFallback.setTransitionAlpha(fallbackAlpha);
        }
    }

    private void restoreVideoFallback() {
        if (videoFallback != null) videoFallback.setTransitionAlpha(1f);
        videoFallback = null;
    }

    private void fail(String operation, Throwable t) {
        failed = true;
        Xp.log("[MCFlow] " + operation + (t == null ? "" : ": " + t));
        hide();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        frameScheduled = false;
        restoreVideoFallback();
        CoverFlowCards.clear();
        if (engine != null) engine.close();
        engine = null;
        hasCover = false;
        frameState = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // OEM software screenshot probes must not disturb the live hardware scene.
        if (!canvas.isHardwareAccelerated() || engine == null || frameState == null
                || getAlpha() <= 0f || failed) return;
        try {
            AppleMusicFlowEngine.draw(canvas, frameState, getWidth(), getHeight());
            int shade = CoverFlowScene.lyricShade(LockLyrics.flowShow());
            if (shade > 0) {
                shadePaint.setColor(0xff000000);
                shadePaint.setAlpha(shade);
                canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
            }
        } catch (Throwable t) {
            fail("draw failed", t);
        }
    }
}
