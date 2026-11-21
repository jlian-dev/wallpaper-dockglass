package com.dockliquida.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dockliquida.app.glass.GlassParams
import kotlin.math.roundToInt

/** Controles exclusivos do bloqueio: não alteram o enquadramento nem a Dock da tela inicial. */
@Composable
internal fun LockScreenPanel(vm: EditorVM) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.replaceLockImage(uri)
    }
    SectionCard("Tela de bloqueio") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Editar tela de bloqueio", style = MaterialTheme.typography.titleSmall)
                Text("Foto e enquadramento independentes, sem Dock.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = vm.lockEnabled, onCheckedChange = vm::setLockEnabled)
        }
        // Ao ligar/desligar a edição do bloqueio os controles abrem e fecham com deslize vertical + fade.
        AnimatedVisibility(
            visible = vm.lockEnabled,
            enter = fadeIn(tween(220)) + expandVertically(tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(220, easing = FastOutSlowInEasing))
        ) {
            LockControls(vm) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
    }
}

@Composable
private fun LockControls(vm: EditorVM, onPickPhoto: () -> Unit) {
    // lockConfig pode ficar nulo por um instante (o editor abriu outro wallpaper enquanto este bloco ainda estava
    // saindo da tela). Um valor padrão no lugar de "!!" evita que o app feche nessa janela.
    val p = vm.lockConfig?.params ?: GlassParams()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A frase troca com um fade quando a origem da foto muda.
        Crossfade(targetState = vm.lockConfig?.imageId == null, animationSpec = tween(200), label = "lockPhotoSource") { sameAsHome ->
            Text(if (sameAsHome) "Usando a mesma foto da tela inicial" else "Usando outra foto no bloqueio",
                style = MaterialTheme.typography.bodySmall)
        }
        FilledTonalButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth().pressScale()) {
            Text("Trocar foto do bloqueio")
        }
        AnimatedVisibility(
            visible = vm.lockConfig?.imageId != null,
            enter = fadeIn(tween(200)) + expandVertically(tween(240, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200, easing = FastOutSlowInEasing))
        ) {
            TextButton(onClick = vm::useMainImageForLock, modifier = Modifier.fillMaxWidth().pressScale()) {
                Text("Usar a foto da tela inicial")
            }
        }
        // Os controles deslizantes entram em cascata, um depois do outro.
        Box(Modifier.fillMaxWidth().cascadeIn(0)) {
            LabeledSlider("Desfoque do bloqueio", p.wpBlur.toFloat(), 0f..60f, "${p.wpBlur}") { v ->
                vm.updateLock { it.copy(wpBlur = v.roundToInt()) }
            }
        }
        Box(Modifier.fillMaxWidth().cascadeIn(1)) {
            LabeledSlider("Zoom do bloqueio", p.zoom.toFloat(), 100f..400f, "${p.zoom}%") { v ->
                vm.updateLock { it.copy(zoom = v.roundToInt()) }
            }
        }
        Box(Modifier.fillMaxWidth().cascadeIn(2)) {
            LabeledSlider("Horizontal do bloqueio", p.panX.toFloat(), -100f..100f, "${p.panX}") { v ->
                vm.updateLock { it.copy(panX = v.roundToInt()) }
            }
        }
        Box(Modifier.fillMaxWidth().cascadeIn(3)) {
            LabeledSlider("Vertical do bloqueio", p.panY.toFloat(), -100f..100f, "${p.panY}") { v ->
                vm.updateLock { it.copy(panY = v.roundToInt()) }
            }
        }
        TextButton(onClick = { vm.updateLock { it.copy(zoom = 100, panX = 0, panY = 0, wpBlur = 0) } }, modifier = Modifier.pressScale()) {
            Text("Restaurar enquadramento do bloqueio")
        }
        Text("A prévia é reduzida para economizar memória. A imagem aplicada usa a resolução de saída do editor.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Painel normal no layout: recebe metade da largura e a mesma altura da prévia principal. */
@Composable
internal fun LockScreenPreviewPane(vm: EditorVM, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Bloqueio", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.changeLockPreviewVisibility(false) }, modifier = Modifier.size(48.dp).pressScale(0.9f)) {
                val color = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.size(22.dp).semantics { contentDescription = "Ocultar prévia do bloqueio" }) {
                    val eye = Path().apply {
                        moveTo(0f, size.height / 2)
                        quadraticBezierTo(size.width / 2, -size.height / 5, size.width, size.height / 2)
                        quadraticBezierTo(size.width / 2, size.height * 1.2f, 0f, size.height / 2)
                        close()
                    }
                    drawPath(eye, color, style = Stroke(1.5.dp.toPx()))
                    drawCircle(color, size.minDimension * .13f)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center) {
            val bitmap = vm.lockPreview
            val error = vm.lockPreviewError
            // Primeira prévia: aparece com fade e crescendo de 96% a 100% (as seguintes só atualizam a imagem).
            val appear = remember { Animatable(0f) }
            LaunchedEffect(bitmap != null) { if (bitmap != null && appear.value < 1f) appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
            if (bitmap != null && vm.outW > 0 && vm.outH > 0) {
                val ratio = vm.outW.toFloat() / vm.outH
                val width = minOf(maxWidth, maxHeight * ratio)
                Image(bitmap.asImageBitmap(), "Prévia reduzida do bloqueio",
                    Modifier.size(width, width / ratio)
                        .graphicsLayer { alpha = appear.value; val s = 0.96f + 0.04f * appear.value; scaleX = s; scaleY = s }
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.FillBounds)
            } else if (error != null) Text(error,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LockScreenPreviewRestore(vm: EditorVM, modifier: Modifier = Modifier) {
    Surface(onClick = { vm.changeLockPreviewVisibility(true) }, modifier = modifier.size(48.dp).pressScale(0.92f),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.semantics { contentDescription = "Mostrar prévia do bloqueio" }) {
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
