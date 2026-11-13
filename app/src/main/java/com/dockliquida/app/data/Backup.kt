package com.dockliquida.app.data

import android.content.Context
import android.net.Uri
import com.dockliquida.app.system.RotationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Backup.kt — arquivo de backup `.dckl` (Dock Líquida).
 * --------------------------------------------------------------------------
 * Um .dckl é um contêiner compactado (formato ZIP) com extensão própria:
 *   manifest.json  → identifica o arquivo (assinatura "DockLiquidaBackup") e a versão do formato
 *   library.json   → wallpapers, álbuns e o ajuste de dock/enquadramento de cada um
 *   prefs.json     → preferências (tema, qualidade, troca automática, última dock…)
 *   walls/<id>     → as fotos originais, byte a byte
 * As miniaturas NÃO entram (são recriadas ao restaurar), o que mantém o arquivo menor.
 */
object Backup {
    const val EXT = "dckl"
    private const val MAGIC = "DockLiquidaBackup"
    private const val VERSION = 1
    private val ID_RE = Regex("[A-Za-z0-9_-]{1,40}")               // só aceita nomes de arquivo "seguros" (evita caminhos maliciosos)

    /** Nome sugerido no seletor de arquivos, ex.: DockLiquida_2026-11-12_1430.dckl */
    fun suggestedName(): String = "DockLiquida_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + "." + EXT

    /** Grava o backup no destino escolhido pelo usuário. Devolve quantas imagens foram incluídas. */
    suspend fun export(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val store = Store.get(ctx); val prefs = Prefs.get(ctx)
        val walls = store.walls.value.filter { store.file(it.id).exists() }
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Não foi possível abrir o destino.")
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            fun text(name: String, s: String) { zip.putNextEntry(ZipEntry(name)); zip.write(s.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            text("manifest.json", JSONObject().put("magic", MAGIC).put("version", VERSION)
                .put("createdAt", System.currentTimeMillis()).put("images", walls.size).toString())
            text("library.json", store.exportIndexJson())
            text("prefs.json", prefs.exportJson())
            for (w in walls) {
                zip.putNextEntry(ZipEntry("walls/${w.id}"))
                store.file(w.id).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        walls.size
    }

    /**
     * Restaura um backup: SUBSTITUI a biblioteca e as preferências atuais. Primeiro lê e valida tudo numa pasta
     * temporária; só se estiver íntegro é que apaga os dados atuais — um arquivo inválido não destrói nada.
     * Devolve quantos wallpapers foram restaurados.
     */
    suspend fun import(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val store = Store.get(ctx); val prefs = Prefs.get(ctx)
        val tmp = File(ctx.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var manifest: String? = null; var library: String? = null; var prefsJson: String? = null
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        e.isDirectory -> {}
                        name == "manifest.json" -> manifest = zip.readBytes().toString(Charsets.UTF_8)
                        name == "library.json" -> library = zip.readBytes().toString(Charsets.UTF_8)
                        name == "prefs.json" -> prefsJson = zip.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("walls/") && ID_RE.matches(name.removePrefix("walls/")) ->
                            FileOutputStream(File(tmp, name.removePrefix("walls/"))).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            val m = manifest ?: throw IllegalStateException("Este arquivo não é um backup do Dock Líquida.")
            val head = JSONObject(m)
            if (head.optString("magic") != MAGIC) throw IllegalStateException("Este arquivo não é um backup do Dock Líquida.")
            if (head.optInt("version") > VERSION) throw IllegalStateException("Backup feito por uma versão mais nova do app. Atualize o app primeiro.")
            val lib = library ?: throw IllegalStateException("Backup incompleto (sem biblioteca).")
            JSONObject(lib)                                          // valida o JSON antes de apagar qualquer coisa

            // Segura a trava da troca automática: se uma troca estiver renderizando agora, esperamos ela terminar, e
            // nenhuma começa enquanto as imagens estão sendo apagadas e trocadas (antes a troca lia arquivos sumindo).
            synchronized(RotationWorker.applyLock) {
                store.wipeAll()                                      // a partir daqui os dados atuais são substituídos
                tmp.listFiles()?.forEach { f ->
                    val dst = File(store.wallsDir(), f.name)
                    if (!f.renameTo(dst)) f.copyTo(dst, overwrite = true)
                }
                store.restoreIndex(lib)
                prefsJson?.let { prefs.importJson(it) }
            }
            // O backup pode trazer "troca ligada/desligada" e outro intervalo: reaplica no WorkManager aqui, junto
            // com os dados, e não só na tela (se a tela fosse fechada no meio, o agendamento ficaria errado).
            RotationWorker.reschedule(ctx)
            store.rebuildThumbs()                                    // lento e a troca não usa miniaturas: fica fora da trava
            store.walls.value.size
        } finally { tmp.deleteRecursively() }
    }
}
