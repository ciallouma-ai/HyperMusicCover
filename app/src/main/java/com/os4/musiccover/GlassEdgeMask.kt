// SPDX-License-Identifier: Apache-2.0
package com.os4.musiccover

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Masks the composed glass in local pixels before the lock screen scales or moves its view. */
internal class GlassEdgeMask {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        blendMode = BlendMode.DST_IN
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        content: () -> Unit,
    ) {
        if (width <= 1 || height <= 1 || right - left <= 2f || bottom - top <= 2f) {
            content()
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        try {
            content()
            // The native outline is retained as a safety clip. Put the antialiased edge just
            // inside it so its one-pixel hard fringe cannot reappear after a redraw or zoom.
            val inset = 1f
            val r = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset,
                r, r, paint)
        } finally {
            canvas.restoreToCount(layer)
        }
    }
}
