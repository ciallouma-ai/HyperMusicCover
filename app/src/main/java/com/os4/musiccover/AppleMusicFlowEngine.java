package com.os4.musiccover;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import java.util.Arrays;

/** One hardware scene shared by the lock-screen wash and the OEM card overlays. */
public final class AppleMusicFlowEngine {
    private static final int TRANSITION_MS = 180;
    private static final int DITHER_TILE_SIZE = 256;
    private static Paint ditherPaint;
    private static boolean ditherUnavailable;
    private static final String TWIST =
            "uniform shader content;\n" +
            "uniform float2 center;\n" +
            "uniform float2 size;\n" +
            "uniform float radius;\n" +
            "half4 main(float2 p) {\n" +
            "  float2 q = p - center;\n" +
            "  float d = length(q);\n" +
            "  if (d < radius) {\n" +
            "    float k = (radius - d) / radius;\n" +
            "    float a = k * k * -3.25;\n" +
            "    q = float2(q.x * cos(a) - q.y * sin(a),\n" +
            "               q.x * sin(a) + q.y * cos(a));\n" +
            "  }\n" +
            "  return content.eval(clamp(q + center, float2(0.5), size - float2(0.5)));\n" +
            "}\n";
    private static final String SATURATE =
            "uniform shader content;\n" +
            "uniform float saturation;\n" +
            "half4 main(float2 p) {\n" +
            "  half4 c = content.eval(p);\n" +
            "  if (c.a <= 0.0) return c;\n" +
            "  float3 rgb = float3(c.rgb) / float(c.a);\n" +
            "  float gray = dot(rgb, float3(0.2125, 0.7154, 0.0721));\n" +
            "  rgb = mix(float3(gray), rgb, saturation);\n" +
            "  return half4(half3(rgb * float(c.a)), c.a);\n" +
            "}\n";

    static final class Frame {
        final RenderNode node;
        final int width, height;

        Frame(RenderNode node, int width, int height) {
            this.node = node;
            this.width = width;
            this.height = height;
        }
    }

    private Bitmap previous, current;
    private RenderNode scene;
    private Frame cached;
    private final float[] rotations = new float[4];
    private int workHeight;
    private float logicalWidth;
    private long transitionStart;
    private long lastFrame;

    public boolean isReady() { return current != null; }

