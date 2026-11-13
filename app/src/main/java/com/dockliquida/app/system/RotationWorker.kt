package com.dockliquida.app.system

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dockliquida.app.data.Prefs
import com.dockliquida.app.data.Store
import com.dockliquida.app.data.Wall
import com.dockliquida.app.render.WallpaperRenderer
import java.util.concurrent.TimeUnit

/*
 * RotationWorker.kt
 * Troca automática: renderiza a imagem e sua Dock quando o WorkManager executa a tarefa.
 * O horário é aproximado: o Android pode adiar tarefas periódicas para economizar bateria.
 *
 * Regras que este arquivo garante:
 *  1) Só UMA troca roda por vez (applyLock): "Trocar agora", o agendamento e a restauração de backup não se atropelam.
 *  2) Uma imagem ruim (arquivo sumido, corrompido, estouro de memória) é PULADA, em vez de travar a fila para sempre.
 *  3) A posição na fila é guardada pelo ID da última imagem aplicada (e não só por um número), então adicionar,
 *     remover ou reordenar imagens não faz a fila repetir ou pular fotos.
 *  4) Se o Android recusar o wallpaper, a fila NÃO avança e o WorkManager tenta de novo (poucas vezes).
 *  5) Se o trabalho for cancelado/desligado no meio da renderização, o wallpaper não é aplicado.
 */
/** Resultado de uma tentativa de troca de wallpaper. */
enum class RotationOutcome { APPLIED, NO_IMAGES, FAILED, CANCELLED }

/**
 * Decide em que posição da fila a próxima troca começa. Função pura (não mexe em disco), por isso é fácil de testar.
 *  1) Achou a última imagem aplicada na fila (pelo ID)? Continua logo depois dela.
 *  2) Ela sumiu (foi apagada ou mudou de álbum)? Continua na primeira imagem que NÃO é mais antiga que ela — assim a
 *     próxima da fila não é pulada nem repetida. Se não sobrou nenhuma mais nova, volta ao começo.
 *  3) Instalação antiga (só tem o índice salvo)? Usa o índice, como a versão anterior fazia.
 * `queue` precisa estar ordenada por addedAt (o Store.rotationQueue() já entrega assim).
 */
internal fun pickRotationStart(queue: List<Wall>, lastId: String?, lastAddedAt: Long, legacyNext: Int): Int {
    if (queue.isEmpty()) return 0
    if (lastId != null) {
        val pos = queue.indexOfFirst { it.id == lastId }
        if (pos >= 0) return (pos + 1) % queue.size
        if (lastAddedAt > 0L) {
            val newer = queue.indexOfFirst { it.addedAt >= lastAddedAt }
            return if (newer >= 0) newer else 0
        }
    }
    return Math.floorMod(legacyNext, queue.size)
}

class RotationWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.rotOn.value) return Result.success() // trabalho antigo pendente após desligar (o ensureScheduled() limpa na próxima abertura)

        // "shouldContinue" é consultado ANTES de aplicar: isStopped vira true se o WorkManager cancelou este
        // trabalho (restrição deixou de valer, tempo esgotado, usuário desligou) — sem isso a thread seguiria
        // renderizando e aplicaria o wallpaper mesmo depois de cancelada.
        val outcome = applyNextInternal(applicationContext) { !isStopped && prefs.rotOn.value }

        return when (outcome) {
            RotationOutcome.APPLIED, RotationOutcome.NO_IMAGES, RotationOutcome.CANCELLED -> Result.success()
            // Falha do sistema (ex.: o Android recusou o wallpaper). Tenta de novo, mas só algumas vezes por ciclo:
            // retry infinito gasta bateria e nunca resolveria um problema permanente.
            RotationOutcome.FAILED -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "DockRotation"
        private const val WORK_NAME = "dock_rotation"
        private const val SP = "dockrotation"
        private const val KEY_NEXT = "next"        // posição (índice) usada só como plano B quando o ID guardado não existe mais
        private const val KEY_LAST_ID = "lastId"   // ID da última imagem aplicada com sucesso
        private const val KEY_LAST_ADDED = "lastAddedAt" // data de inclusão dessa imagem (referência se ela for apagada depois)
        private const val MAX_RETRIES = 3          // tentativas extras por ciclo quando o sistema recusa o wallpaper
        private const val MAX_CANDIDATES = 5       // quantas imagens ruins seguidas pulamos antes de desistir deste ciclo

        /**
         * Trava única da troca. Quem quiser mexer nos arquivos/biblioteca (ex.: restaurar backup) enquanto nenhuma
         * troca está renderizando usa `synchronized(RotationWorker.applyLock) { ... }`.
         */
        internal val applyLock = Any()

        /** Cria/atualiza o agendamento no WorkManager. `policy` decide o que fazer se já existir um. */
        private fun enqueue(ctx: Context, hours: Int, policy: ExistingPeriodicWorkPolicy) {
            val period = hours.coerceAtLeast(1).toLong()
            val req = PeriodicWorkRequestBuilder<RotationWorker>(period, TimeUnit.HOURS)
                // A primeira troca automática acontece depois de UM intervalo. Sem isso, ligar o interruptor (ou
                // abrir o app após restaurar dados) trocava o wallpaper na hora; para isso existe "Trocar agora".
                .setInitialDelay(period, TimeUnit.HOURS)
                // Renderizar em alta resolução gasta bastante: com bateria baixa o Android adia para depois.
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                // Se falhar, espera 15 min (linear) entre as tentativas, em vez do padrão de 30 s que martelava o sistema.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(WORK_NAME, policy, req)
        }

        /** Liga (ou atualiza) a troca a cada `hours` horas. UPDATE mantém a contagem de tempo do trabalho já existente. */
        fun schedule(ctx: Context, hours: Int) = enqueue(ctx, hours, ExistingPeriodicWorkPolicy.UPDATE)

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK_NAME)
        }

        /** Faz o agendamento refletir as preferências atuais (usado depois de restaurar um backup). */
        fun reschedule(ctx: Context) {
            val prefs = Prefs.get(ctx.applicationContext)
            if (prefs.rotOn.value) schedule(ctx, prefs.rotHours.value) else cancel(ctx)
        }

        /**
         * Chamado ao abrir o app. As preferências dizem "ligado", mas o agendamento do WorkManager pode ter se perdido
         * (backup automático do Android restaura as preferências e NÃO restaura o banco do WorkManager; limpeza de dados;
         * atualização que falhou…). KEEP não mexe num agendamento que já existe, então não reinicia a contagem do tempo.
         * Se estiver desligado, remove qualquer trabalho órfão.
         */
        fun ensureScheduled(ctx: Context) {
            val prefs = Prefs.get(ctx.applicationContext)
            if (prefs.rotOn.value) enqueue(ctx, prefs.rotHours.value, ExistingPeriodicWorkPolicy.KEEP) else cancel(ctx)
        }

        /** Troca manual ("Trocar agora"): funciona mesmo com o agendamento desativado. */
        fun applyNext(ctx: Context): Boolean = applyNextInternal(ctx) == RotationOutcome.APPLIED

        /** Igual a [applyNext], mas diz o motivo quando não troca (para a tela mostrar a mensagem certa). */
        fun applyNextResult(ctx: Context): RotationOutcome = applyNextInternal(ctx)

        private fun applyNextInternal(ctx: Context, shouldContinue: () -> Boolean = { true }): RotationOutcome =
            // synchronized = fila de espera: se já há uma troca renderizando, esta espera a outra terminar.
            synchronized(applyLock) {
                try {
                    applyLocked(ctx.applicationContext, shouldContinue)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Sem memória ao trocar o wallpaper", e)   // Error não é Exception: sem este catch ele escapava
                    RotationOutcome.FAILED
                } catch (e: Exception) {
                    Log.e(TAG, "Falha na troca do wallpaper; será tentado novamente se agendado", e)
                    RotationOutcome.FAILED
                }
            }

        /** Corpo da troca. Só chamar com o applyLock já adquirido. */
        private fun applyLocked(app: Context, shouldContinue: () -> Boolean): RotationOutcome {
            val store = Store.get(app)
            val prefs = Prefs.get(app)
            val queue = store.rotationQueue()
            if (queue.isEmpty()) {
                Log.i(TAG, "Sem imagens na galeria automática; nenhuma troca realizada")
                return RotationOutcome.NO_IMAGES
            }

            val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
            val start = startIndex(sp, queue)

            // Tenta a imagem da vez; se ela estiver ruim, passa para a seguinte (no máximo MAX_CANDIDATES).
            for (step in 0 until minOf(queue.size, MAX_CANDIDATES)) {
                if (!shouldContinue()) return RotationOutcome.CANCELLED
                val index = (start + step) % queue.size
                val wall = queue[index]

                val bitmap = renderWall(store, prefs, wall) ?: continue   // imagem ruim: pula

                try {
                    if (!shouldContinue()) return RotationOutcome.CANCELLED         // cancelado enquanto renderizava: não aplica
                    Actions.applyWallpaper(app, bitmap, WallTarget.fromKey(prefs.rotTarget.value))
                } finally {
                    bitmap.recycle()                                        // sempre libera, mesmo se o Android recusar
                }

                // Só avança a fila quando o Android aceita aplicar o wallpaper. commit() (síncrono) em vez de apply():
                // este código roda em thread de fundo e o processo pode ser encerrado logo em seguida.
                sp.edit()
                    .putString(KEY_LAST_ID, wall.id)
                    .putLong(KEY_LAST_ADDED, wall.addedAt)
                    .putInt(KEY_NEXT, (index + 1) % queue.size)
                    .commit()
                Log.i(TAG, "Wallpaper aplicado: ${wall.id} (${index + 1}/${queue.size})")
                return RotationOutcome.APPLIED
            }
            Log.e(TAG, "Nenhuma das próximas imagens pôde ser renderizada")
            return RotationOutcome.FAILED
        }

        /** Lê do SharedPreferences o que a troca anterior guardou e deixa [pickRotationStart] decidir. */
        private fun startIndex(sp: SharedPreferences, queue: List<Wall>): Int =
            pickRotationStart(queue, sp.getString(KEY_LAST_ID, null), sp.getLong(KEY_LAST_ADDED, 0L), sp.getInt(KEY_NEXT, 0))

        /** Renderiza uma imagem da fila. Devolve null (em vez de lançar erro) se ela não puder ser gerada. */
        private fun renderWall(store: Store, prefs: Prefs, wall: Wall): Bitmap? {
            val file = store.file(wall.id)
            if (!file.isFile) {                                           // apagada/restaurada por fora enquanto estava na fila
                Log.w(TAG, "Arquivo da imagem não existe: ${wall.id}")
                return null
            }
            return try {
                WallpaperRenderer.renderFull(file, wall.outW, wall.outH, prefs.dockFor(wall))
                    ?: run { Log.e(TAG, "Falha ao renderizar wallpaper: ${wall.id}"); null }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Sem memória para renderizar ${wall.id}", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao renderizar ${wall.id}", e)
                null
            }
        }
    }
}
