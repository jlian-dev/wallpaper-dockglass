package com.dockliquida.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Motion.kt — peças de movimento reutilizadas pelo app inteiro.
 * --------------------------------------------------------------------------
 * Nada aqui muda o desenho do app (cores, formas, tamanhos): só como as coisas se MOVEM.
 *
 *   pressScale()   o elemento "afunda" um pouco enquanto o dedo está sobre ele e volta com mola
 *   cascadeIn()    o elemento aparece subindo suavemente, um depois do outro (efeito cascata)
 *   EditorTransition   o editor abre crescendo/aparecendo e fecha encolhendo/sumindo
 *
 * Desempenho: os valores animados são lidos só na fase de desenho (graphicsLayer), então o Compose não
 * recompõe a tela a cada quadro. Se o aparelho estiver com "remover animações" ligado, o Compose já
 * encurta os tempos sozinho (as animações terminam na hora).
 */

/**
 * Efeito de toque: enquanto há um dedo sobre o elemento ele encolhe para [pressedScale] e volta com uma mola.
 * Só OBSERVA o toque (não consome nada), então funciona junto de clickable, botões do Material e rolagem:
 * se a lista começar a rolar, o toque é cancelado e o elemento volta ao tamanho normal.
 * Coloque ANTES do clip/clickable na cadeia de modifiers, para escalar o elemento inteiro.
 */
fun Modifier.pressScale(pressedScale: Float = 0.96f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)   // dedo encostou (mesmo que um botão já tenha tratado o toque)
                pressed = true
                try {
                    waitForUpOrCancellation()
                } finally {
                    pressed = false
                }
            }
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

/** Por quanto tempo, depois de a tela abrir, os itens ainda entram em cascata (itens que surgem ao rolar não). */
private const val INTRO_WINDOW_MS = 700L

/** Guarda o instante em que a tela/lista abriu. Passe esse valor para [cascadeIn] nos itens de listas que rolam. */
@Composable
fun rememberIntroClock(key: Any? = null): Long = remember(key) { SystemClock.uptimeMillis() }

/**
 * Entrada em cascata: o elemento começa transparente e [rise] mais embaixo, e sobe até o lugar com uma mola.
 * `index` atrasa cada item em [stepMs] ms (até o 10º, para a fila não ficar longa).
 * `introClock` (de [rememberIntroClock]) limita a cascata a quem aparece logo na abertura da tela: itens que só
 * surgem depois, ao rolar, aparecem direto. Com o valor padrão 0 o item sempre anima (telas sem rolagem longa).
 */
fun Modifier.cascadeIn(index: Int, introClock: Long = 0L, stepMs: Int = 35, rise: Dp = 14.dp): Modifier = composed {
    val animate = remember(introClock) { introClock == 0L || SystemClock.uptimeMillis() - introClock < INTRO_WINDOW_MS }
    val progress = remember(introClock) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(introClock) {
        if (animate) {
            delay(index.coerceIn(0, 10) * stepMs.toLong())
            progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)                          // a mola pode passar de 1: a transparência não
        translationY = (1f - p) * rise.toPx()
    }
}

/**
 * Envolve o editor: ele entra crescendo (94% → 100%), subindo um pouco e aparecendo; ao fechar faz o caminho
 * inverso e SÓ DEPOIS chama [onClosed] (que fecha o editor de verdade e volta às abas). Assim a tela não
 * pisca vazia durante a saída. `content` recebe `requestClose`: chame-o em vez de fechar direto.
 */
@Composable
fun EditorTransition(onClosed: () -> Unit, content: @Composable (requestClose: () -> Unit) -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestOnClosed by rememberUpdatedState(onClosed)
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }

    val requestClose: () -> Unit = remember {
        {
            if (!closing) {                                 // vários toques em "voltar" não disparam duas saídas
                closing = true
                scope.launch {
                    progress.animateTo(0f, tween(220, easing = FastOutLinearInEasing))
                    latestOnClosed()
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = progress.value
            alpha = p
            val s = 0.94f + 0.06f * p
            scaleX = s
            scaleY = s
            translationY = (1f - p) * 28.dp.toPx()
        }
    ) { content(requestClose) }
}
