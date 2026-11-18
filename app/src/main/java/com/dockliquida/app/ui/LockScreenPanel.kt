package com.dockliquida.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
        if (vm.lockEnabled) {
            Text(if (vm.lockConfig?.imageId == null) "Usando a mesma foto da tela inicial" else "Usando outra foto no bloqueio",
                style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.fillMaxWidth()) { Text("Trocar foto do bloqueio") }
            if (vm.lockConfig?.imageId != null) {
                TextButton(onClick = vm::useMainImageForLock, modifier = Modifier.fillMaxWidth()) {
                    Text("Usar a foto da tela inicial")
                }
            }
            val p = vm.lockConfig!!.params
            LabeledSlider("Desfoque do bloqueio", p.wpBlur.toFloat(), 0f..60f, "${p.wpBlur}") { v ->
                vm.updateLock { it.copy(wpBlur = v.roundToInt()) }
            }
            LabeledSlider("Zoom do bloqueio", p.zoom.toFloat(), 100f..400f, "${p.zoom}%") { v ->
                vm.updateLock { it.copy(zoom = v.roundToInt()) }
            }
            LabeledSlider("Horizontal do bloqueio", p.panX.toFloat(), -100f..100f, "${p.panX}") { v ->
                vm.updateLock { it.copy(panX = v.roundToInt()) }
            }
            LabeledSlider("Vertical do bloqueio", p.panY.toFloat(), -100f..100f, "${p.panY}") { v ->
                vm.updateLock { it.copy(panY = v.roundToInt()) }
            }
            TextButton(onClick = { vm.updateLock { it.copy(zoom = 100, panX = 0, panY = 0, wpBlur = 0) } }) {
                Text("Restaurar enquadramento do bloqueio")
            }
            Text("A prévia é reduzida para economizar memória. A imagem aplicada usa a resolução de saída do editor.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Prévia pequena sobre a área principal; recolher libera a referência e suspende novos pedidos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LockScreenMiniPreview(vm: EditorVM, modifier: Modifier = Modifier) {
    if (!vm.lockEnabled) return
    if (!vm.lockPreviewVisible) {
        Surface(onClick = { vm.changeLockPreviewVisibility(true) }, modifier = modifier.size(48.dp),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics { contentDescription = "Mostrar prévia do bloqueio" }) {
                Text("›", style = MaterialTheme.typography.headlineMedium)
            }
        }
        return
    }
    Surface(modifier = modifier.width(116.dp), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bloqueio", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.changeLockPreviewVisibility(false) }, modifier = Modifier.size(40.dp)) {
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
            Box(Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                val bitmap = vm.lockPreview
                if (bitmap != null) Image(bitmap.asImageBitmap(), "Prévia reduzida do bloqueio",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else if (vm.lockPreviewError != null) Text(vm.lockPreviewError!!,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
}
