package com.dockliquida.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dockliquida.app.data.OutMode
import com.dockliquida.app.glass.BevelProfile
import com.dockliquida.app.glass.GlassStyle
import com.dockliquida.app.system.DockOverlay
import com.dockliquida.app.system.WallTarget
import kotlin.math.roundToInt

/*
 * EditorScreen.kt — editor no layout da versão anterior: botões de vidro no topo (voltar / ampliar), prévia no meio
 * e painel Material 3 embaixo (abas Base | Dock + menu ⋮). O painel NUNCA cobre a imagem.
 */
@Composable
fun EditorScreen(vm: EditorVM, wallId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var big by remember { mutableStateOf(false) }          // prévia ampliada (esconde o painel)
    var tab by remember { mutableStateOf("base") }         // base | dock
    var menu by remember { mutableStateOf(false) }
    var askTarget by remember { mutableStateOf(false) }    // diálogo "Aplicar em…"
    val cs = MaterialTheme.colorScheme
    // Seletor do Android para escolher a nova foto (não precisa de permissão de armazenamento)
    val swapPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.replaceImage(uri) }

    LaunchedEffect(wallId) { vm.open(wallId) }
    BackHandler { vm.close(); onClose() }

    // Resultado do alinhamento sobre a tela inicial (sobreposição)
    val overlayResult by DockOverlay.result.collectAsState()
    LaunchedEffect(overlayResult) { overlayResult?.let { vm.applyOverlay(it) } }
    LaunchedEffect(vm.message) { vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null } }

    Box(Modifier.fillMaxSize()) {
        GlassScope(
            Modifier.fillMaxSize(),
            chrome = {
                // ---- topo: voltar e ampliar em vidro (desfocam a prévia quando ela passa por baixo) ----
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { vm.close(); onClose() })
                    GlassIconButton(if (big) DockIcons.FullscreenExit else DockIcons.Fullscreen, if (big) "Mostrar painel" else "Ampliar prévia", { big = !big })
                }
            }
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 4.dp)) {
                PreviewArea(vm, Modifier.weight(1.8f))          // prévia com ~30% mais altura que o painel

                if (!big) {
                    // ---- painel Material 3: superfície em camada, cantos de 28 dp no topo ----
                    Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = cs.surfaceContainer, tonalElevation = 2.dp) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Segmented(listOf("base" to "Base", "dock" to "Dock"), tab, { tab = it }, Modifier.weight(1f))
                                Box(Modifier.padding(start = 10.dp)) {
                                    FilledTonalIconButton(onClick = { menu = true }, Modifier.size(52.dp)) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Baixar PNG") }, onClick = { menu = false; vm.exportPng(false) })
                                        DropdownMenuItem(text = { Text("Baixar em alta resolução") }, onClick = { menu = false; vm.exportPng(true) })
                                        DropdownMenuItem(text = { Text("Aplicar como wallpaper") }, onClick = { menu = false; askTarget = true })
                                    }
                                }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp).navigationBarsPadding()) {
                                if (tab == "base") BasePanel(vm, onSwapPhoto = {
                                    swapPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) else DockPanel(vm)
                            }
                        }
                    }
                } else Box(Modifier.navigationBarsPadding())
            }
        }

        // ---- "carregando" por cima de tudo (bloqueia toques) ----
        vm.busy?.let { txt ->
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    Text(txt, color = Color.White)
                }
            }
        }
    }

    if (askTarget) {
        AlertDialog(
            onDismissRequest = { askTarget = false },
            title = { Text("Aplicar wallpaper em") },
            text = { Column { for (t in WallTarget.values()) TextButton(onClick = { askTarget = false; vm.applyWallpaper(t) }, modifier = Modifier.fillMaxWidth()) { Text(t.label) } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { askTarget = false }) { Text("Cancelar") } }
        )
    }
}

/**
 * Prévia com gestos (composable próprio: a troca do bitmap a cada quadro NÃO recompõe os sliders).
 * A imagem tem o tamanho EXATO da geometria, então o toque corresponde ao pixel certo da saída.
 */
