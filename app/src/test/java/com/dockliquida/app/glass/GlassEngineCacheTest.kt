package com.dockliquida.app.glass

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassEngineCacheTest {
    private val width = 180
    private val height = 320
    private val base = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        (255 shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or ((x + y) and 255)
    }

    private fun render(engine: GlassEngine, params: GlassParams): IntArray {
        val background = engine.buildBackground(base, width, height, params.blur.toDouble(), Region(0, 0, width, height))
        return base.copyOf().also { engine.renderDock(it, width, height, params, background) }
    }

    @Test
    fun cachedShadowMatchesFreshRenderAfterMovingAndChangingShape() {
        val engine = GlassEngine()
        val initial = GlassParams.initialFor(width, height)
        val variants = listOf(
            initial,
            initial.copy(x = 6, y = 130),
            initial.copy(x = -45, y = -6),
            initial.copy(w = 120, h = 48, r = 12),
            initial.copy(w = 120, h = 48, r = 22),
            initial.copy(blur = 8, angle = 45, shine = 90),
            initial.scaled(0.55),
            initial,
            initial.scaled(0.55),
            initial
        )
        for (params in variants) {
            assertArrayEquals("O cache alterou os pixels: $params", render(GlassEngine(), params), render(engine, params))
        }
    }

    @Test
    fun movingDockDoesNotLeaveShadowAtOldPosition() {
        val engine = GlassEngine()
        val first = GlassParams.initialFor(width, height)
        val original = render(engine, first)
        val moved = first.copy(y = 120, x = -20)
        val actual = render(engine, moved)
        assertTrue("Mover a Dock deveria mudar os pixels", !original.contentEquals(actual))
        assertArrayEquals(render(GlassEngine(), moved), actual)
        assertArrayEquals(original, render(engine, first))
    }
}
