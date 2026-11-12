package com.dockliquida.app.system

import android.content.Context
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
 * --------------------------------------------------------------------------
 * Troca automática de wallpaper. O WorkManager (agendador do sistema) executa isto de tempos em tempos,
 * mesmo com o app fechado e depois de reiniciar o celular.
 *
 * MELHORIA em relação ao app web: antes o app gerava e guardava todas as imagens prontas (JPEG) para o
 * Worker só aplicar. Agora o motor de vidro é nativo, então o Worker DESENHA a dock na hora, na
 * qualidade máxima, a partir da foto original — nada de arquivos duplicados, nada fica "desatualizado"
 * quando você muda o ajuste da dock.
 */
class RotationWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        applyNext(applicationContext)
        return Result.success()                  // se falhar, simplesmente espera o próximo ciclo
    }

    companion object {
        private const val WORK_NAME = "dock_rotation"
        private const val SP = "dockrotation"

        /** Liga (ou atualiza) a troca a cada `hours` horas. */
        fun schedule(ctx: Context, hours: Int) {
            val req = PeriodicWorkRequestBuilder<RotationWorker>(hours.toLong(), TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) }

        /** Aplica a próxima imagem da fila (ordem circular). Devolve false se não houver imagem ou se algo falhar. */
        fun applyNext(ctx: Context): Boolean {
            val app = ctx.applicationContext
            val store = Store.get(app)
            val prefs = Prefs.get(app)
            val queue = store.rotationQueue()
            if (queue.isEmpty()) return false
            val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
            val i = sp.getInt("next", 0) % queue.size
            val wall = queue[i]
            val bmp = WallpaperRenderer.renderFull(store.file(wall.id), wall.outW, wall.outH, prefs.dockFor(wall)) ?: return false
            return try {
                Actions.applyWallpaper(app, bmp, WallTarget.fromKey(prefs.rotTarget.value))
                sp.edit().putInt("next", (i + 1) % queue.size).apply()
                true
            } catch (e: Exception) { false } finally { bmp.recycle() }
        }
    }
}
