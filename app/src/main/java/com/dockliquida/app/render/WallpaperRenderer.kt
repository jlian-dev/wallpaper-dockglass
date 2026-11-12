package com.dockliquida.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.dockliquida.app.glass.Blur
import com.dockliquida.app.glass.GlassBackground
import com.dockliquida.app.glass.GlassEngine
import com.dockliquida.app.glass.GlassParams
import com.dockliquida.app.glass.Region
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * WallpaperRenderer.kt
 * --------------------------------------------------------------------------
 * Liga as peças: imagem do disco -> corte/zoom/desfoque ("cover") -> motor de vidro -> Bitmap pronto.
 * Substitui o drawCover(), render() e baixarPng() do script.js.
 */

/** Imagem já carregada na memória + o tamanho ORIGINAL dela (a versão decodificada pode ser menor). */
class LoadedImage(val bitmap: Bitmap, val origW: Int, val origH: Int, val id: Long)

object WallpaperRenderer {

    /** Escala "cover": cresce a imagem só até preencher o quadro (sem esticar), com zoom e folga para o desfoque. */
    fun coverScale(origW: Int, origH: Int, W: Int, H: Int, p: GlassParams): Double =
        max(W.toDouble() / origW, H.toDouble() / origH) *
            (p.zoom / 100.0 * (1 + 4.0 * p.wpBlur / min(W, H)))   // com desfoque amplia um pouco: as bordas não ficam transparentes

    /**
     * Desenha a imagem no modo "cobrir" dentro de um quadro W×H e devolve os pixels (ARGB).
     * Aplica zoom, enquadramento (pan) e o desfoque do papel de parede. (= drawCover do script.js)
     */
    fun buildBase(src: Bitmap, origW: Int, origH: Int, W: Int, H: Int, p: GlassParams): IntArray {
        val s = coverScale(origW, origH, W, H, p)
        val dw = origW * s; val dh = origH * s                       // tamanho da imagem já escalada
        val px = p.panX / 100.0; val py = p.panY / 100.0
        val dx = (W - dw) / 2 - px * (dw - W) / 2                    // 0 = centrada; ±100 = encosta na borda
        val dy = (H - dh) / 2 - py * (dh - H) / 2

        // Reduz pela metade enquanto a redução for maior que 2×: bilinear sozinho serrilha ao encolher demais.
        var cur = src
        while (dw / cur.width < 0.5 && cur.width > 2 && cur.height > 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }

        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)                                // fundo opaco: nenhum pixel transparente
        val m = Matrix().apply {
            postScale((dw / cur.width).toFloat(), (dh / cur.height).toFloat())
            postTranslate(dx.toFloat(), dy.toFloat())
        }
        canvas.drawBitmap(cur, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG))
        if (cur !== src) cur.recycle()

        val pixels = IntArray(W * H)
        out.getPixels(pixels, 0, W, 0, 0, W, H)
        out.recycle()
        return if (p.wpBlur > 0) Blur.gaussian(pixels, W, H, p.wpBlur.toDouble(), clampEdges = true) else pixels
    }

    /**
     * Gera a imagem FINAL (PNG/JPEG em tamanho cheio ou ampliado). Usada para exportar, aplicar como
     * wallpaper e na troca automática. Só desfoca a faixa de fundo que o vidro realmente precisa.
     * @param factor 1.0 = tamanho de saída; 2.0 = o dobro (alta resolução)
     */
    fun renderFull(file: File, outW: Int, outH: Int, params: GlassParams, factor: Double = 1.0, withDock: Boolean = true): Bitmap? {
        val W = (outW * factor).roundToInt(); val H = (outH * factor).roundToInt()
        val info = ImageIo.info(file)
        val p = if (factor == 1.0) params else params.scaled(factor)
        val scale = coverScale(info.width, info.height, W, H, p)      // com que escala o original será desenhado
        val src = ImageIo.decodeForScale(file, scale) ?: return null
        val base = buildBase(src, info.width, info.height, W, H, p)
        src.recycle()
        if (withDock) {
            val engine = GlassEngine()
            val region = engine.neededRegion(W, H, p)
            val bg = engine.buildBackground(base, W, H, p.blur.toDouble(), region)
            engine.renderDock(base, W, H, p, bg)                      // desenha direto sobre a base (poupa memória)
        }
        return Bitmap.createBitmap(base, W, H, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Pipeline da PRÉVIA do editor. Guarda em cache o fundo (recortado/desfocado) e o vidro desfocado,
 * então arrastar a dock só repete a parte barata. Cada cache tem uma "assinatura" (key): só é refeito
 * quando algo que o afeta muda — exatamente a ideia de baseKey/glassKey do script.js.
 */
class PreviewPipeline {
    private val engine = GlassEngine()
    private var baseKey = ""
    private var base: IntArray? = null
    private var glassKey = ""
    private var bg: GlassBackground? = null

    /** Largura/altura do quadro desenhado na última chamada (útil para converter toques em pixels de saída). */
    var lastW = 0; private set
    var lastH = 0; private set

    fun render(img: LoadedImage, outW: Int, outH: Int, params: GlassParams, factor: Double): Bitmap {
        val W = max(8, (outW * factor).roundToInt()); val H = max(8, (outH * factor).roundToInt())
        lastW = W; lastH = H
        val p = params.scaled(factor)                                  // medidas em px acompanham a escala da prévia
        val bKey = listOf(W, H, p.zoom, p.panX, p.panY, p.wpBlur, img.id).joinToString("|")
        if (bKey != baseKey || base == null) {
            base = WallpaperRenderer.buildBase(img.bitmap, img.origW, img.origH, W, H, p); baseKey = bKey
            glassKey = ""                                              // fundo mudou: o vidro precisa ser refeito
        }
        val gKey = bKey + "|" + p.blur
        if (gKey != glassKey || bg == null) {
            bg = engine.buildBackground(base!!, W, H, p.blur.toDouble(), Region(0, 0, W, H)); glassKey = gKey
        }
        val canvas = base!!.copyOf()                                   // base fica intacta para o próximo quadro
        engine.renderDock(canvas, W, H, p, bg!!)
        return Bitmap.createBitmap(canvas, W, H, Bitmap.Config.ARGB_8888)
    }

    /** Fator de escala da prévia: o suficiente para a tela, nunca maior que o tamanho real (limites 0,2–1). */
    fun factorFor(outW: Int, outH: Int, boxW: Float, boxH: Float, quality: Float): Double {
        if (boxW <= 0f || boxH <= 0f) return 0.4
        val s = min(boxW / outW, boxH / outH) * quality
        val cap = Math.sqrt(520_000.0 / (outW.toDouble() * outH))       // a prévia nunca passa de ~520 mil pixels (celulares são lentos)
        return min(s.toDouble(), cap).coerceIn(0.1, 1.0)
    }
}
