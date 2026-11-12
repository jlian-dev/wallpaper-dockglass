package com.dockliquida.app.glass

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * GlassEngine.kt
 * --------------------------------------------------------------------------
 * O CORAÇÃO do app: o efeito Liquid Glass. É uma tradução fiel, linha a linha, do
 * drawDock()/buildTable()/buildGlass() do script.js, seguindo o método do artigo
 * https://kube.io/blog/liquid-glass-css-svg/ (refração física com a lei de Snell).
 *
 * Kotlin puro: trabalha só com IntArray (pixels ARGB). Quem converte Bitmap <-> IntArray é
 * o WallpaperRenderer. Assim este arquivo roda igual no celular e num teste de computador.
 */

/** Retângulo de pixels (usado para recortar só a região útil da imagem). */
data class Region(val x0: Int, val y0: Int, val w: Int, val h: Int)

/**
 * Fundo desfocado que fica ATRÁS do vidro (o "GD" do script.js).
 * Guarda só uma região da imagem (para a exportação em alta resolução não desfocar a imagem inteira).
 */
class GlassBackground(val x0: Int, val y0: Int, val w: Int, val h: Int, val px: IntArray)

/** Referência a uma tabela de refração já calculada (para não recalcular a cada quadro). */
object Refraction {

    /** Curva "squircle": a suavidade de cantos que a Apple usa. x vai de 0 (borda) a 1 (fim do bisel). */
    private fun squircle(x: Double): Double = (1.0 - (1.0 - x).pow(4.0)).pow(0.25)

    /** Altura do vidro (0 a 1) em função da distância x à borda, conforme o perfil escolhido. */
    fun height(profile: BevelProfile, x: Double): Double = when (profile) {
        BevelProfile.SQUIRCLE -> squircle(x)
        BevelProfile.CIRCLE -> sqrt(1.0 - (1.0 - x) * (1.0 - x))        // cúpula esférica (transição mais dura)
        BevelProfile.CONCAVE -> 1.0 - squircle(x)                       // tigela: empurra a luz para fora
        BevelProfile.LIP -> {                                           // mistura convexo e côncavo (smootherstep)
            val t = x * x * x * (x * (x * 6 - 15) + 10)
            squircle(x) * (1 - t) + (1 - squircle(x)) * t
        }
    }

    /**
     * Simula 128 raios de luz que atravessam o vidro, do ponto da borda até o fim do bisel, e guarda
     * quanto cada raio se desvia lateralmente. Depois é só consultar a tabela por pixel.
     * Positivo = o fundo visto vem de mais para dentro.
     */
    fun buildTable(profile: BevelProfile, bevel: Double, thick: Double, ior: Double): DoubleArray {
        val bev = max(1.0, bevel)                       // evita divisão por zero
        val eta = 1.0 / ior                             // razão n1/n2 (ar -> vidro)
        val table = DoubleArray(128)
        for (i in 0 until 128) {
            val x = i / 127.0
            val a = max(0.0, x - 0.001); val b = min(1.0, x + 0.001)
            // inclinação da superfície (derivada numérica), convertida de unidades normalizadas para px
            val slope = (height(profile, b) - height(profile, a)) / (b - a) * thick / bev
            val len = hypot(slope, 1.0)
            val nx = -slope / len; val nz = 1.0 / len   // normal da superfície (vetor unitário)
            val cosi = nz                               // raio incidente vertical: cos = -(N·I)
            val k = 1.0 - eta * eta * (1.0 - cosi * cosi)
            val c = eta * cosi - sqrt(max(0.0, k))      // lei de Snell em forma vetorial
            val tx = c * nx; val tz = -eta + c * nz     // direção do raio refratado
            val z = thick * height(profile, x)          // altura do vidro neste ponto
            // desvio lateral = quanto o raio anda de lado até chegar ao fundo (altura z)
            table[i] = if (tz < -1e-6) tx * z / (-tz) else 0.0
        }
        return table
    }
}

