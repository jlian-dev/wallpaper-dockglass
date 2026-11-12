package com.dockliquida.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dockliquida.app.BuildConfig
import com.dockliquida.app.data.Backup
import com.dockliquida.app.system.RotationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(on, onChange)
    }
}

/** Ajustes (Material 3): cartões em camadas com interruptores e seletores. */
@Composable
fun SettingsScreen(vm: AppVM, bottomPad: Dp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = vm.prefs
    val theme by p.theme.collectAsState(); val quality by p.quality.collectAsState()
    val individual by p.individual.collectAsState(); val glassFull by p.glassFull.collectAsState()
    val dynamicColors by p.dynamicColors.collectAsState()
    val rotOn by p.rotOn.collectAsState(); val hours by p.rotHours.collectAsState(); val target by p.rotTarget.collectAsState()
    val walls by vm.store.walls.collectAsState()
    val queue = remember(walls) { vm.store.rotationQueue().size }

    // ---- backup .dckl ----
    var busy by remember { mutableStateOf<String?>(null) }          // texto do "aguarde" (null = livre)
    var askRestore by remember { mutableStateOf(false) }            // confirmação antes de restaurar
    // Exportar: o seletor do Android deixa você escolher a pasta e o nome (sugerimos DockLiquida_data.dckl)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            busy = "Gerando backup…"
            try {
                val n = Backup.export(ctx, uri)
                Toast.makeText(ctx, "Backup salvo com $n imagem(ns).", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) { Toast.makeText(ctx, "Falha no backup: ${e.message}", Toast.LENGTH_LONG).show() } finally { busy = null }
        }
    }
    // Restaurar: escolhe um arquivo .dckl (o tipo MIME dele é desconhecido, então liberamos todos)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = "Restaurando backup…"
            try {
                val n = Backup.import(ctx, uri)
                if (p.rotOn.value) RotationWorker.schedule(ctx, p.rotHours.value) else RotationWorker.cancel(ctx)   // reaplica a troca automática
                Toast.makeText(ctx, "Backup restaurado: $n imagem(ns).", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) { Toast.makeText(ctx, e.message ?: "Falha ao restaurar.", Toast.LENGTH_LONG).show() } finally { busy = null }
        }
    }

    Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = bottomPad + 16.dp)) {
        Text("Ajustes", Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineMedium)
        SectionCard("Aparência") {
            Text("Tema da interface", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Segmented(listOf("auto" to "Auto", "claro" to "Claro", "escuro" to "Escuro"), theme, { p.setTheme(it) }, Modifier.fillMaxWidth())
            SwitchRow("Cores do sistema (Material You)", "Ligado: cores do wallpaper. Desligado: branco, preto e cinza.", dynamicColors) { p.setDynamicColors(it) }
            // A amostra acompanha o tema efetivamente aplicado: Material You ou cores neutras.
            val colors = MaterialTheme.colorScheme
            Text(
                "Paleta de cores",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    colors.primary,
                    colors.primaryContainer,
                    colors.secondaryContainer,
                    colors.surfaceVariant
                ).forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .background(color, RoundedCornerShape(7.dp))
                            .border(1.dp, colors.outline.copy(alpha = 0.25f), RoundedCornerShape(7.dp))
                    )
                }
            }
            SwitchRow("Liquid Glass completo", "Ligado: vidro na barra e botões. Desligado: Material 3.", glassFull) { p.setGlassFull(it) }
        }
        SectionCard("Editor") {
            SwitchRow("Dock individual", "Cada wallpaper guarda a própria Dock", individual) { p.setIndividual(it) }
            Text("Qualidade da prévia (rápida economiza bateria)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Segmented(listOf("0.6" to "Rápida", "1.0" to "Normal", "1.6" to "Nítida"), quality.toString(), { p.setQuality(it.toFloat()) }, Modifier.fillMaxWidth())
        }
        SectionCard("Troca automática") {
            SwitchRow("Trocar sozinho", "Funciona com o app fechado · $queue imagem(ns) na fila", rotOn) {
                p.setRotOn(it); if (it) RotationWorker.schedule(ctx, hours) else RotationWorker.cancel(ctx)
            }
            Text("Intervalo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Segmented(listOf(6, 12, 24, 48).map { "$it" to "$it h" }, hours.toString(), { p.setRotHours(it.toInt()); if (rotOn) RotationWorker.schedule(ctx, it.toInt()) }, Modifier.fillMaxWidth())
            Text("Aplicar em", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Segmented(listOf("home" to "Início", "lock" to "Bloqueio", "both" to "Ambas"), target, { p.setRotTarget(it) }, Modifier.fillMaxWidth())
            FilledTonalButton(onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { RotationWorker.applyNext(ctx) }
                    Toast.makeText(ctx, if (ok) "Wallpaper trocado!" else "Adicione imagens na galeria automática.", Toast.LENGTH_SHORT).show()
                }
            }, Modifier.fillMaxWidth()) { Text("Trocar agora") }
        }
        SectionCard("Backup") {
            Text(
                "Salva num único arquivo .${Backup.EXT} as suas imagens, álbuns, ajustes de cada dock e preferências.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { exportLauncher.launch(Backup.suggestedName()) }, Modifier.fillMaxWidth()) { Text("Exportar backup (.${Backup.EXT})") }
            OutlinedButton(onClick = { askRestore = true }, Modifier.fillMaxWidth()) { Text("Restaurar backup") }
        }
        SectionCard("Sobre") {
            Text("Dock Líquida ${BuildConfig.VERSION_NAME} · app 100% nativo (Kotlin + Jetpack Compose)")
            Text("Efeito de refração baseado no artigo “Liquid Glass in the Browser”, de kube.io.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { p.resetAll(); RotationWorker.cancel(ctx) }, Modifier.fillMaxWidth()) { Text("Restaurar configurações padrão") }
        }
    }

    if (askRestore) AlertDialog(
        onDismissRequest = { askRestore = false },
        title = { Text("Restaurar backup?") },
        text = { Text("Isso SUBSTITUI as imagens, álbuns e ajustes atuais pelos do arquivo. Se quiser guardar o que tem agora, exporte um backup antes.") },
        confirmButton = { TextButton(onClick = { askRestore = false; importLauncher.launch(arrayOf("*/*")) }) { Text("Escolher arquivo") } },
        dismissButton = { TextButton(onClick = { askRestore = false }) { Text("Cancelar") } }
    )
    busy?.let { txt ->
        AlertDialog(
            onDismissRequest = {}, confirmButton = {},                // sem botões: não dá para fechar enquanto trabalha
            title = { Text(txt) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        )
    }
}
