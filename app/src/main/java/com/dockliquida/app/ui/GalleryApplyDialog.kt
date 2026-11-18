package com.dockliquida.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dockliquida.app.system.Actions
import com.dockliquida.app.system.RotationWorker
import com.dockliquida.app.system.WallTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GalleryApplyDialog(
    wallId: String,
    vm: AppVM,
    onDismiss: () -> Unit,
    onApplied: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var applying by remember(wallId) { mutableStateOf(false) }
    var error by remember(wallId) { mutableStateOf<String?>(null) }

    fun apply(target: WallTarget) {
        if (applying) return
        applying = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val operationContext = currentCoroutineContext()
                    synchronized(RotationWorker.applyLock) {
                        operationContext.ensureActive()
                        val wall = vm.store.wall(wallId)
                            ?: throw IllegalStateException("A imagem não está mais na galeria.")
                        operationContext.ensureActive()
                        Actions.applyWall(appContext, vm.store, wall, vm.prefs.dockFor(wall), target) {
                            operationContext.ensureActive()
                            true
                        }
                    }
                }
                Toast.makeText(context, "Papel de parede aplicado!", Toast.LENGTH_SHORT).show()
                onApplied()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                error = "Memória insuficiente. Reduza o tamanho de saída no editor e tente novamente."
            } catch (e: Exception) {
                error = e.message ?: "Não foi possível aplicar o papel de parede."
            } finally {
                applying = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(if (applying) "Aplicando wallpaper…" else "Aplicar papel de parede") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (applying) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Preparando o papel de parede…")
                } else {
                    Text(if (com.dockliquida.app.BuildConfig.DEBUG || vm.store.wall(wallId)?.lock?.enabled == true)
                        "Inicial com Dock; bloqueio usa a foto e os ajustes salvos no editor."
                    else "Escolha onde aplicar a imagem selecionada com a Dock salva.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    for (target in WallTarget.values()) {
                        FilledTonalButton(onClick = { apply(target) }, modifier = Modifier.fillMaxWidth()) {
                            Text(target.label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!applying) TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
