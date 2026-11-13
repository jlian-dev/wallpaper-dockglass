package com.dockliquida.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dockliquida.app.data.OutMode
import com.dockliquida.app.data.Prefs
import com.dockliquida.app.data.Store
import com.dockliquida.app.data.Wall
import com.dockliquida.app.data.glassFromJson
import com.dockliquida.app.data.toJson
import org.json.JSONObject
import com.dockliquida.app.glass.GlassParams
import com.dockliquida.app.glass.GlassStyle
import com.dockliquida.app.render.ImageIo
import com.dockliquida.app.render.LoadedImage
import com.dockliquida.app.render.PreviewPipeline
import com.dockliquida.app.render.WallpaperRenderer
import com.dockliquida.app.system.Actions
import com.dockliquida.app.system.DockOverlay
import com.dockliquida.app.system.OverlayResult
import com.dockliquida.app.system.WallTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * EditorVM.kt
 * --------------------------------------------------------------------------
 * O "cérebro" do editor (o que no app web era o script.js + o objeto state). Guarda os ajustes atuais,
 * pede a prévia ao motor de vidro em segundo plano e cuida de salvar/exportar/aplicar.
 *
 * Os campos com `by mutableStateOf` são "estado do Compose": quando mudam, a tela se redesenha sozinha.
 */
class EditorVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    val store = Store.get(app)
    val prefs = Prefs.get(app)

    // ---- estado visível pela interface ----
    var wall by mutableStateOf<Wall?>(null); private set
    var params by mutableStateOf(GlassParams()); private set
    var outMode by mutableStateOf(OutMode.AUTO); private set
    var outW by mutableIntStateOf(0); private set
    var outH by mutableIntStateOf(0); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var style by mutableStateOf<GlassStyle?>(null); private set      // estilo "puro" selecionado (some ao mexer num controle)
    var busy by mutableStateOf<String?>(null); private set           // texto do carregando (null = livre)
    var message by mutableStateOf<String?>(null)                      // aviso curto (a tela mostra um Toast e limpa)

    private var wallRef: Wall? = null                                  // cópia de trabalho (NÃO é estado: mudar isso não redesenha a tela toda)
    private var image: LoadedImage? = null
    private var imageCounter = 0L
    private val pipeline = PreviewPipeline()
    private var boxW = 0f; private var boxH = 0f                      // tamanho da área da prévia na tela (px)

    // ---- renderização em segundo plano: só o pedido MAIS RECENTE importa (CONFLATED) ----
    private class Req(val img: LoadedImage, val outW: Int, val outH: Int, val p: GlassParams, val factor: Double)
    private val requests = Channel<Req>(Channel.CONFLATED)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (r in requests) {
                val bmp = pipeline.render(r.img, r.outW, r.outH, r.p, r.factor)
                withContext(Dispatchers.Main) { preview = bmp }
            }
        }
    }

    // =====================================================================  abrir / fechar
    fun open(id: String) {
        if (wall?.id == id) { requestRender(); return }               // já está aberto (ex.: voltou do overlay)
        val w = store.wall(id) ?: return
        wall = w; wallRef = w; preview = null; image = null; style = null
        params = prefs.dockFor(w); outMode = w.outMode; outW = w.outW; outH = w.outH
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageIo.decodeMaxSide(store.file(id), 2560) }   // 2560 px: sobra para a prévia
            if (bmp == null) { message = "Não foi possível abrir a imagem."; return@launch }
            image = LoadedImage(bmp, w.origW, w.origH, ++imageCounter)
            requestRender()
        }
    }

    /** Grava tudo e marca este wallpaper como o "último aberto" (usado na Home). */
    fun close() {
        saveJob?.cancel()
        saveNow()
        wall?.let { prefs.setLastWall(it.id) }
        wall = null; wallRef = null; image = null; preview = null                   // (o Bitmap é liberado pelo coletor de lixo: reciclar à mão poderia derrubar uma renderização em andamento)
    }

    // =====================================================================  salvar (com atraso, como o saveSoon do app web)
    private var saveJob: Job? = null
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(400); saveNow() }      // 400 ms depois da última mudança
    }
    private fun saveNow() {
        val w = wallRef ?: return
        val updated = w.copy(dock = params, outMode = outMode, outW = outW, outH = outH)
        wallRef = updated                                              // (antes isto mudava o estado e recompunha o painel inteiro a cada salvamento)
        store.update(updated)
        prefs.saveLastDock(params, outW, outH)                        // "reutilizar as configurações anteriores"
    }

    // =====================================================================  prévia
    fun setBox(w: Float, h: Float) { if (w != boxW || h != boxH) { boxW = w; boxH = h; requestRender() } }

    private var settleJob: Job? = null
    var shownFactor = 1.0; private set                                   // escala da última prévia em qualidade normal (0–1)

    /** Quantas vezes a foto original está AMPLIADA na saída (>1 = pode perder nitidez). */
    fun upscale(): Double {
        val w = wallRef ?: return 0.0
        if (outW <= 0) return 0.0
        return WallpaperRenderer.coverScale(w.origW, w.origH, outW, outH, params)
    }

    /**
     * Pede uma prévia. `draft = true` (durante arrastos e sliders) desenha em ~55% da resolução, que custa
     * cerca de 1/3 do tempo; 220 ms depois da última mudança, redesenha em qualidade normal.
     */
    fun requestRender(draft: Boolean = false) {
        val img = image ?: return
        if (boxW <= 0f || outW <= 0) return
        var f = pipeline.factorFor(outW, outH, boxW, boxH, prefs.quality.value)
        if (!draft) shownFactor = f                                  // fator da prévia "de verdade" (mostrado na legenda)
        if (draft) {
            f = (f * 0.55).coerceAtLeast(0.1)
            settleJob?.cancel()
            settleJob = viewModelScope.launch { delay(220); requestRender(false) }
        }
        requests.trySend(Req(img, outW, outH, params, f))
    }

    /** Geometria da imagem dentro da caixa (para converter toques em pixels de saída): escala, deslocamento X e Y. */
    class Geometry(val scale: Float, val ox: Float, val oy: Float)
    fun geometry(): Geometry {
        if (outW <= 0 || outH <= 0) return Geometry(1f, 0f, 0f)
        val s = min(boxW / outW, boxH / outH)
        return Geometry(s, (boxW - outW * s) / 2f, (boxH - outH * s) / 2f)
    }

    // =====================================================================  alterações
    /** Aplica uma mudança nos parâmetros, redesenha e agenda o salvamento. */
    fun update(clearStyle: Boolean = true, f: (GlassParams) -> GlassParams) {
        params = f(params)
        if (clearStyle) style = null
        requestRender(draft = true); scheduleSave()
    }

    fun applyStyle(s: GlassStyle) { update(clearStyle = false) { it.withStyle(s, outW) }; style = s }

    fun resetDock() {
        update { GlassParams.initialFor(outW, outH).copy(zoom = it.zoom, panX = it.panX, panY = it.panY, wpBlur = it.wpBlur) }
    }

    fun resetFraming() { update { it.copy(zoom = 100, panX = 0, panY = 0, wpBlur = 0) } }

    /** Troca o modo de tamanho. A dock é reescalada na mesma proporção para não "fugir". */
    fun setOutMode(mode: OutMode, customW: Int = outW, customH: Int = outH) {
        val w0 = wallRef ?: return
        val (nw, nh) = when (mode) {
            OutMode.AUTO -> w0.origW.coerceIn(64, 8192) to w0.origH.coerceIn(64, 8192)
            OutMode.DEVICE -> Actions.deviceSize(ctx).let { it.first.coerceIn(64, 8192) to it.second.coerceIn(64, 8192) }
            OutMode.CUSTOM -> customW.coerceIn(64, 8192) to customH.coerceIn(64, 8192)
        }
        if (nw != outW || nh != outH) {
            val fx = nw.toDouble() / outW; val fy = nh.toDouble() / outH
            params = params.resized(fx, fy)
            outW = nw; outH = nh
        }
        outMode = mode
        requestRender(); scheduleSave()
    }

    // =====================================================================  gestos na prévia
    private var remPanX = 0.0; private var remPanY = 0.0              // restos fracionários do pan (movimento suave)
    fun beginGesture() { remPanX = 0.0; remPanY = 0.0 }

    /** O toque começou em cima da dock? (com uma folga de 24 px para ficar fácil de pegar) */
    fun hitDock(xOut: Float, yOut: Float): Boolean {
        val p = params
        val left = outW / 2f + p.x - p.w / 2f
        val slop = 24f
        return xOut >= left - slop && xOut <= left + p.w + slop && yOut >= p.y - slop && yOut <= p.y + p.h + slop
    }

    fun dragDock(dxOut: Float, dyOut: Float) {
        update { it.copy(x = it.x + dxOut.roundToInt(), y = it.y + dyOut.roundToInt()) }
    }

    /** Arrasta o papel de parede (muda o enquadramento). Inverte a fórmula do drawCover: dx = (W-dw)/2 - pan*(dw-W)/2. */
    fun panWallpaper(dxOut: Float, dyOut: Float) {
        val w = wallRef ?: return
        val p = params
        val s = WallpaperRenderer.coverScale(w.origW, w.origH, outW, outH, p)
        val spanX = (w.origW * s - outW) / 2.0                         // folga horizontal (para cada lado)
        val spanY = (w.origH * s - outH) / 2.0
        var nx = p.panX.toDouble(); var ny = p.panY.toDouble()
        if (spanX > 0.5) { nx += remPanX - dxOut / spanX * 100.0; nx = nx.coerceIn(-100.0, 100.0) }
        if (spanY > 0.5) { ny += remPanY - dyOut / spanY * 100.0; ny = ny.coerceIn(-100.0, 100.0) }
        remPanX = nx - Math.round(nx); remPanY = ny - Math.round(ny)
        update { it.copy(panX = Math.round(nx).toInt(), panY = Math.round(ny).toInt()) }
    }

    fun zoomBy(z: Float) { update { it.copy(zoom = (it.zoom * z).roundToInt().coerceIn(100, 400)) } }

    // =====================================================================  trocar a foto de fundo
    /**
     * Troca a imagem deste wallpaper por outra escolhida no seletor, mantendo a dock, o álbum e o tamanho de saída.
     * O enquadramento (zoom/posição) volta ao padrão, porque a foto nova tem outras proporções.
     */
    fun replaceImage(uri: Uri) {
        val w = wallRef ?: return
        saveJob?.cancel()
        viewModelScope.launch {
            busy = "Trocando a foto…"
            try {
                val size = store.replaceFile(w.id, uri) ?: throw IllegalStateException("O arquivo escolhido não é uma imagem.")
                val fresh = store.wall(w.id) ?: w                              // versão com a miniatura/dimensões novas
                wallRef = fresh; wall = fresh
                params = params.copy(zoom = 100, panX = 0, panY = 0)
                if (outMode == OutMode.AUTO) setOutMode(OutMode.AUTO)          // "igual à imagem": recalcula o tamanho (e reescala a dock)
                val bmp = withContext(Dispatchers.IO) { ImageIo.decodeMaxSide(store.file(w.id), 2560) }
                    ?: throw IllegalStateException("Não foi possível abrir a nova imagem.")
                image = LoadedImage(bmp, size.first, size.second, ++imageCounter)
                saveNow()
                requestRender()
                message = "Foto trocada."
            } catch (e: Throwable) { message = e.message ?: "Falha ao trocar a foto." } finally { busy = null }
        }
    }

    // =====================================================================  alinhar sobre a tela inicial (overlay)
    fun startOverlay() {
        if (!DockOverlay.hasPermission(ctx)) {
            DockOverlay.requestPermission(ctx)
            message = "Ative \"Exibir sobre outros apps\", volte ao app e toque em Alinhar de novo."
            return
        }
        setOutMode(OutMode.DEVICE)                                     // alinha na resolução física (a da tela inicial real)
        saveJob?.cancel(); saveNow()
        val p = params
        DockOverlay.start(ctx, p.x, p.y, p.w, p.h, p.r, outW, outH)
    }

    fun applyOverlay(r: OverlayResult) {
        update { it.copy(x = r.x, y = r.y, w = r.w, h = r.h, r = r.r) }
        DockOverlay.consumeResult()
    }

    // =====================================================================  preset: salva e importa apenas a Dock
    /** Arquivo de preset, separado do backup .dckl e sem o wallpaper. */
    fun exportDock(uri: Uri) {
        if (wallRef == null) { message = "Abra um wallpaper antes de salvar a Dock."; return }
        val preset = JSONObject()
            .put("format", "dockliquida.dock")
            .put("version", 1)
            .put("outW", outW)
            .put("outH", outH)
            // O enquadramento pertence à foto, e não ao preset de Dock.
            .put("dock", params.copy(zoom = 100, panX = 0, panY = 0, wpBlur = 0).toJson())
            .toString(2)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(preset.toByteArray(Charsets.UTF_8))
                    } ?: error("Não foi possível abrir o arquivo de destino.")
                }
                message = "Dock salva! O wallpaper não foi incluído."
            } catch (e: Exception) { message = "Erro ao salvar Dock: ${e.message}" }
        }
    }

    fun importDock(uri: Uri) {
        if (wallRef == null) { message = "Abra um wallpaper antes de importar uma Dock."; return }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        reader.readText().also { require(it.length <= 64_000) { "Arquivo muito grande para um preset." } }
                    } ?: error("Não foi possível abrir o arquivo.")
                }
                val root = JSONObject(text)
                require(root.optString("format") == "dockliquida.dock" && root.optInt("version") == 1) {
                    "Este arquivo não é um preset de Dock compatível."
                }
                val sourceW = root.getInt("outW")
                val sourceH = root.getInt("outH")
                require(sourceW in 64..8192 && sourceH in 64..8192) { "Tamanho original inválido." }
                val saved = glassFromJson(root.getJSONObject("dock"))
                require(saved.w in 20..8192 && saved.h in 20..8192 && saved.r >= 0) { "Dimensões da Dock inválidas." }
                // Escala proporcional para diferentes resoluções e preserva a foto atual.
                val scaled = saved.resized(outW.toDouble() / sourceW, outH.toDouble() / sourceH)
                update { current -> scaled.copy(
                    zoom = current.zoom, panX = current.panX,
                    panY = current.panY, wpBlur = current.wpBlur
                ) }
                message = "Dock importada e aplicada ao wallpaper atual."
            } catch (e: Exception) { message = "Não foi possível importar: ${e.message}" }
        }
    }

    // =====================================================================  exportar / aplicar
    private fun hiresFactor(): Double = min(2.0, 4096.0 / max(outW, outH)).coerceAtLeast(1.0)   // 2× limitado a 4096 px no lado maior

    /** Gera o PNG em tamanho cheio (ou 2×) e salva em Imagens/Dock Líquida. */
    fun exportPng(hires: Boolean) {
        val w = wallRef ?: return
        saveJob?.cancel(); saveNow()
        viewModelScope.launch {
            busy = "Gerando imagem…"
            try {
                val bmp = withContext(Dispatchers.Default) {
                    WallpaperRenderer.renderFull(store.file(w.id), outW, outH, params, if (hires) hiresFactor() else 1.0)
                } ?: throw IllegalStateException("Não foi possível gerar a imagem.")
                val base = w.name.substringBeforeLast('.').take(40)
                val name = "DockLiquida_${base}_${System.currentTimeMillis() / 1000}.png"
                withContext(Dispatchers.IO) { Actions.saveToGallery(ctx, bmp, name) }
                bmp.recycle()
                message = "Salvo em Imagens › Dock Líquida (${(outW * (if (hires) hiresFactor() else 1.0)).roundToInt()} px de largura)"
            } catch (e: Throwable) { message = "Falha ao salvar: ${e.message}" } finally { busy = null }
        }
    }

    /** Aplica direto como papel de parede, sem baixar nem salvar. */
    fun applyWallpaper(target: WallTarget) {
        val w = wallRef ?: return
        saveJob?.cancel(); saveNow()
        viewModelScope.launch {
            busy = "Aplicando wallpaper…"
            try {
                withContext(Dispatchers.Default) {
                    val bmp = WallpaperRenderer.renderFull(store.file(w.id), outW, outH, params)
                        ?: throw IllegalStateException("Não foi possível gerar a imagem.")
                    try { Actions.applyWallpaper(ctx, bmp, target) } finally { bmp.recycle() }
                }
                message = "Papel de parede aplicado!"
            } catch (e: Throwable) { message = "Não foi possível aplicar: ${e.message}" } finally { busy = null }
        }
    }
}
