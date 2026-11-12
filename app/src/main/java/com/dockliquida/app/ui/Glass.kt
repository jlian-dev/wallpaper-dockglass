package com.dockliquida.app.ui

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Glass.kt — o Liquid Glass da INTERFACE (usado só na barra inferior e nos botões do topo).
 * --------------------------------------------------------------------------
 * Como funciona o desfoque de verdade (Android 12+):
 *   1) A tela inteira (a "fonte") é gravada numa camada gráfica (GraphicsLayer) além de ser desenhada normalmente.
 *   2) Cada peça de vidro desenha uma CÓPIA dessa camada, deslocada para a posição dela, com desfoque (BlurEffect),
 *      e por cima coloca uma película clara e uma borda de luz.
 *   Resultado: o que passa por baixo da barra (a grade de imagens rolando) fica realmente borrado.
 * No Android 8–11 não há RenderEffect: o vidro vira uma superfície translúcida simples (sem desfoque).
 *
 * IMPORTANTE: as peças de vidro ficam FORA da "fonte" (irmãs dela), senão uma desenharia a si mesma.
 */

/** A camada gravada + onde (em pixels da janela) começa o conteúdo gravado. */
class Backdrop(val layer: GraphicsLayer) { var origin by mutableStateOf(Offset.Zero) }

/** A fonte de desfoque "ativa" para as peças de vidro desenhadas dentro deste escopo. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Preferência da interface: vidro atual ligado; superfícies Material 3 desligado. */
val LocalGlassFull = staticCompositionLocalOf { false }

/**
 * Cria uma fonte de desfoque. `content` é o conteúdo da tela (rolável, imagens…); `chrome` são as peças de vidro
 * (botões do topo, barra inferior) que ficam POR CIMA e desfocam o que está embaixo.
 */
@Composable
fun GlassScope(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    chrome: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    // Sem Liquid Glass, não precisamos capturar nem desfocar o fundo da tela.
    if (!LocalGlassFull.current) {
        Box(modifier) {
            Box(Modifier.fillMaxSize().background(background), content = content)
            chrome()
        }
        return
    }

    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { Backdrop(layer) }
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(modifier) {
            Box(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }   // para alinhar a cópia desfocada
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }            // grava o conteúdo na camada…
                        drawLayer(layer)                                              // …e desenha normalmente na tela
                    }
                    .background(background),                                          // o fundo entra na gravação (fica depois do drawWithContent)
                content = content
            )
            chrome()
        }
    }
}

/** O tema atual é escuro? */
@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Peça de vidro: cópia desfocada do fundo + película clara + borda de luz (clara no canto superior esquerdo). */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    // Material 3: mesma forma, tamanho, conteúdo e clique; sem blur, reflexos ou borda de vidro.
    if (!LocalGlassFull.current) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier
                .shadow(elevation, shape)
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            content = content
        )
        return
    }

    val backdrop = LocalBackdrop.current
    val dark = isDarkSurface()
    val cs = MaterialTheme.colorScheme
    val glassLayer = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    val canBlur = Build.VERSION.SDK_INT >= 31 && backdrop != null
    // película: mais leve quando há desfoque real; mais opaca quando é só translucidez
    val tint = if (canBlur) (if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.42f)) else cs.surfaceContainerHigh.copy(alpha = 0.92f)
    val rim = Brush.linearGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.25f))
        else listOf(Color.White.copy(alpha = 1f), Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.70f))
    )
    Box(
        modifier
            .shadow(elevation, shape)
            .clip(shape)
            .onGloballyPositioned { pos = it.positionInRoot() }
            .drawWithContent {
                val bd = backdrop
                if (canBlur && bd != null) {
                    glassLayer.renderEffect = BlurEffect(18.dp.toPx(), 18.dp.toPx(), TileMode.Clamp)
                    glassLayer.record {
                        // desloca a cópia do fundo para que o trecho certo fique atrás desta peça
                        translate(left = bd.origin.x - pos.x, top = bd.origin.y - pos.y) { drawLayer(bd.layer) }
                    }
                    drawLayer(glassLayer)
                }
                drawRect(tint)
                drawContent()
            }
            .border(1.dp, rim, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content
    )
}

/** Botão quadrado-arredondado de vidro só com ícone (voltar, ampliar…). Mesmo formato da versão anterior. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.size(52.dp), RoundedCornerShape(18.dp), onClick = onClick) {
        Icon(icon, description, Modifier.align(Alignment.Center).size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Ação de topo em vidro (ex.: "+ Adicionar"), com ícone opcional. */
@Composable
fun GlassAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    GlassSurface(modifier, RoundedCornerShape(26.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 15.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

/** Item da barra inferior, versão compacta: só o ícone; o selecionado ganha uma pílula e mostra o nome ao lado. */
@Composable
fun NavItem(on: Boolean, icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Apenas a selecao muda suavemente; mantemos a mesma forma, altura e efeito de vidro.
    val tint by animateColorAsState(
        targetValue = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "navIconColor"
    )
    val itemBackground by animateColorAsState(
        targetValue = if (on) cs.primaryContainer.copy(alpha = 0.80f) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "navItemBackground"
    )
    Row(
        modifier.clip(CircleShape)
            .background(itemBackground)
            .clickable(onClick = onClick)
            .animateContentSize(animationSpec = tween(durationMillis = 220))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = tint)
        if (on) { Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1) }
    }
}

/** Ícones que não existem no pacote básico, desenhados por texto de caminho (SVG). */
object DockIcons {
    private fun vec(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Gallery: ImageVector by lazy { vec("Gallery", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z") }
    val Fullscreen: ImageVector by lazy { vec("Fullscreen", "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z") }
    val FullscreenExit: ImageVector by lazy { vec("FullscreenExit", "M5,16h3v3h2v-5H5v2zM8,8H5v2h5V5H8v3zM14,19h2v-3h3v-2h-5v5zM16,8V5h-2v5h5V8h-3z") }
}
