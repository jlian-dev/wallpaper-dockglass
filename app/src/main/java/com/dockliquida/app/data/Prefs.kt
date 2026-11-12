package com.dockliquida.app.data

import android.content.Context
import android.content.SharedPreferences
import com.dockliquida.app.glass.GlassParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/*
 * Prefs.kt
 * --------------------------------------------------------------------------
 * Preferências do app (era o objeto PREF do script.js). Cada uma é um StateFlow: quando você muda
 * o valor, a interface que o observa se redesenha sozinha, e o valor é gravado no SharedPreferences.
 */
class Prefs private constructor(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("dockPrefs", Context.MODE_PRIVATE)

    private fun <T> flow(initial: T): MutableStateFlow<T> = MutableStateFlow(initial)

    private val _theme = flow(sp.getString("tema", "auto")!!)                 // auto | claro | escuro
    private val _quality = flow(sp.getFloat("qualidade", 1f))                  // 0,6 rápida · 1 normal · 1,6 nítida
    private val _individual = flow(sp.getBoolean("dockIndividual", true))     // cada wallpaper guarda a própria dock
    private val _glassFull = flow(sp.getBoolean("glassFull", false))
    private val _dynamicColors = flow(sp.getBoolean("dynamicColors", true))   // true = Material You; false = cinzas neutros          // barra inferior com vidro mais transparente
    private val _rotOn = flow(sp.getBoolean("trocaAtiva", false))
    private val _rotHours = flow(sp.getInt("trocaHoras", 24))
    private val _rotTarget = flow(sp.getString("trocaAlvo", "home")!!)         // home | lock | both
    private val _lastWall = flow(sp.getString("lastWall", null))               // wallpaper aberto por último (prévia da Home)

    val theme: StateFlow<String> = _theme.asStateFlow()
    val quality: StateFlow<Float> = _quality.asStateFlow()
    val individual: StateFlow<Boolean> = _individual.asStateFlow()
    val glassFull: StateFlow<Boolean> = _glassFull.asStateFlow()
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()
    val rotOn: StateFlow<Boolean> = _rotOn.asStateFlow()
    val rotHours: StateFlow<Int> = _rotHours.asStateFlow()
    val rotTarget: StateFlow<String> = _rotTarget.asStateFlow()
    val lastWall: StateFlow<String?> = _lastWall.asStateFlow()

    fun setTheme(v: String) { _theme.value = v; sp.edit().putString("tema", v).apply() }
    fun setQuality(v: Float) { _quality.value = v; sp.edit().putFloat("qualidade", v).apply() }
    fun setIndividual(v: Boolean) { _individual.value = v; sp.edit().putBoolean("dockIndividual", v).apply() }
    fun setGlassFull(v: Boolean) { _glassFull.value = v; sp.edit().putBoolean("glassFull", v).apply() }
    fun setDynamicColors(v: Boolean) { _dynamicColors.value = v; sp.edit().putBoolean("dynamicColors", v).apply() }
    fun setRotOn(v: Boolean) { _rotOn.value = v; sp.edit().putBoolean("trocaAtiva", v).apply() }
    fun setRotHours(v: Int) { _rotHours.value = v; sp.edit().putInt("trocaHoras", v).apply() }
    fun setRotTarget(v: String) { _rotTarget.value = v; sp.edit().putString("trocaAlvo", v).apply() }
    fun setLastWall(id: String?) { _lastWall.value = id; sp.edit().putString("lastWall", id).apply() }

    /**
     * Última dock usada + o tamanho de saída em que ela foi ajustada. Serve para (1) o modo "dock
     * compartilhada" e (2) começar um wallpaper novo já com os seus ajustes de sempre (reescalados).
     */
    fun lastDock(): Pair<GlassParams, Pair<Int, Int>>? {
        val s = sp.getString("lastDock", null) ?: return null
        return try {
            val o = JSONObject(s)
            glassFromJson(o.getJSONObject("dock")) to (o.getInt("outW") to o.getInt("outH"))
        } catch (e: Exception) { null }
    }
    fun saveLastDock(d: GlassParams, outW: Int, outH: Int) {
        sp.edit().putString("lastDock", JSONObject().put("dock", d.toJson()).put("outW", outW).put("outH", outH).toString()).apply()
    }

    /** Dock inicial para uma saída outW×outH: a última usada reescalada, ou a padrão se não houver. */
    fun initialDock(outW: Int, outH: Int): GlassParams {
        val (d, size) = lastDock() ?: return GlassParams.initialFor(outW, outH)
        val (lw, lh) = size
        return d.resized(outW.toDouble() / lw, outH.toDouble() / lh).copy(zoom = 100, panX = 0, panY = 0)
    }

    /**
     * A dock que vale para este wallpaper: a própria dele (modo "dock individual") ou a compartilhada
     * (a última usada, reescalada para o tamanho dele). O enquadramento (zoom/pan/desfoque do fundo)
     * é SEMPRE do wallpaper.
     */
    fun dockFor(w: Wall): GlassParams {
        if (_individual.value) return w.dock
        val (d, size) = lastDock() ?: return w.dock
        return d.resized(w.outW.toDouble() / size.first, w.outH.toDouble() / size.second)
            .copy(zoom = w.dock.zoom, panX = w.dock.panX, panY = w.dock.panY, wpBlur = w.dock.wpBlur)
    }

    // ---- backup (.dckl): todas as preferências vão e voltam como JSON, com o tipo de cada valor ----
    fun exportJson(): String {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val item = JSONObject()
            when (v) {
                is Boolean -> item.put("t", "b").put("v", v)
                is Int -> item.put("t", "i").put("v", v)
                is Long -> item.put("t", "l").put("v", v)
                is Float -> item.put("t", "f").put("v", v.toDouble())
                is String -> item.put("t", "s").put("v", v)
                else -> continue
            }
            o.put(k, item)
        }
        return o.toString()
    }

    fun importJson(json: String) {
        val o = JSONObject(json)
        val e = sp.edit().clear()
        for (k in o.keys()) {
            val x = o.getJSONObject(k)
            when (x.getString("t")) {
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "s" -> e.putString(k, x.getString("v"))
            }
        }
        e.commit()                                                  // commit (síncrono): o reload abaixo já enxerga os valores
        reload()
    }

    /** Relê tudo do SharedPreferences e atualiza os fluxos (a interface se redesenha sozinha). */
    fun reload() {
        _theme.value = sp.getString("tema", "auto")!!; _quality.value = sp.getFloat("qualidade", 1f)
        _individual.value = sp.getBoolean("dockIndividual", true); _glassFull.value = sp.getBoolean("glassFull", false)
        _dynamicColors.value = sp.getBoolean("dynamicColors", true)
        _rotOn.value = sp.getBoolean("trocaAtiva", false); _rotHours.value = sp.getInt("trocaHoras", 24)
        _rotTarget.value = sp.getString("trocaAlvo", "home")!!; _lastWall.value = sp.getString("lastWall", null)
    }

    fun resetAll() {
        sp.edit().clear().apply()
        _theme.value = "auto"; _quality.value = 1f; _individual.value = true; _glassFull.value = false
        _dynamicColors.value = true
        _rotOn.value = false; _rotHours.value = 24; _rotTarget.value = "home"; _lastWall.value = null
    }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }
}
