package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppleFlowMathTest {
    @Test public void expandedOrbitsAndDoubleSpeed() {
        float[] angles = new float[4];
        AppleFlowMath.advance(angles, 67L);
        assertEquals(2f * 0.003f * 67f / 33.333333f, angles[0], 0.000001f);
        assertEquals(2f * -0.008f * 67f / 33.333333f, angles[1], 0.000001f);
        assertEquals(568f * 0.65f, AppleFlowMath.layerBasis(256f, 568f), 0.0001f);
        assertEquals(0.4f * 256f, AppleFlowMath.centerX(1, 256f, 0f), 0.0001f);
        assertEquals(0.4f * 568f, AppleFlowMath.centerY(1, 256f, 568f, 0f), 0.0001f);
        assertEquals(0.5f * 256f, AppleFlowMath.centerX(2, 256f, 0f), 0.0001f);
        assertEquals(0.74f * 568f, AppleFlowMath.centerY(2, 256f, 568f, 0f), 0.0001f);
        assertEquals(0.55f * 256f, AppleFlowMath.centerX(3, 256f, 0f), 0.0001f);
        assertEquals(0.31f * 568f, AppleFlowMath.centerY(3, 256f, 568f, 0f), 0.0001f);
    }

    @Test public void twistAndBlurUseLogicalCoordinates() {
        float[] outer = AppleFlowMath.twist(900f, 0f, 0f, 0f, 900f);
        assertEquals(900f, outer[0], 0f);
        assertEquals(0f, outer[1], 0f);
        float[] inner = AppleFlowMath.twist(100f, 0f, 0f, 0f, 900f);
        assertTrue(inner[1] < 0f);
        assertEquals(14f * 256f / 400f, AppleFlowMath.blurStep(400f), 0.0001f);
        assertEquals(1.6f, AppleFlowMath.SATURATION, 0f);
        float sum = AppleFlowMath.HALF_KERNEL[7];
        for (int i = 0; i < 7; i++) sum += 2f * AppleFlowMath.HALF_KERNEL[i];
        assertEquals(1f, sum, 0.00001f);
    }
}