@Composable
private fun PreviewArea(vm: EditorVM, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 62.dp, bottom = 2.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { vm.setBox(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(vm) {
                        // Arrastar a dock move a dock; arrastar o resto move o papel de parede; pinça dá zoom.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            vm.beginGesture()
                            val g = vm.geometry()
                            val onDock = vm.hitDock((down.position.x - g.ox) / g.scale, (down.position.y - g.oy) / g.scale)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (pressed >= 2 && zoom != 1f) vm.zoomBy(zoom)
                                if (pan != Offset.Zero) {
                                    if (onDock && pressed < 2) vm.dragDock(pan.x / g.scale, pan.y / g.scale)
                                    else vm.panWallpaper(pan.x / g.scale, pan.y / g.scale)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = vm.preview
                if (bmp != null) {
                    val g = vm.geometry()
                    val d = LocalDensity.current
                    val shape = RoundedCornerShape(24.dp)
                    Image(
                        bmp.asImageBitmap(), "Prévia",
                        Modifier.size(with(d) { (vm.outW * g.scale).toDp() }, with(d) { (vm.outH * g.scale).toDp() }).clip(shape),
                        contentScale = ContentScale.FillBounds
                    )
                } else CircularProgressIndicator()
            }
        }
        // Legenda como na versão anterior: tamanho, % da prévia e aviso se a foto estiver muito ampliada
        val up = vm.upscale()
        val pct = (vm.shownFactor * 100).roundToInt()
        Text(
            "${vm.outW} × ${vm.outH} px (prévia a $pct%, o PNG sai em 100%)" +
                if (up > 1.05) " · papel de parede ampliado ${"%.2f".format(up)}×: pode perder nitidez" else "",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================================ aba BASE
@Composable
private fun BasePanel(vm: EditorVM, onSwapPhoto: () -> Unit) {
    val p = vm.params
    // Reutiliza o vidro da interface: mais fino e com a mesma refração/contorno dos outros botões.

    Button(
        onClick = onSwapPhoto,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Icon(
            imageVector = DockIcons.Gallery,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = "Trocar imagem",
            style = MaterialTheme.typography.labelLarge
        )
    }

    SectionCard("Tamanho do papel de parede") {
        ChipRow(OutMode.values().map { it.name to it.label }, vm.outMode.name) { vm.setOutMode(OutMode.valueOf(it)) }
        if (vm.outMode == OutMode.CUSTOM) {
            // Largura e altura (só no modo Personalizado): aplica apenas números válidos
            var wTxt by remember(vm.outW) { mutableStateOf(vm.outW.toString()) }
            var hTxt by remember(vm.outH) { mutableStateOf(vm.outH.toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(wTxt, { wTxt = it.filter(Char::isDigit).take(4); wTxt.toIntOrNull()?.let { w -> if (w >= 64) vm.setOutMode(OutMode.CUSTOM, w, vm.outH) } },
                    label = { Text("Largura") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(hTxt, { hTxt = it.filter(Char::isDigit).take(4); hTxt.toIntOrNull()?.let { h -> if (h >= 64) vm.setOutMode(OutMode.CUSTOM, vm.outW, h) } },
                    label = { Text("Altura") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        } else {
            Text("${vm.outW} × ${vm.outH} px", style = MaterialTheme.typography.bodyMedium)
        }
    }
    SectionCard("Enquadramento e desfoque") {
        LabeledSlider("Desfoque do papel de parede", p.wpBlur.toFloat(), 0f..60f, "${p.wpBlur}") { v -> vm.update { it.copy(wpBlur = v.roundToInt()) } }
        LabeledSlider("Zoom (corta mais)", p.zoom.toFloat(), 100f..400f, "${p.zoom}%") { v -> vm.update { it.copy(zoom = v.roundToInt()) } }
        LabeledSlider("Mover na horizontal", p.panX.toFloat(), -100f..100f, "${p.panX}") { v -> vm.update { it.copy(panX = v.roundToInt()) } }
        LabeledSlider("Mover na vertical", p.panY.toFloat(), -100f..100f, "${p.panY}") { v -> vm.update { it.copy(panY = v.roundToInt()) } }
        FilledTonalButton(onClick = { vm.resetFraming() }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar enquadramento") }
    }
}

// ============================================================================================ aba DOCK
@Composable
private fun DockPanel(vm: EditorVM) {
    val p = vm.params
    SectionCard("Posição da dock (precisão de 1 px)") {
        Button(
            onClick = { vm.startOverlay() }, Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        ) { Text("Alinhar sobre a tela inicial", style = MaterialTheme.typography.titleSmall) }
        Stepper("Posição Y", p.y) { v -> vm.update { it.copy(y = v) } }
        Stepper("Posição X", p.x) { v -> vm.update { it.copy(x = v) } }
        Stepper("Largura", p.w) { v -> vm.update { it.copy(w = v.coerceAtLeast(20)) } }
        Stepper("Altura", p.h) { v -> vm.update { it.copy(h = v.coerceAtLeast(20)) } }
        Stepper("Curvatura", p.r) { v -> vm.update { it.copy(r = v.coerceAtLeast(0)) } }
    }
    SectionCard("Efeito de vidro") {
        ChipRow(GlassStyle.values().map { it.name to it.label }, vm.style?.name) { vm.applyStyle(GlassStyle.valueOf(it)) }
        Text("Formato do bisel", style = MaterialTheme.typography.labelMedium)
        ChipRow(BevelProfile.values().map { it.name to it.label }, p.profile.name) { k -> vm.update { it.copy(profile = BevelProfile.valueOf(k)) } }
        LabeledSlider("Desfoque do vidro", p.blur.toFloat(), 0f..60f, "${p.blur}") { v -> vm.update { it.copy(blur = v.roundToInt()) } }
        LabeledSlider("Largura do bisel", p.bevel.toFloat(), 8f..150f, "${p.bevel}") { v -> vm.update { it.copy(bevel = v.roundToInt()) } }
        LabeledSlider("Espessura do vidro", p.thick.toFloat(), 4f..200f, "${p.thick}") { v -> vm.update { it.copy(thick = v.roundToInt()) } }
        LabeledSlider("Índice de refração", p.ior, 1f..2f, "%.2f".format(p.ior)) { v -> vm.update { it.copy(ior = (v * 100).roundToInt() / 100f) } }
        LabeledSlider("Escala da refração", p.scale.toFloat(), 0f..150f, "${p.scale}%") { v -> vm.update { it.copy(scale = v.roundToInt()) } }
        LabeledSlider("Aberração cromática", p.chroma.toFloat(), 0f..30f, "${p.chroma}%") { v -> vm.update { it.copy(chroma = v.roundToInt()) } }
        LabeledSlider("Brilho especular", p.shine.toFloat(), 0f..100f, "${p.shine}") { v -> vm.update { it.copy(shine = v.roundToInt()) } }
        LabeledSlider("Ângulo da luz", p.angle.toFloat(), -180f..180f, "${p.angle}°") { v -> vm.update { it.copy(angle = v.roundToInt()) } }
        FilledTonalButton(onClick = { vm.resetDock() }, modifier = Modifier.fillMaxWidth()) { Text("Repor valores padrão") }
    }
}

/** Ajuste fino como na versão anterior: [−1] [valor] [+1] com botões e campo de contorno (Material 3). */
@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        StepBtn("−1") { onChange(value - 1) }
        Box(
            Modifier.padding(horizontal = 6.dp).width(88.dp).height(48.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Text("$value", style = MaterialTheme.typography.bodyLarge) }
        StepBtn("+1") { onChange(value + 1) }
    }
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, Modifier.size(width = 56.dp, height = 48.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(0.dp)) {
        Text(text, fontSize = 15.sp, maxLines = 1)
    }
}
