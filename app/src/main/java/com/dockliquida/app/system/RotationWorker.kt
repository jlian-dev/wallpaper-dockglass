package com.dockliquida.app.system

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dockliquida.app.data.Prefs
import com.dockliquida.app.data.Store
import com.dockliquida.app.render.WallpaperRenderer
import java.util.concurrent.TimeUnit

/*
 * RotationWorker.kt
 * Troca automática: renderiza a imagem e sua Dock quando o WorkManager executa a tarefa.
 * O horário é aproximado: o Android pode adiar tarefas periódicas para economizar bateria.
 */
class RotationWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.rotOn.value) return Result.success() // trabalho antigo pendente após desligar

        return when (applyNextInternal(applicationContext)) {
            ApplyResult.APPLIED, ApplyResult.NO_IMAGES -> Result.success()
            ApplyResult.FAILED -> Result.retry() // WorkManager usa backoff, não perde a falha
        }
    }

    companion object {
        private const val TAG = "DockRotation"
        private const val WORK_NAME = "dock_rotation"
        private const val SP = "dockrotation"

        private enum class ApplyResult { APPLIED, NO_IMAGES, FAILED }

        /** Liga (ou atualiza) a troca a cada `hours` horas. */
        fun schedule(ctx: Context, hours: Int) {
            val req = PeriodicWorkRequestBuilder<RotationWorker>(hours.toLong(), TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }

        /** Troca manual ("Trocar agora"): funciona mesmo com o agendamento desativado. */
        fun applyNext(ctx: Context): Boolean = applyNextInternal(ctx) == ApplyResult.APPLIED

        private fun applyNextInternal(ctx: Context): ApplyResult {
            val app = ctx.applicationContext
            var bitmap: android.graphics.Bitmap? = null
            return try {
                val store = Store.get(app)
                val prefs = Prefs.get(app)
                val queue = store.rotationQueue()
                if (queue.isEmpty()) {
                    Log.i(TAG, "Sem imagens na galeria automática; nenhuma troca realizada")
                    return ApplyResult.NO_IMAGES
                }

                val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
                val index = Math.floorMod(sp.getInt("next", 0), queue.size)
                val wall = queue[index]
                bitmap = WallpaperRenderer.renderFull(
                    store.file(wall.id), wall.outW, wall.outH, prefs.dockFor(wall)
                )
                val rendered = bitmap
                if (rendered == null) {
                    Log.e(TAG, "Falha ao renderizar wallpaper: ${wall.id}")
                    return ApplyResult.FAILED
                }

                Actions.applyWallpaper(app, rendered, WallTarget.fromKey(prefs.rotTarget.value))
                // Só avança a fila quando o Android aceita aplicar o wallpaper.
                sp.edit().putInt("next", (index + 1) % queue.size).apply()
                Log.i(TAG, "Wallpaper aplicado: ${wall.id} (${index + 1}/${queue.size})")
                ApplyResult.APPLIED
            } catch (e: Exception) {
                Log.e(TAG, "Falha na troca do wallpaper; será tentado novamente se agendado", e)
                ApplyResult.FAILED
            } finally {
                bitmap?.recycle()
            }
        }
    }
}
