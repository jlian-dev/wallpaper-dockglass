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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect

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
    // Guarda a última mensagem de erro para ela não virar texto vazio enquanto faz o fade de saída.
    var lastError by remember(wallId) { mutableStateOf("") }
    LaunchedEffect(error) { error?.let { lastError = it } }

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
        // O título e o conteúdo trocam com um fade entre "escolher onde aplicar" e "aplicando…".
        title = { Crossfade(targetState = applying, animationSpec = tween(200), label = "applyTitle") { busyNow ->
            Text(if (busyNow) "Aplicando wallpaper…" else "Aplicar papel de parede")
        } },
        text = {
          Crossfade(targetState = applying, animationSpec = tween(220), label = "applyBody") { busyNow ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busyNow) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Preparando o papel de parede…")
                } else {
                    Text(if (com.dockliquida.app.BuildConfig.DEBUG || vm.store.wall(wallId)?.lock?.enabled == true)
                        "Inicial com Dock; bloqueio usa a foto e os ajustes salvos no editor."
                    else "Escolha onde aplicar a imagem selecionada com a Dock salva.")
                    AnimatedVisibility(
                        visible = error != null,
                        enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                    ) { Text(error ?: lastError, color = MaterialTheme.colorScheme.error) }
                    for ((index, target) in WallTarget.values().withIndex()) {
                        // Os botões entram em cascata e afundam ao toque.
                        FilledTonalButton(onClick = { apply(target) }, modifier = Modifier.cascadeIn(index).fillMaxWidth().pressScale()) {
                            Text(target.label)
                        }
                    }
                }
            }
          }
        },
        confirmButton = {},
        dismissButton = {
            AnimatedVisibility(visible = !applying, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}
