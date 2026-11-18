package com.dockliquida.app.system

import com.dockliquida.app.data.*
import com.dockliquida.app.glass.GlassParams
import org.junit.Assert.*
import org.junit.Test

class WallpaperPlanTest {
    private val home = GlassParams(zoom = 150, panX = 12)
    private val lock = GlassParams(zoom = 230, panX = -40, wpBlur = 8)
    private fun wall(config: LockWallpaper? = null) = Wall(
        id = "main", kind = Kind.FIXED, name = "foto", addedAt = 1L, albumId = null,
        origW = 1080, origH = 2400, outMode = OutMode.DEVICE,
        outW = 1080, outH = 2400, dock = home, lock = config
    )

    @Test fun differentPhotoAndFramingOnlyAffectLock() {
        val steps = wallpaperSteps(wall(LockWallpaper(imageId = "lock_other", params = lock)), home, WallTarget.BOTH, true)
        assertEquals(listOf(WallTarget.HOME, WallTarget.LOCK), steps.map { it.target })
        assertEquals(listOf("main", "lock_other"), steps.map { it.imageId })
        assertEquals(listOf(home, lock), steps.map { it.params })
        assertEquals(listOf(true, false), steps.map { it.withDock })
    }
    @Test fun samePhotoStillHasIndependentFraming() {
        val step = wallpaperSteps(wall(LockWallpaper(params = lock)), home, WallTarget.LOCK, true).single()
        assertEquals("main", step.imageId)
        assertEquals(lock, step.params)
        assertFalse(step.withDock)
    }
    @Test fun homeOnlyNeverAppliesOrUsesLockPhoto() {
        val step = wallpaperSteps(wall(LockWallpaper(imageId = "lock_other", params = lock)), home, WallTarget.HOME, true).single()
        assertEquals(WallTarget.HOME, step.target)
        assertEquals("main", step.imageId)
        assertEquals(home, step.params)
        assertTrue(step.withDock)
    }
    @Test fun disablingIndependentEditionUsesCurrentHomeFraming() {
        val step = wallpaperSteps(wall(LockWallpaper(enabled = false, imageId = "lock_other", params = lock)), home, WallTarget.LOCK, true).single()
        assertEquals("main", step.imageId)
        assertEquals(home, step.params)
        assertFalse(step.withDock)
    }
    @Test fun oldReleaseBehaviorIsPreservedWhenEditionIsDisabled() {
        val step = wallpaperSteps(wall(), home, WallTarget.BOTH, false).single()
        assertEquals(WallTarget.BOTH, step.target)
        assertTrue(step.withDock)
    }
    @Test fun independentEditionAlsoWorksOutsideDebugWhenEnabled() {
        val steps = wallpaperSteps(wall(LockWallpaper(params = lock)), home, WallTarget.BOTH, false)
        assertEquals(2, steps.size)
        assertFalse(steps.last().withDock)
    }
}
