package com.os4.musiccover;

/** Album-art flow geometry and tuned colour parameters in viewport logical pixels. */
final class AppleFlowMath {
    static final int WORK_WIDTH = 256;
    static final int ART_SIZE = 512;
    static final long FRAME_MS = 67L;
    static final int BLUR_QUALITY = 10;
    static final float BLUR_STRENGTH = 140f;
    static final float TWIST_ANGLE = -3.25f;
    static final float TWIST_RADIUS = 900f;
    static final float SATURATION = 1.6f;
    static final float SPEED = 2f;

    static final float[] SCALES = {1.25f, 0.80f, 0.50f, 0.25f};
    static final float[] ROTATION_PER_STEP = {0.003f, -0.008f, -0.006f, 0.004f};
    // Pixi 7.4.3's 15-tap Gaussian kernel, listed from the outermost tap to the centre.
    static final float[] HALF_KERNEL = {
            0.000489f, 0.002403f, 0.009246f, 0.027840f,
            0.065602f, 0.120999f, 0.174697f, 0.197448f
    };

    private AppleFlowMath() {}

    static int workHeight(float width, float height) {
        return Math.max(1, Math.round(WORK_WIDTH * height / width));
    }

    static float layerBasis(float width, float height) {
        return Math.max(width, height * 0.65f);
    }

    static float blurStep(float logicalWidth) {
        return BLUR_STRENGTH / BLUR_QUALITY * WORK_WIDTH / logicalWidth;
    }

    static void advance(float[] rotations, long deltaMs) {
        float step = SPEED * Math.max(0L, Math.min(100L, deltaMs)) / 33.333333f;
        for (int i = 0; i < 4; i++) rotations[i] += ROTATION_PER_STEP[i] * step;
    }

    private static float orbitPhase(int layer, float rotation) {
        return rotation * 0.75f + (layer == 3 ? 1.5f : 0.5f) * (float) Math.PI;
    }

    static float centerX(int layer, float width, float rotation) {
        if (layer == 1) return width / 2.5f;
        if (layer == 2 || layer == 3)
            return width * (layer == 3 ? 0.55f : 0.5f)
                    + width * 0.38f * (float) Math.cos(orbitPhase(layer, rotation));
        return width * 0.5f;
    }

    static float centerY(int layer, float width, float height, float rotation) {
        if (layer == 1) return height / 2.5f;
        if (layer == 2 || layer == 3)
            return height * (layer == 3 ? 0.55f : 0.5f)
                    + height * 0.24f * (float) Math.sin(orbitPhase(layer, rotation));
        return height * 0.5f;
    }

    static float[] twist(float x, float y, float centerX, float centerY, float radius) {
        float dx = x - centerX, dy = y - centerY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance >= radius) return new float[] {x, y};
        float falloff = (radius - distance) / radius;
        float angle = falloff * falloff * TWIST_ANGLE;
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        return new float[] {dx * cos - dy * sin + centerX,
                dx * sin + dy * cos + centerY};
    }
}