/**
 * Cache da FORMA do vidro: para cada pixel da dock guarda a transparência, a direção da borda,
 * o desvio da refração e o brilho da borda. Só depende do formato (tamanho, curvatura, bisel,
 * espessura, índice, escala, perfil, luz) — não da posição nem do fundo. Por isso arrastar a dock
 * só refaz a leitura do fundo e a mistura de cores, o que é bem mais rápido.
 */
class GlassEngine {
    private var key = ""
    private var fw = 0
    private var fh = 0
    private var alpha = ByteArray(0)       // cobertura (0..255) — suaviza a serrilha da borda
    private var nxArr = FloatArray(0)      // componente X da normal externa da borda
    private var nyArr = FloatArray(0)      // componente Y da normal externa da borda
    private var disp = FloatArray(0)       // desvio da refração, em px
    private var hl0 = FloatArray(0)        // brilho da borda (antes de multiplicar por "shine")

    /** Quantos pixels além da dock é preciso ler do fundo (refração + aberração cromática). */
    fun requiredPad(p: GlassParams): Int {
        val bevel = min(p.bevel.toDouble(), p.h / 2.0)
        val t = Refraction.buildTable(p.profile, bevel, p.thick.toDouble(), p.ior.toDouble())
        var m = 0.0
        for (v in t) m = max(m, abs(v))
        return ceil(m * p.scale / 100.0 * (1.0 + p.chroma / 100.0)).toInt() + 3
    }

    /** Posição X (esquerda) da dock no quadro de largura `cw`. x = 0 significa "centralizada". */
    fun dockLeft(cw: Int, p: GlassParams): Int = jsRound(cw / 2.0 + p.x - p.w / 2.0)

    /**
     * Região do quadro que o vidro precisa enxergar: a dock + margem de refração + margem do desfoque.
     * Para a prévia usamos o quadro inteiro; para exportar, só esta faixa (muito mais leve na memória).
     */
    fun neededRegion(cw: Int, ch: Int, p: GlassParams): Region {
        val pad = requiredPad(p) + ceil(3.0 * p.blur).toInt() + 2
        val left = dockLeft(cw, p)
        val x0 = max(0, left - pad); val y0 = max(0, p.y - pad)
        val x1 = min(cw, left + p.w + pad); val y1 = min(ch, p.y + p.h + pad)
        return Region(x0, y0, max(1, x1 - x0), max(1, y1 - y0))
    }

    // ------------------------------------------------------------------------------------------
    // PASSO A: fundo desfocado atrás do vidro
    // ------------------------------------------------------------------------------------------
    /**
     * Fundo do vidro = imagem nítida por baixo (evita borda transparente) + a mesma imagem desfocada,
     * saturada (1,5×) e um pouco mais clara (1,06×) por cima. É o `blur() saturate(1.5) brightness(1.06)`
     * do script.js.
     */
    fun buildBackground(base: IntArray, bw: Int, bh: Int, blurPx: Double, region: Region): GlassBackground {
        // 1) recorta a região da imagem nítida
        val sharp = IntArray(region.w * region.h)
        for (y in 0 until region.h) {
            System.arraycopy(base, (region.y0 + y) * bw + region.x0, sharp, y * region.w, region.w)
        }
        // 2) desfoca (fora da imagem = transparente, como o filtro blur() do Canvas)
        val blurred = Blur.gaussian(sharp, region.w, region.h, blurPx, clampEdges = false)
        val s = 1.5                                          // saturate(1.5): matriz de saturação do CSS
        val m00 = 0.213 + 0.787 * s; val m01 = 0.715 - 0.715 * s; val m02 = 0.072 - 0.072 * s
        val m10 = 0.213 - 0.213 * s; val m11 = 0.715 + 0.285 * s; val m12 = 0.072 - 0.072 * s
        val m20 = 0.213 - 0.213 * s; val m21 = 0.715 - 0.715 * s; val m22 = 0.072 + 0.928 * s
        val bright = 1.06                                    // brightness(1.06)
        val out = IntArray(sharp.size)
        for (i in out.indices) {
            val bp = blurred[i]
            val a = bp ushr 24                               // cobertura do desfoque neste pixel (255 = opaco)
            val r = (bp shr 16) and 255; val g = (bp shr 8) and 255; val b = bp and 255   // cores já "pré-multiplicadas"
            val nr = ((m00 * r + m01 * g + m02 * b) * bright + 0.5).toInt().coerceIn(0, a)
            val ng = ((m10 * r + m11 * g + m12 * b) * bright + 0.5).toInt().coerceIn(0, a)
            val nb = ((m20 * r + m21 * g + m22 * b) * bright + 0.5).toInt().coerceIn(0, a)
            // 3) compõe o desfocado SOBRE o nítido: onde o desfoque é transparente, aparece o nítido
            val sp = sharp[i]
            val inv = 255 - a
            val rr = nr + ((sp shr 16) and 255) * inv / 255
            val gg = ng + ((sp shr 8) and 255) * inv / 255
            val bb = nb + (sp and 255) * inv / 255
            out[i] = (255 shl 24) or (min(255, rr) shl 16) or (min(255, gg) shl 8) or min(255, bb)
        }
        return GlassBackground(region.x0, region.y0, region.w, region.h, out)
    }

