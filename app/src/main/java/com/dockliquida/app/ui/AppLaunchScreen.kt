package com.dockliquida.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dockliquida.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * AppLaunchScreen.kt
 * --------------------------------------------------------------------------
 * Tela de abertura animada, no tema "líquido" do app. Linha do tempo (primeira abertura):
 *
 *   0,00 s  brilho colorido cresce ao fundo (e fica "flutuando" devagar)
 *   0,00 s  o ícone salta de pequeno até o tamanho final, com mola (passa um pouco do ponto e assenta)
 *   0,26 s  duas ondas, como gotas na água, se espalham a partir do ícone
 *   0,38 s  um reflexo de vidro atravessa o ícone na diagonal
 *   0,42 s  o nome aparece letra por letra, subindo suavemente
 *   1,15 s  a abertura se dissolve (cresce um pouco enquanto some) e o app aparece com fade
 *
 * Desempenho: todos os valores animados são lidos só nas fases de "desenho" (graphicsLayer, drawWithContent, Canvas).
 * Assim o Compose não precisa recompor a tela a cada quadro: só redesenha.
 */
@Composable
internal fun AppLaunchScreen(content: @Composable () -> Unit) {
    // rememberSaveable: se o Android recriar a tela (ex.: processo morto), não repete a abertura.
    var opened by rememberSaveable { mutableStateOf(false) }

    // Cada Animatable é um "controle deslizante" de 0 a 1 (ou valor próprio) que a linha do tempo abaixo move.
    val glow = remember { Animatable(0f) }          // brilho de fundo: 0 = apagado, 1 = pleno
    val logoAlpha = remember { Animatable(0f) }     // transparência do ícone
    val logoScale = remember { Animatable(0.55f) }  // tamanho do ícone (a mola leva até 1)
    val ripple = remember { Animatable(0f) }        // progresso das ondas (0 = no ícone, 1 = espalhadas e sumidas)
    val shine = remember { Animatable(0f) }         // posição do reflexo (0 = antes do ícone, 1 = depois)
    val title = remember { Animatable(0f) }         // progresso do nome, letra por letra
    val exit = remember { Animatable(0f) }          // saída da abertura (0 = visível, 1 = sumiu)
    val contentProgress = remember { Animatable(if (opened) 1f else 0f) } // entrada do app

    LaunchedEffect(Unit) {
        if (opened) return@LaunchedEffect
        // Cada launch roda "ao mesmo tempo"; o delay() dentro dele é só o atraso de início daquele efeito.
        launch { glow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        launch { logoAlpha.animateTo(1f, tween(220)) }
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) }
        launch { delay(260); ripple.animateTo(1f, tween(1200, easing = LinearEasing)) }
        launch { delay(380); shine.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { delay(420); title.animateTo(1f, tween(650, easing = LinearEasing)) }

        delay(1150)                                                       // tempo para o olho ler a marca
        exit.animateTo(1f, tween(260, easing = FastOutLinearInEasing))   // abertura se dissolve
        opened = true                                                     // só agora monta o app (pesado), com a tela já vazia
        contentProgress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
    }

    if (opened) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = contentProgress.value
            val scale = 0.97f + 0.03f * contentProgress.value
            scaleX = scale
            scaleY = scale
        }) {
            content()
        }
    } else {
        val appName = stringResource(R.string.app_name)
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary
        val muted = MaterialTheme.colorScheme.onSurfaceVariant

        // "Deriva" lenta e infinita dos brilhos de fundo (0 → 1 a cada 6 s, recomeçando).
        val drift = rememberInfiniteTransition(label = "launchDrift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "drift"
        )

        // Camada única: ao sair, tudo junto cresce 8% e some.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - exit.value
                val s = 1f + 0.08f * exit.value
                scaleX = s
                scaleY = s
            }
        ) {
            // 1) Brilho de fundo: dois "borrões" de cor (gradiente radial) que crescem e giram devagar.
            Canvas(Modifier.fillMaxSize()) {
                val g = glow.value
                val phase = drift.value * 2f * PI.toFloat()
                val base = minOf(size.width, size.height)
                val cx = size.width / 2f
                val cy = size.height * 0.42f                              // altura aproximada do ícone
                val radius = base * (0.35f + 0.25f * g)                   // o brilho se expande enquanto acende
                val a = Offset(cx + cos(phase) * base * 0.08f, cy + sin(phase) * base * 0.06f)
                val b = Offset(cx + cos(phase + PI.toFloat()) * base * 0.12f, cy + sin(phase + PI.toFloat()) * base * 0.10f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(primary.copy(alpha = 0.30f * g), Color.Transparent), center = a, radius = radius),
                    radius = radius, center = a
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(tertiary.copy(alpha = 0.22f * g), Color.Transparent), center = b, radius = radius * 0.9f),
                    radius = radius * 0.9f, center = b
                )
            }

            // 2) Conteúdo central: ícone (com ondas e reflexo), nome e indicador.
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Palco do ícone: 96 dp. A Canvas das ondas é maior (requiredSize) e "vaza" para os lados sem empurrar o layout.
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.requiredSize(300.dp)) {
                        val startR = 48.dp.toPx()                         // começa na borda do ícone
                        val endR = size.minDimension / 2f                 // termina na borda da Canvas
                        for (k in 0..1) {                                 // duas ondas, a segunda um pouco atrasada
                            val p = ((ripple.value - k * 0.22f) / (1f - 0.22f)).coerceIn(0f, 1f)
                            if (p <= 0f || p >= 1f) continue
                            val e = FastOutSlowInEasing.transform(p)      // espalha rápido e desacelera, como água
                            val fade = (1f - p) * (1f - p)                // some suavemente
                            drawCircle(
                                color = primary.copy(alpha = 0.55f * fade),
                                radius = startR + (endR - startR) * e,
                                center = center,
                                style = Stroke(width = (0.6f + 2.5f * (1f - p)).dp.toPx())   // linha afina ao se espalhar
                            )
                        }
                    }

                    Box(
                        Modifier
                            .size(96.dp)
                            .graphicsLayer {
                                alpha = logoAlpha.value
                                scaleX = logoScale.value
                                scaleY = logoScale.value
                                // Enquanto está pequeno, fica um pouco mais embaixo: parece "subir" ao crescer.
                                translationY = (1f - logoScale.value) * 30.dp.toPx()
                            }
                            .clip(RoundedCornerShape(26.dp))
                            .drawWithContent {
                                drawContent()                             // o ícone em si
                                val w = size.width
                                val h = size.height
                                // Reflexo de vidro: faixa branca diagonal que atravessa o ícone uma vez.
                                val x = -0.6f * w + (2.2f * w) * shine.value
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                        start = Offset(x - 0.3f * w, 0f),
                                        end = Offset(x + 0.3f * w, h)
                                    )
                                )
                                // Brilho fixo no topo, como a borda de uma peça de vidro.
                                drawRoundRect(
                                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), endY = h * 0.5f),
                                    cornerRadius = CornerRadius(26.dp.toPx())
                                )
                                // Contorno fino e claro.
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.25f),
                                    cornerRadius = CornerRadius(26.dp.toPx()),
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_background),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // Nome do app, letra por letra.
                Box(Modifier.padding(top = 20.dp)) { StaggeredTitle(appName, title) }

                // Indicador de carregamento: três pontinhos que pulsam em sequência + texto, aparecendo com o nome.
                Row(
                    modifier = Modifier
                        .padding(top = 28.dp)
                        .graphicsLayer { alpha = ((title.value - 0.4f) / 0.6f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PulsingDots(primary)
                    Text(
                        text = "Iniciando $appName…",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                }
            }
        }
    }
}

/**
 * Escreve o texto letra por letra: cada letra começa um pouco depois da anterior, sobe 14 dp e vai de transparente a opaca.
 * `progress` vai de 0 a 1; as letras dividem esse intervalo (cada uma usa metade dele, escalonadas).
 */
@Composable
private fun StaggeredTitle(text: String, progress: Animatable<Float, AnimationVector1D>) {
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { i, ch ->
            Text(
                text = ch.toString(),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.graphicsLayer {
                    val start = 0.5f * i / text.length                      // quando esta letra começa (0 a 0,5)
                    val local = FastOutSlowInEasing.transform(((progress.value - start) / 0.5f).coerceIn(0f, 1f))
                    alpha = local
                    translationY = (1f - local) * 14.dp.toPx()
                }
            )
        }
    }
}

/** Três pontinhos que "respiram" (crescem e clareiam) um depois do outro, em laço infinito. */
@Composable
private fun PulsingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "launchDots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(480, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 160)               // cada ponto começa 160 ms depois do anterior
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        alpha = a.value
                        val s = 0.7f + 0.3f * a.value
                        scaleX = s
                        scaleY = s
                    }
                    .background(color, CircleShape)
            )
        }
    }
}
