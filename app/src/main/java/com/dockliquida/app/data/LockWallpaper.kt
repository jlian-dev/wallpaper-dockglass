package com.dockliquida.app.data

import com.dockliquida.app.glass.GlassParams
import org.json.JSONObject

/** null imageId = reutiliza a foto principal, com enquadramento independente. */
data class LockWallpaper(
    val enabled: Boolean = true,
    val imageId: String? = null,
    val params: GlassParams = GlassParams()
)

internal fun LockWallpaper.toJson(): JSONObject = JSONObject()
    .put("enabled", enabled).put("imageId", imageId ?: JSONObject.NULL).put("params", params.toJson())

internal fun lockFromJson(json: JSONObject): LockWallpaper {
    val id = if (json.isNull("imageId")) null else json.getString("imageId")
    require(id == null || Regex("lock_[A-Za-z0-9_-]{1,35}").matches(id)) { "Identificador de bloqueio inválido." }
    val p = json.optJSONObject("params")?.let { glassFromJson(it) } ?: GlassParams()
    return LockWallpaper(json.optBoolean("enabled", true), id, p.copy(
        zoom = p.zoom.coerceIn(100, 400), panX = p.panX.coerceIn(-100, 100),
        panY = p.panY.coerceIn(-100, 100), wpBlur = p.wpBlur.coerceIn(0, 60)
    ))
}
