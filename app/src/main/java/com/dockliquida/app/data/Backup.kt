package com.dockliquida.app.data

import android.content.Context
import android.net.Uri
import com.dockliquida.app.render.ImageIo
import com.dockliquida.app.system.RotationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Verifica todas as fotos antes de entrar na etapa que substitui a biblioteca. */
internal fun verifyBackupImages(dir: File, ids: Set<String>, readableHeader: (File) -> Boolean) {
    for (id in ids) {
        val image = File(dir, id)
        require(image.isFile && image.length() > 0) { "Backup incompleto: falta uma foto da biblioteca ($id)." }
        require(readableHeader(image)) { "Backup inválido: não foi possível ler a foto $id." }
    }
}

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
    private const val VERSION = 2
    private const val MAX_TEXT_BYTES = 32L * 1024 * 1024        // manifest/biblioteca/preferências: 32 MB já é enorme
    private const val MAX_IMAGE_BYTES = 512L * 1024 * 1024      // uma foto dentro do backup
    private const val MAX_ENTRIES = 50_000                      // quantos arquivos o backup pode ter
    private val ID_RE = Regex("[A-Za-z0-9_-]{1,40}")               // só aceita nomes de arquivo "seguros" (evita caminhos maliciosos)

    /** Nome sugerido no seletor de arquivos, ex.: DockLiquida_2026-11-12_1430.dckl */
    fun suggestedName(): String = "DockLiquida_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + "." + EXT

    /**
     * Copia no máximo `max` bytes. Arquivo maior que isso = backup recusado. Protege contra um .dckl corrompido ou
     * malicioso (o zip diz ser pequeno mas "explode" ao abrir) que encheria a memória ou o armazenamento do aparelho.
     */
    private fun java.io.InputStream.copyLimited(out: java.io.OutputStream, max: Long) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalStateException("Backup inválido: um dos arquivos internos é grande demais.")
            out.write(buf, 0, n)
        }
    }
    private fun java.io.InputStream.readLimited(max: Long): String {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(out, max)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun validatePrefs(json: String) {
        val root = JSONObject(json)
        for (key in root.keys()) {
            val entry = root.getJSONObject(key)
            val type = entry.getString("t")
            val expected = when (key) {
                "tema", "lastWall", "lastDock", "trocaAlvo" -> "s"
                "qualidade" -> "f"
                "trocaHoras" -> "i"
                "dockIndividual", "glassFull", "dynamicColors", "amoled", "trocaAtiva" -> "b"
                else -> type
            }
            require(type == expected) { "Backup inválido: tipo incorreto para a preferência '$key'." }
            when (type) {
                "b" -> entry.getBoolean("v")
                "i" -> entry.getInt("v")
                "l" -> entry.getLong("v")
                "f" -> entry.getDouble("v")
                "s" -> entry.getString("v")
                else -> throw IllegalStateException("Backup inválido (tipo de preferência).")
            }
        }
    }

    /** Lista os arquivos a partir do próprio índice que será gravado ou restaurado. */
    private fun requiredAssets(library: JSONObject): Set<String> {
        val records = library.getJSONArray("walls")
        val mainIds = mutableSetOf<String>()
        val assets = linkedSetOf<String>()
        for (i in 0 until records.length()) {
            val wall = records.getJSONObject(i)
            val id = wall.getString("id")
            require(ID_RE.matches(id) && mainIds.add(id)) { "Backup inválido: identificador de wallpaper inválido ou repetido." }
            Kind.valueOf(wall.optString("kind", "FIXED"))
            OutMode.valueOf(wall.optString("outMode", "AUTO"))
            require(wall.getInt("origW") > 0 && wall.getInt("origH") > 0 &&
                wall.getInt("outW") > 0 && wall.getInt("outH") > 0) { "Backup inválido: dimensões de wallpaper inválidas." }
            assets.add(id)
            if (!wall.isNull("lock")) {
                lockFromJson(wall.getJSONObject("lock")).imageId?.let { assets.add(it) }
            }
        }
        val albums = library.getJSONArray("albums")
        val ids = mutableSetOf<String>()
        for (i in 0 until albums.length()) {
            val album = albums.getJSONObject(i)
            Kind.valueOf(album.optString("kind", "FIXED"))
            require(ids.add(album.getString("id"))) { "Backup inválido: álbum repetido." }
        }
        return assets
    }

    /** Grava o backup no destino escolhido pelo usuário. Devolve quantas imagens foram incluídas. */
    suspend fun export(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val store = Store.get(ctx); val prefs = Prefs.get(ctx)
        val library = store.exportIndexJson()
        val index = JSONObject(library)
        val wallCount = index.getJSONArray("walls").length()
        val assets = requiredAssets(index)
        check(assets.size <= MAX_ENTRIES - 3) { "A biblioteca excede a quantidade de arquivos aceita pelo backup." }
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Não foi possível abrir o destino.")
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            fun text(name: String, s: String) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                check(bytes.size.toLong() <= MAX_TEXT_BYTES) { "Os dados da biblioteca excedem o tamanho aceito pelo backup." }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            text("manifest.json", JSONObject().put("magic", MAGIC).put("version", VERSION)
                .put("createdAt", System.currentTimeMillis()).put("images", wallCount).toString())
            text("library.json", library)
            text("prefs.json", prefs.exportJson())
            for (id in assets) {
                currentCoroutineContext().ensureActive()
                val file = store.file(id)
                check(file.isFile && file.length() > 0) { "Backup interrompido: uma foto da biblioteca está ausente ou vazia ($id)." }
                zip.putNextEntry(ZipEntry("walls/$id"))
                file.inputStream().use { it.copyLimited(zip, MAX_IMAGE_BYTES) }
                zip.closeEntry()
            }
        }
        wallCount
    }

    /**
     * Restaura um backup: SUBSTITUI a biblioteca e as preferências atuais. Primeiro lê e valida tudo numa pasta
     * temporária; só se estiver íntegro é que apaga os dados atuais — um arquivo inválido não destrói nada.
     * Devolve quantos wallpapers foram restaurados.
     */
    suspend fun import(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val store = Store.get(ctx); val prefs = Prefs.get(ctx)
        val restoreContext = currentCoroutineContext()
        val tmp = File(ctx.cacheDir, "restore_${UUID.randomUUID()}")
        check(tmp.mkdir()) { "Não foi possível preparar a restauração." }
        try {
            var manifest: String? = null; var library: String? = null; var prefsJson: String? = null
            var entries = 0
            val seen = HashSet<String>()
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    restoreContext.ensureActive()
                    val e = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IllegalStateException("Backup inválido: arquivos demais.")
                    val name = e.name
                    if (!e.isDirectory) require(seen.add(name)) { "Backup inválido: arquivo interno repetido." }
                    when {
                        e.isDirectory -> {}
                        name == "manifest.json" -> manifest = zip.readLimited(MAX_TEXT_BYTES)
                        name == "library.json" -> library = zip.readLimited(MAX_TEXT_BYTES)
                        name == "prefs.json" -> prefsJson = zip.readLimited(MAX_TEXT_BYTES)
                        name.startsWith("walls/") && ID_RE.matches(name.removePrefix("walls/")) ->
                            FileOutputStream(File(tmp, name.removePrefix("walls/"))).use { zip.copyLimited(it, MAX_IMAGE_BYTES) }
                    }
                    zip.closeEntry()
                }
            }
            val m = manifest ?: throw IllegalStateException("Este arquivo não é um backup do Dock Líquida.")
            val head = JSONObject(m)
            if (head.optString("magic") != MAGIC) throw IllegalStateException("Este arquivo não é um backup do Dock Líquida.")
            val version = head.getInt("version")
            if (version > VERSION) throw IllegalStateException("Backup feito por uma versão mais nova do app. Atualize o app primeiro.")
            require(version >= 1) { "Versão de backup inválida." }
            val lib = library ?: throw IllegalStateException("Backup incompleto (sem biblioteca).")
            val libraryRoot = JSONObject(lib)
            if (libraryRoot.opt("walls") !is JSONArray || libraryRoot.opt("albums") !is JSONArray)
                throw IllegalStateException("Backup inválido (estrutura da biblioteca).")
            val records = libraryRoot.getJSONArray("walls")
            val assets = requiredAssets(libraryRoot)
            require(!head.has("images") || head.getInt("images") == records.length()) { "Backup incompleto: a contagem de wallpapers difere do índice." }
            verifyBackupImages(tmp, assets) { image ->
                restoreContext.ensureActive()
                val info = ImageIo.info(image)
                info.width > 0 && info.height > 0
            }
            prefsJson?.let { validatePrefs(it) }

            // Segura a trava da troca automática: se uma troca estiver renderizando agora, esperamos ela terminar, e
            // nenhuma começa enquanto as imagens estão sendo apagadas e trocadas (antes a troca lia arquivos sumindo).
            synchronized(RotationWorker.applyLock) {
                restoreContext.ensureActive()
                store.wipeAll()                                      // a partir daqui os dados atuais são substituídos
                for (id in assets) {
                    val f = File(tmp, id)
                    val dst = File(store.wallsDir(), id)
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
