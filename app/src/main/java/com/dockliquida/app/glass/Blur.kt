package com.dockliquida.app.glass

import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/*
 * Blur.kt
 * --------------------------------------------------------------------------
 * Desfoque gaussiano rápido em imagens ARGB (IntArray, um Int por pixel: 0xAARRGGBB).
 *
 * O Canvas do navegador fazia isso com `ctx.filter = "blur(Npx)"`, onde N é o desvio-padrão (sigma)
 * da gaussiana. Aqui aproximamos a gaussiana com TRÊS desfoques de caixa seguidos (técnica
 * clássica "boxes for gauss"): o resultado é praticamente idêntico e o custo é O(pixels),
 * independente do tamanho do desfoque.
 *
 * Cada passe usa uma "soma deslizante": em vez de somar 2r+1 pixels para cada pixel, somamos
 * a janela uma vez e, ao andar um pixel, tiramos o que saiu e colocamos o que entrou.
 */
object Blur {

    /** Calcula o tamanho (largura) das 3 caixas que juntas imitam uma gaussiana de desvio `sigma`. */
    private fun boxSizes(sigma: Double, n: Int): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1.0)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--                                   // tamanho de caixa precisa ser ímpar
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n.toDouble() * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)
        val m = round(mIdeal).toInt()                           // quantas caixas usam o tamanho menor
        return IntArray(n) { if (it < m) wl else wu }
    }

    /**
     * Desfoca `src` e devolve uma imagem NOVA (a original não é alterada).
     * @param clampEdges true  = fora da imagem repete a cor da borda (usado no desfoque do papel de parede);
     *                   false = fora da imagem é transparente (igual ao filtro blur() do Canvas, usado no vidro).
     */
    fun gaussian(src: IntArray, w: Int, h: Int, sigma: Double, clampEdges: Boolean): IntArray {
        if (sigma < 0.3) return src.copyOf()                     // desfoque desprezível: só copia
        var a = src.copyOf()                                    // buffer A (entrada do passe atual)
        var b = IntArray(src.size)                              // buffer B (saída do passe atual)
        for (size in boxSizes(sigma, 3)) {
            val r = (size - 1) / 2                              // raio da caixa
            if (r < 1) continue
            passHorizontal(a, b, w, h, r, clampEdges)           // 1) desfoca nas linhas
            passVertical(b, a, w, h, r, clampEdges)             // 2) desfoca nas colunas (volta para A)
        }
        return a
    }

    /** Um passe de caixa ao longo de cada LINHA. Os 4 canais (A,R,G,B) são somados separadamente. */
    private fun passHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, clamp: Boolean) {
        val div = 2 * r + 1                                      // pixels dentro da janela
        val half = div / 2                                       // soma de meio divisor: arredonda em vez de truncar
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0       // somas da janela atual
            for (i in -r..r) {                                    // monta a janela inicial centrada no pixel 0
                val xi = if (clamp) i.coerceIn(0, w - 1) else i
                if (xi in 0 until w) {
                    val p = src[row + xi]
                    sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
                }
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa + half) / div shl 24) or ((sr + half) / div shl 16) or ((sg + half) / div shl 8) or ((sb + half) / div)
                // desliza a janela: sai o pixel x-r, entra o pixel x+r+1
                var xo = x - r; var xn = x + r + 1
                if (clamp) { xo = xo.coerceIn(0, w - 1); xn = xn.coerceIn(0, w - 1) }
                if (xo in 0 until w) { val p = src[row + xo]; sa -= p ushr 24; sr -= (p shr 16) and 255; sg -= (p shr 8) and 255; sb -= p and 255 }
                if (xn in 0 until w) { val p = src[row + xn]; sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            }
        }
    }

    /** Mesmo passe, mas ao longo de cada COLUNA (o "passo" entre pixels vizinhos é a largura w). */
    private fun passVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, clamp: Boolean) {
        val div = 2 * r + 1
        val half = div / 2
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val yi = if (clamp) i.coerceIn(0, h - 1) else i
                if (yi in 0 until h) {
                    val p = src[yi * w + x]
                    sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
                }
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa + half) / div shl 24) or ((sr + half) / div shl 16) or ((sg + half) / div shl 8) or ((sb + half) / div)
                var yo = y - r; var yn = y + r + 1
                if (clamp) { yo = yo.coerceIn(0, h - 1); yn = yn.coerceIn(0, h - 1) }
                if (yo in 0 until h) { val p = src[yo * w + x]; sa -= p ushr 24; sr -= (p shr 16) and 255; sg -= (p shr 8) and 255; sb -= p and 255 }
                if (yn in 0 until h) { val p = src[yn * w + x]; sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            }
        }
    }

}
