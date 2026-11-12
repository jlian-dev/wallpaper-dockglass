package com.dockliquida.app.glass

/*
 * GlassParams.kt
 * --------------------------------------------------------------------------
 * Aqui ficam TODOS os números que o usuário controla (era o objeto "state" do
 * script.js). É uma "data class" IMUTÁVEL: para mudar um valor criamos uma cópia
 * com copy(...). Isso é ótimo para o Jetpack Compose, que redesenha a tela
 * sozinho quando percebe que o objeto mudou.
 *
 * Este arquivo NÃO usa nada do Android: é Kotlin puro, por isso o mesmo código
 * roda no editor, no exportador de PNG e na troca automática (Worker).
 */

/** Formato (perfil) da superfície do bisel — a curva que define como o vidro "entorta" a luz. */
enum class BevelProfile(val label: String) {
    SQUIRCLE("Squircle (estilo Apple)"),
    CIRCLE("Círculo"),
    LIP("Lip (borda alta, centro côncavo)"),
    CONCAVE("Côncavo")
}

/**
 * Estilos prontos (atalhos). Bisel e espessura são FRAÇÕES da altura da dock e o desfoque é
 * fração da largura da imagem, então valem em qualquer resolução (igual ao ESTILOS do script.js).
 */
enum class GlassStyle(
    val label: String,
    val bevel: Double,      // fração da altura da dock
    val thickness: Double,  // fração do bisel
    val blur: Double,       // fração da largura da imagem
    val ior: Float,         // índice de refração
    val scale: Int,         // escala da refração (%)
    val chroma: Int,        // aberração cromática (%)
    val shine: Int,         // brilho especular (0–100)
    val profile: BevelProfile
) {
    CRISTAL("Cristal", .35, 1.2, .012, 1.5f, 100, 8, 60, BevelProfile.SQUIRCLE),   // o visual padrão
    GELO("Gelo", .42, 1.5, .006, 1.8f, 130, 14, 90, BevelProfile.SQUIRCLE),        // transparente, muita refração
    FOSCO("Fosco", .30, .8, .030, 1.2f, 60, 0, 35, BevelProfile.SQUIRCLE),         // vidro jateado
    LIQUIDO("Líquido", .45, 1.4, .012, 1.6f, 140, 10, 70, BevelProfile.LIP),       // borda alta, centro côncavo
    SUTIL("Sutil", .25, 1.0, .010, 1.2f, 50, 3, 25, BevelProfile.SQUIRCLE)         // quase imperceptível
}

/** Arredonda como o Math.round do JavaScript (0,5 sempre sobe). Mantém o resultado idêntico ao do app web. */
internal fun jsRound(v: Double): Int = Math.floor(v + 0.5).toInt()

data class GlassParams(
    val x: Int = 0,            // deslocamento horizontal em relação ao centro (px)
    val y: Int = 0,            // distância do topo da dock até o topo da imagem (px)
    val w: Int = 0,            // largura da dock (px)
    val h: Int = 0,            // altura da dock (px)
    val r: Int = 0,            // raio da curvatura dos cantos (px)
    val blur: Int = 18,        // desfoque do fundo atrás do vidro (px)
    val ior: Float = 1.5f,     // índice de refração (1 = ar, 1.5 = vidro)
    val thick: Int = 60,       // espessura do vidro (px)
    val scale: Int = 100,      // escala da refração (%)
    val chroma: Int = 8,       // aberração cromática (%)
    val angle: Int = -60,      // direção da luz do reflexo (graus)
    val profile: BevelProfile = BevelProfile.SQUIRCLE,
    val wpBlur: Int = 0,       // desfoque do papel de parede inteiro (px)
    val zoom: Int = 100,       // zoom do papel de parede dentro do quadro (%; 100 = só o corte mínimo)
    val panX: Int = 0,         // enquadramento horizontal (-100 a 100)
    val panY: Int = 0,         // enquadramento vertical (-100 a 100)
    val bevel: Int = 34,       // largura do bisel: a faixa de borda que entorta a luz (px)
    val shine: Int = 60        // intensidade do brilho especular (0–100)
) {
    /**
     * Versão em outra escala (a prévia é desenhada menor que o PNG final). Só as medidas em pixels
     * acompanham a escala; ior, ângulo, zoom e pan não mudam. (= SCALED do script.js)
     */
    fun scaled(f: Double): GlassParams = copy(
        x = jsRound(x * f), y = jsRound(y * f), w = jsRound(w * f), h = jsRound(h * f), r = jsRound(r * f),
        bevel = jsRound(bevel * f), thick = jsRound(thick * f), blur = jsRound(blur * f), wpBlur = jsRound(wpBlur * f)
    )

    /**
     * Mudou o tamanho da imagem de saída? A dock é reescalada na mesma proporção para não "fugir"
     * (horizontal para x/largura/raio…, vertical para y/altura). (= applySize do script.js)
     */
    fun resized(fx: Double, fy: Double): GlassParams = copy(
        x = jsRound(x * fx), w = jsRound(w * fx), r = jsRound(r * fx), bevel = jsRound(bevel * fx),
        thick = jsRound(thick * fx), blur = jsRound(blur * fx), wpBlur = jsRound(wpBlur * fx),
        y = jsRound(y * fy), h = jsRound(h * fy)
    )

    /** Aplica um estilo pronto (Cristal, Gelo…) mantendo posição e tamanho da dock. */
    fun withStyle(s: GlassStyle, outW: Int): GlassParams {
        val newBevel = jsRound(h * s.bevel)                 // bisel proporcional à altura da dock
        return copy(
            bevel = newBevel,
            thick = jsRound(newBevel * s.thickness),        // espessura proporcional ao bisel
            blur = jsRound(outW * s.blur),                  // desfoque proporcional à largura da imagem
            ior = s.ior, scale = s.scale, chroma = s.chroma, shine = s.shine, profile = s.profile
        )
    }

    companion object {
        /** Dock inicial proporcional ao tamanho de saída (= resetDock do script.js). Perto da base da tela. */
        fun initialFor(outW: Int, outH: Int): GlassParams {
            val dockW = jsRound(outW * 0.9)
            val dockH = jsRound(outW * 0.2)
            val bevel = jsRound(dockH * 0.35)               // bisel ≈ 1/3 da altura da dock
            return GlassParams(
                x = 0, w = dockW, h = dockH,
                y = outH - dockH - jsRound(outH * 0.025),
                r = jsRound(dockH * 0.36),
                bevel = bevel, thick = jsRound(bevel * 1.2),
                blur = jsRound(outW * 0.012)
            )
        }
    }
}