    // ------------------------------------------------------------------------------------------
    // SOMBRA: forma da dock desfocada e escurecida, desenhada ANTES do vidro
    // ------------------------------------------------------------------------------------------
    private fun drawShadow(canvas: IntArray, cw: Int, ch: Int, left: Int, top: Int, w: Int, h: Int, r: Double) {
        val sigma = h * 0.25 / 2.0                          // shadowBlur do Canvas = 2 × sigma
        val offY = jsRound(h * 0.08)                        // sombra levemente deslocada para baixo
        val m = ceil(3 * sigma).toInt() + 2                 // margem para o desfoque não cortar
        val mw = w + 2 * m; val mh = h + 2 * m
        val mask = IntArray(mw * mh)
        val hx = w / 2.0 - r; val hy = h / 2.0 - r
        for (y in 0 until mh) for (x in 0 until mw) {
            val dx = x + 0.5 - mw / 2.0; val dy = y + 0.5 - mh / 2.0
            val qx = abs(dx) - hx; val qy = abs(dy) - hy
            val dist = hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r   // distância assinada à borda
            val cov = (0.5 - dist).coerceIn(0.0, 1.0)       // cobertura (suaviza a borda)
            mask[y * mw + x] = ((cov * 255 + 0.5).toInt()) shl 24
        }
        val blurred = Blur.gaussian(mask, mw, mh, sigma, clampEdges = false)
        val ox = left - m; val oy = top - m + offY          // onde a máscara cai no quadro
        for (y in 0 until mh) {
            val cy = oy + y; if (cy < 0 || cy >= ch) continue
            for (x in 0 until mw) {
                val cx = ox + x; if (cx < 0 || cx >= cw) continue
                val a = (blurred[y * mw + x] ushr 24) * 0.35 / 255.0   // sombra preta com 35% de opacidade máxima
                if (a <= 0.0) continue
                val d = canvas[cy * cw + cx]
                val k = 1.0 - a
                canvas[cy * cw + cx] = (255 shl 24) or
                    (((((d shr 16) and 255) * k + 0.5).toInt()) shl 16) or
                    (((((d shr 8) and 255) * k + 0.5).toInt()) shl 8) or
                    ((((d and 255) * k + 0.5).toInt()))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Desenho da dock inteira
    // ------------------------------------------------------------------------------------------
    /**
     * Desenha sombra + vidro em cima de `canvas` (que já contém o papel de parede).
     * `p` deve estar na MESMA escala do canvas (use GlassParams.scaled(f) na prévia).
     */
    fun renderDock(canvas: IntArray, cw: Int, ch: Int, p: GlassParams, bg: GlassBackground) {
        val w = p.w; val h = p.h
        if (w <= 0 || h <= 0) return
        val r = min(p.r.toDouble(), min(w / 2.0, h / 2.0))   // o raio nunca passa de metade do lado menor
        val left = dockLeft(cw, p)
        val top = p.y

        drawShadow(canvas, cw, ch, left, top, w, h, r)

        val bevel = min(p.bevel.toDouble(), h / 2.0)         // bisel não passa de metade da altura
        val rimW = max(1.5, 2.5 * cw / 1179.0)               // espessura do fio de luz, proporcional à imagem
        val key = listOf(w, h, r, bevel, p.thick, p.ior, p.scale, p.profile, p.angle, rimW).joinToString("|")
        if (key != this.key || fw != w || fh != h) buildField(w, h, r, bevel, rimW, p, key)

        // ---- PASSO C: cor de cada pixel = fundo desfocado (com refração) + película branca + reflexo ----
        val shine = p.shine / 100.0f
        val cr = p.chroma / 100.0f
        val gx1 = cw - 1; val gy1 = ch - 1                   // limites do quadro (para "prender" leituras)
        val bx1 = bg.w - 1; val by1 = bg.h - 1               // limites do recorte do fundo
        val bpx = bg.px
        for (py in 0 until h) {
            val gy = top + py
            if (gy < 0 || gy >= ch) continue
            val tint = 0.08f + 0.10f * (1f - min(1f, max(0f, py.toFloat() / h)))   // película branca, mais forte no topo
            val omt = 1f - tint; val t255 = 255f * tint
            for (px in 0 until w) {
                val gx = left + px
                if (gx < 0 || gx >= cw) continue
                val k = py * w + px
                val a = alpha[k].toInt() and 0xFF
                if (a == 0) continue
                val d = disp[k]
                val c0: Int; val c1: Int; val c2: Int
                if (d == 0f) {                               // miolo plano: os três canais leem o mesmo pixel
                    val sx = gx.coerceIn(0, gx1); val sy = gy.coerceIn(0, gy1)
                    val q = bpx[(sy - bg.y0).coerceIn(0, by1) * bg.w + (sx - bg.x0).coerceIn(0, bx1)]
                    c0 = (q shr 16) and 255; c1 = (q shr 8) and 255; c2 = q and 255
                } else {                                     // bisel: refração com aberração cromática (R desvia menos, B mais)
                    val nx = nxArr[k]; val ny = nyArr[k]
                    var kk = d * (1f - cr)
                    var sx = (left + Math.round(px - nx * kk)).coerceIn(0, gx1); var sy = (top + Math.round(py - ny * kk)).coerceIn(0, gy1)
                    c0 = (bpx[(sy - bg.y0).coerceIn(0, by1) * bg.w + (sx - bg.x0).coerceIn(0, bx1)] shr 16) and 255
                    kk = d
                    sx = (left + Math.round(px - nx * kk)).coerceIn(0, gx1); sy = (top + Math.round(py - ny * kk)).coerceIn(0, gy1)
                    c1 = (bpx[(sy - bg.y0).coerceIn(0, by1) * bg.w + (sx - bg.x0).coerceIn(0, bx1)] shr 8) and 255
                    kk = d * (1f + cr)
                    sx = (left + Math.round(px - nx * kk)).coerceIn(0, gx1); sy = (top + Math.round(py - ny * kk)).coerceIn(0, gy1)
                    c2 = bpx[(sy - bg.y0).coerceIn(0, by1) * bg.w + (sx - bg.x0).coerceIn(0, bx1)] and 255
                }
                var v0 = c0 * omt + t255; var v1 = c1 * omt + t255; var v2 = c2 * omt + t255   // mistura com branco
                val hl = hl0[k] * shine
                if (hl != 0f) {                              // só na borda: soma o reflexo (branco + cor saturada)
                    val lum = 0.3f * c0 + 0.59f * c1 + 0.11f * c2
                    val omh = 1f - hl
                    v0 = v0 * omh + (127.5f + 0.5f * min(255f, max(0f, lum + (c0 - lum) * 2.5f))) * hl
                    v1 = v1 * omh + (127.5f + 0.5f * min(255f, max(0f, lum + (c1 - lum) * 2.5f))) * hl
                    v2 = v2 * omh + (127.5f + 0.5f * min(255f, max(0f, lum + (c2 - lum) * 2.5f))) * hl
                }
                // ---- PASSO D: coloca no quadro respeitando a transparência da borda (alfa) ----
                val i = gy * cw + gx
                val dst = canvas[i]
                val ia = 255 - a
                val r8 = ((v0 + 0.5f).toInt().coerceIn(0, 255) * a + ((dst shr 16) and 255) * ia + 127) / 255
                val g8 = ((v1 + 0.5f).toInt().coerceIn(0, 255) * a + ((dst shr 8) and 255) * ia + 127) / 255
                val b8 = ((v2 + 0.5f).toInt().coerceIn(0, 255) * a + (dst and 255) * ia + 127) / 255
                canvas[i] = (255 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // PASSO B: forma do vidro (cache). Só é recalculada quando o formato muda
    // ------------------------------------------------------------------------------------------
    private fun buildField(w: Int, h: Int, r: Double, bevel: Double, rimW: Double, p: GlassParams, newKey: String) {
        val table = Refraction.buildTable(p.profile, bevel, p.thick.toDouble(), p.ior.toDouble())   // tabela de refração
        val n = w * h
        if (fw != w || fh != h || alpha.size != n) {
            alpha = ByteArray(n); nxArr = FloatArray(n); nyArr = FloatArray(n); disp = FloatArray(n); hl0 = FloatArray(n)
        }
        val cx = w / 2.0; val cy = h / 2.0                   // centro da dock
        val hx = w / 2.0 - r; val hy = h / 2.0 - r           // "miolo" reto do retângulo arredondado
        val ang = p.angle * Math.PI / 180.0
        val lx = Math.cos(ang); val ly = Math.sin(ang)       // direção da luz
        for (py in 0 until h) {
            for (px in 0 until w) {
                val k = py * w + px
                // Distância assinada à borda (SDF): negativa = dentro, positiva = fora
                val dx = px + 0.5 - cx; val dy = py + 0.5 - cy
                val qx = abs(dx) - hx; val qy = abs(dy) - hy
                val ox = max(qx, 0.0); val oy = max(qy, 0.0)
                val len = sqrt(ox * ox + oy * oy)
                val dist = len + min(max(qx, qy), 0.0) - r
                if (dist > 0.5) { alpha[k] = 0; disp[k] = 0f; hl0[k] = 0f; continue }    // fora da dock: não pinta
                // Normal externa: para onde a borda "aponta" neste pixel
                val nx: Double; val ny: Double
                if (len > 0) { nx = ox / len * Math.signum(dx); ny = oy / len * Math.signum(dy) }   // cantos
                else if (qx > qy) { nx = Math.signum(dx); ny = 0.0 }                                 // laterais
                else { nx = 0.0; ny = Math.signum(dy) }                                              // topo/base
                val depth = max(0.0, -dist)                                           // distância da borda para dentro
                val idx = min(127, floor(depth / bevel * 127).toInt())                // posição na tabela
                nxArr[k] = nx.toFloat(); nyArr[k] = ny.toFloat()
                disp[k] = if (depth < bevel) (table[idx] * p.scale / 100.0).toFloat() else 0f   // 0 no miolo plano
                // Brilho da borda: fio de luz fino, mais forte do lado virado para a luz
                val dot = nx * lx + ny * ly                                           // 1 = virada para a luz, -1 = oposto
                val rim = if (depth < rimW) 1.0 - depth / rimW else 0.0
                hl0[k] = (rim * (0.2 + 0.8 * abs(dot)) * (if (dot > 0) 1.0 else 0.6)).toFloat()
                alpha[k] = (min(1.0, max(0.0, 0.5 - dist)) * 255).toInt().toByte()   // alfa: suaviza a serrilha
            }
        }
        key = newKey; fw = w; fh = h
    }

    /** Só para testes: amostra do cache (alfa, desvio, brilho) no pixel (px, py) da dock. */
    internal fun debugSample(px: Int, py: Int): Triple<Int, Float, Float> {
        val k = py * fw + px
        return Triple(alpha[k].toInt() and 0xFF, disp[k], hl0[k])
    }
}
