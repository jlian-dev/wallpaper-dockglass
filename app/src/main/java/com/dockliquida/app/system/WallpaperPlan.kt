package com.dockliquida.app.system

import com.dockliquida.app.data.Wall
import com.dockliquida.app.glass.GlassParams

internal data class WallpaperStep(val target: WallTarget, val imageId: String, val params: GlassParams, val withDock: Boolean)

/** Decide as saídas sem renderizar, para testar seleção de foto, enquadramento e destino. */
internal fun wallpaperSteps(wall: Wall, homeParams: GlassParams, target: WallTarget, debug: Boolean): List<WallpaperStep> {
    val config = wall.lock?.takeIf { it.enabled }
    val split = debug || config != null
    val targets = if (split && target == WallTarget.BOTH) listOf(WallTarget.HOME, WallTarget.LOCK) else listOf(target)
    return targets.map { destination ->
        val isLock = split && destination == WallTarget.LOCK
        WallpaperStep(destination, if (isLock) config?.imageId ?: wall.id else wall.id,
            if (isLock) config?.params ?: homeParams else homeParams, !isLock)
    }
}