    /** The caller can recycle its artwork immediately after this copy. */
    public void setCover(Bitmap art) {
        Bitmap owned = Bitmap.createBitmap(AppleFlowMath.ART_SIZE, AppleFlowMath.ART_SIZE,
                Bitmap.Config.ARGB_8888);
        new Canvas(owned).drawBitmap(art, null,
                new android.graphics.Rect(0, 0, AppleFlowMath.ART_SIZE, AppleFlowMath.ART_SIZE),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        previous = current;
        current = owned;
        transitionStart = android.os.SystemClock.uptimeMillis();
        lastFrame = 0L;
    }

    /** Freezes motion while hidden, without changing the artwork or the accumulated angle. */
    void pause() { lastFrame = 0L; }

    public void close() {
        if (scene != null) scene.discardDisplayList();
        scene = null;
        cached = null;
        previous = null;
        current = null;
    }

    Frame frame(long now, float widthPx, float heightPx, float density) {
        if (current == null || widthPx <= 0f || heightPx <= 0f || density <= 0f) return null;
        int desiredHeight = AppleFlowMath.workHeight(widthPx, heightPx);
        float desiredLogicalWidth = widthPx / density;
        if (scene == null || workHeight != desiredHeight || logicalWidth != desiredLogicalWidth) {
            rebuild(desiredHeight, desiredLogicalWidth);
            lastFrame = 0L;
        }
        if (cached != null && lastFrame > 0L && now - lastFrame < AppleFlowMath.FRAME_MS)
            return cached;
        if (lastFrame > 0L) AppleFlowMath.advance(rotations, now - lastFrame);
        lastFrame = now;
        record(now);
        cached = new Frame(scene, AppleFlowMath.WORK_WIDTH, workHeight);
        return cached;
    }

    private void rebuild(int height, float logical) {
        if (scene != null) scene.discardDisplayList();
        workHeight = height;
        logicalWidth = logical;
        scene = new RenderNode("HMC Apple Music flow");
        scene.setPosition(0, 0, AppleFlowMath.WORK_WIDTH, workHeight);

        RuntimeShader twist = new RuntimeShader(TWIST);
        twist.setFloatUniform("center", AppleFlowMath.WORK_WIDTH * 0.5f, workHeight * 0.5f);
        twist.setFloatUniform("size", AppleFlowMath.WORK_WIDTH, workHeight);
        twist.setFloatUniform("radius", AppleFlowMath.TWIST_RADIUS
                * AppleFlowMath.WORK_WIDTH / logicalWidth);
        RenderEffect chain = RenderEffect.createRuntimeShaderEffect(twist, "content");

        float step = AppleFlowMath.blurStep(logicalWidth);
        for (int axis = 0; axis < 2; axis++) {
            String code = blurShader(axis == 0);
            for (int pass = 0; pass < AppleFlowMath.BLUR_QUALITY; pass++) {
                RuntimeShader blur = new RuntimeShader(code);
                blur.setFloatUniform("size", AppleFlowMath.WORK_WIDTH, workHeight);
                blur.setFloatUniform("tapStep", step);
                chain = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(blur, "content"), chain);
            }
        }
        RuntimeShader saturate = new RuntimeShader(SATURATE);
        saturate.setFloatUniform("saturation", AppleFlowMath.SATURATION);
        chain = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(saturate, "content"), chain);
        scene.setRenderEffect(chain);
        cached = null;
    }

    /** Pixi 7.4.3's 15 taps, clamped to the album-filled viewport at its edges. */
    private static String blurShader(boolean horizontal) {
        StringBuilder code = new StringBuilder("uniform shader content;\n"
                + "uniform float2 size;\nuniform float tapStep;\n"
                + "half4 tap(float2 p) {\n"
                + "  return content.eval(clamp(p, float2(0.5), size - float2(0.5)));\n}\n"
                + "half4 main(float2 p) {\n  float4 color = float4(0.0);\n");
        for (int offset = -7; offset <= 7; offset++) {
            String weight = Float.toString(AppleFlowMath.HALF_KERNEL[7 - Math.abs(offset)]);
            String coord = horizontal
                    ? "float2(p.x + " + offset + ".0 * tapStep, p.y)"
                    : "float2(p.x, p.y + " + offset + ".0 * tapStep)";
            code.append("  color += float4(tap(").append(coord).append(")) * ")
                    .append(weight).append(";\n");
        }
        return code.append("  return half4(color);\n}\n").toString();
    }

    private void record(long now) {
        RecordingCanvas canvas = scene.beginRecording();
        try {
            float oldAlpha = previous == null ? 0f : Math.max(0f,
                    1f - (now - transitionStart) / (float) TRANSITION_MS);
            if (oldAlpha > 0f) compose(canvas, previous, oldAlpha, false);
            compose(canvas, current, 1f - oldAlpha, oldAlpha > 0f);
            if (oldAlpha == 0f) previous = null;
        } finally {
            scene.endRecording();
        }
    }

    private void compose(Canvas canvas, Bitmap art, float alpha, boolean additive) {
        if (art == null || alpha <= 0f) return;
        Paint layer = new Paint();
        layer.setAlpha(Math.round(alpha * 255f));
        if (additive) layer.setBlendMode(BlendMode.PLUS);
        int layerSave = canvas.saveLayer(0f, 0f, AppleFlowMath.WORK_WIDTH, workHeight, layer);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float width = AppleFlowMath.WORK_WIDTH, height = workHeight;
        // The original width-based square left most of a portrait screen transparent.
        // A complete colour field keeps the broad wash visible above and below the cards.
        canvas.drawBitmap(art, null, new android.graphics.RectF(0f, 0f, width, height), paint);
        float basis = AppleFlowMath.layerBasis(width, height);
        for (int i = 0; i < 4; i++) {
            float side = basis * AppleFlowMath.SCALES[i];
            int save = canvas.save();
            canvas.translate(AppleFlowMath.centerX(i, width, rotations[i]),
                    AppleFlowMath.centerY(i, width, height, rotations[i]));
            canvas.rotate((float) Math.toDegrees(rotations[i]));
            canvas.drawBitmap(art, null,
                    new android.graphics.RectF(-side * 0.5f, -side * 0.5f,
                            side * 0.5f, side * 0.5f), paint);
            canvas.restoreToCount(save);
        }
        canvas.restoreToCount(layerSave);
    }

    static void draw(Canvas canvas, Frame frame, float width, float height) {
        if (frame == null || !canvas.isHardwareAccelerated()) return;
        int save = canvas.save();
        canvas.scale(width / frame.width, height / frame.height);
        canvas.drawRenderNode(frame.node);
        canvas.restoreToCount(save);
    }

    /** Adds roughly one RGB level of zero-mean noise after the 256px scene is enlarged. */
    static void drawDither(Canvas canvas, float width, float height) {
        if (!canvas.isHardwareAccelerated() || ditherUnavailable) return;
        try {
            if (ditherPaint == null) ditherPaint = createDitherPaint();
            canvas.drawRect(0f, 0f, width, height, ditherPaint);
        } catch (Throwable ignored) {
            // Grain must never make the underlying album flow unavailable.
            ditherPaint = null;
            ditherUnavailable = true;
        }
    }

    private static Paint createDitherPaint() {
        int[] pixels = new int[DITHER_TILE_SIZE * DITHER_TILE_SIZE];
        Arrays.fill(pixels, 0, pixels.length / 2, 0xffffffff);
        Arrays.fill(pixels, pixels.length / 2, pixels.length, 0xff000000);
        int state = 0x51ed270b;
        for (int i = pixels.length - 1; i > 0; i--) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            int j = (state & 0x7fffffff) % (i + 1);
            int color = pixels[i];
            pixels[i] = pixels[j];
            pixels[j] = color;
        }
        Bitmap tile = Bitmap.createBitmap(DITHER_TILE_SIZE, DITHER_TILE_SIZE,
                Bitmap.Config.ARGB_8888);
        tile.setPixels(pixels, 0, DITHER_TILE_SIZE, 0, 0,
                DITHER_TILE_SIZE, DITHER_TILE_SIZE);
        Paint paint = new Paint();
        paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        paint.setBlendMode(BlendMode.OVERLAY);
        paint.setAlpha(3);
        return paint;
    }

    /** Settings preview uses the phone's viewport geometry, then scales it into its thumbnail. */
    public boolean drawPreview(Canvas canvas, float viewportWidth, float viewportHeight,
                               float density, float targetWidth, float targetHeight) {
        if (!canvas.isHardwareAccelerated()) return false;
        Frame currentFrame = frame(android.os.SystemClock.uptimeMillis(),
                viewportWidth, viewportHeight, density);
        if (currentFrame == null) return false;
        draw(canvas, currentFrame, targetWidth, targetHeight);
        drawDither(canvas, targetWidth, targetHeight);
        return true;
    }

    /** map converts a card-local pixel to the full-screen flow's physical pixel. */
    static void drawMapped(Canvas canvas, Frame frame, float[] map, float flowWidth,
                           float flowHeight, int width, int height, float radius,
                           float alpha, float shade) {
        if (frame == null || map == null || !canvas.isHardwareAccelerated()) return;
        Matrix localToFlow = new Matrix();
        localToFlow.setValues(new float[] {map[0], map[1], map[4],
                map[2], map[3], map[5], 0f, 0f, 1f});
        Matrix flowToLocal = new Matrix();
        if (!localToFlow.invert(flowToLocal)) return;

        Paint layer = new Paint();
        layer.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
        if (shade > 0f) {
            float light = 1f - Math.max(0f, Math.min(1f, shade));
            layer.setColorFilter(new ColorMatrixColorFilter(new float[] {
                    light, 0f, 0f, 0f, 0f,
                    0f, light, 0f, 0f, 0f,
                    0f, 0f, light, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
            }));
        }
        int save = canvas.saveLayer(0f, 0f, width, height, layer);
        Path clip = new Path();
        clip.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        int mapped = canvas.save();
        canvas.concat(flowToLocal);
        draw(canvas, frame, flowWidth, flowHeight);
        canvas.restoreToCount(mapped);
        drawDither(canvas, width, height);
        canvas.restoreToCount(save);
    }
}
