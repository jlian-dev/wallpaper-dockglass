package com.dockliquida.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.dockliquida.app.glass.BevelProfile
import com.dockliquida.app.glass.GlassParams
import com.dockliquida.app.render.ImageIo
import com.dockliquida.app.system.Actions
import com.dockliquida.app.system.RotationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Store.kt
 * --------------------------------------------------------------------------
 * A "biblioteca" do app: wallpapers, álbuns e o ajuste de dock de cada um. No app web isso ficava no
 * IndexedDB/localStorage; aqui são arquivos normais na pasta privada do app (filesDir):
 *   walls/<id>    → a foto original, byte a byte (nenhuma recompressão: zero perda de qualidade)
 *   thumbs/<id>.jpg → miniatura de 360 px para a grade
 *   library.json  → a lista (wallpapers + álbuns + ajustes)
 * O estado é exposto como StateFlow: a interface (Compose) "assina" e se atualiza sozinha.
 */

/** Onde o wallpaper aparece: galeria fixa ou galeria de troca automática. */
enum class Kind { FIXED, ROTATION }

/** Como o tamanho de saída é decidido (igual ao seletor "Papel de parede: tamanho" do app web). */
enum class OutMode(val label: String) { AUTO("Igual à imagem"), DEVICE("Tela deste aparelho"), CUSTOM("Personalizado") }

data class Wall(
    val id: String,
    val kind: Kind,
    val name: String,
    val addedAt: Long,
    val albumId: String?,          // null = sem álbum
    val origW: Int,                // tamanho da foto já com a orientação aplicada
    val origH: Int,
    val outMode: OutMode,
    val outW: Int,                 // tamanho de saída em px
    val outH: Int,
    val dock: GlassParams,         // a dock (posição, vidro, enquadramento) DESTE wallpaper
    val rev: Int = 0,              // sobe a cada "Trocar foto" (força a interface a recarregar a miniatura)
    val lock: LockWallpaper? = null
)

data class Album(val id: String, val name: String, val kind: Kind, val slide: Boolean = false)

/**
 * Tira referências a álbuns que não existem (ou que são da OUTRA galeria): a imagem volta a ficar "sem álbum".
 * Sem isso, uma foto apontando para um álbum apagado some da galeria (ela só aparece dentro do álbum) mas continua
 * na fila da troca automática, como uma imagem "fantasma".
 */
internal fun sanitizeAlbumRefs(walls: List<Wall>, albums: List<Album>): List<Wall> {
    val kindById = albums.associate { it.id to it.kind }
    return walls.map { w -> if (w.albumId != null && kindById[w.albumId] != w.kind) w.copy(albumId = null) else w }
}

/** Nova lista de álbuns ao (des)marcar o slideshow, ou null se passaria do limite de 2 álbuns marcados. */
internal fun withSlide(albums: List<Album>, id: String, on: Boolean): List<Album>? {
    val others = albums.count { it.kind == Kind.ROTATION && it.slide && it.id != id }   // os OUTROS já marcados
    if (on && others >= 2) return null
    return albums.map { if (it.id == id) it.copy(slide = on) else it }
}

/** (De)serialização de GlassParams em JSON. Fica aqui (e não no motor) porque org.json é coisa do Android. */
internal fun GlassParams.toJson(): JSONObject = JSONObject()
    .put("x", x).put("y", y).put("w", w).put("h", h).put("r", r).put("blur", blur)
    .put("ior", ior.toDouble()).put("thick", thick).put("scale", scale).put("chroma", chroma)
    .put("angle", angle).put("profile", profile.name).put("wpBlur", wpBlur).put("zoom", zoom)
    .put("panX", panX).put("panY", panY).put("bevel", bevel).put("shine", shine)

internal fun glassFromJson(o: JSONObject): GlassParams {
    val d = GlassParams()                                          // valores padrão para campos ausentes
    return GlassParams(
        x = o.optInt("x", d.x), y = o.optInt("y", d.y), w = o.optInt("w", d.w), h = o.optInt("h", d.h),
        r = o.optInt("r", d.r), blur = o.optInt("blur", d.blur), ior = o.optDouble("ior", d.ior.toDouble()).toFloat(),
        thick = o.optInt("thick", d.thick), scale = o.optInt("scale", d.scale), chroma = o.optInt("chroma", d.chroma),
        angle = o.optInt("angle", d.angle),
        profile = runCatching { BevelProfile.valueOf(o.optString("profile", d.profile.name)) }.getOrDefault(d.profile),
        wpBlur = o.optInt("wpBlur", d.wpBlur), zoom = o.optInt("zoom", d.zoom), panX = o.optInt("panX", d.panX),
        panY = o.optInt("panY", d.panY), bevel = o.optInt("bevel", d.bevel), shine = o.optInt("shine", d.shine)
    )
}

class Store private constructor(private val app: Context) {

    private val wallsDir = File(app.filesDir, "walls").apply { mkdirs() }
    private val thumbsDir = File(app.filesDir, "thumbs").apply { mkdirs() }
    private val indexFile = File(app.filesDir, "library.json")
    private val importMutex = Mutex() // impede duas importações simultâneas de criarem a mesma foto

    private val _walls = MutableStateFlow<List<Wall>>(emptyList())
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val walls: StateFlow<List<Wall>> = _walls.asStateFlow()
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    init { load(); repairFiles() }

    /**
     * Conserta o que uma interrupção (app fechado à força, bateria acabando) pode deixar pela metade:
     *  1) "<id>.previous": cópia de segurança criada ao TROCAR A FOTO de um wallpaper. Se a troca não terminou (a foto
     *     nova não existe ou está ilegível) devolve a original, senão o wallpaper ficaria sem imagem; se terminou, apaga a cópia.
     *  2) Foto própria do bloqueio que sumiu do disco: o wallpaper volta a usar a foto principal no bloqueio. Sem isso
     *     a aplicação falhava sempre e o backup recusava gerar o arquivo ("imagem de bloqueio ausente") para sempre.
     */
    private fun repairFiles() {
        wallsDir.listFiles { f -> f.name.endsWith(".previous") }?.forEach { previous ->
            val current = file(previous.name.removeSuffix(".previous"))
            val currentOk = current.isFile && runCatching { ImageIo.info(current).let { it.width > 0 && it.height > 0 } }.getOrDefault(false)
            if (currentOk) previous.delete()
            else {
                current.delete()
                if (!previous.renameTo(current)) runCatching { previous.copyTo(current, overwrite = true); previous.delete() }
            }
        }
        fun missingLock(w: Wall) = w.lock?.imageId?.let { !file(it).isFile } == true
        if (_walls.value.any { missingLock(it) }) {
            _walls.update { list -> list.map { w -> if (missingLock(w)) w.copy(lock = w.lock?.copy(imageId = null)) else w } }
            persist()
        }
    }

    fun file(id: String) = File(wallsDir, id)
    fun thumb(id: String) = File(thumbsDir, "$id.jpg")
    fun wall(id: String?): Wall? = _walls.value.firstOrNull { it.id == id }

    /** Importação privada: a foto do bloqueio não aparece como wallpaper extra na galeria. */
    suspend fun importLockImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = "lock_" + UUID.randomUUID().toString().take(12)
        val dest = file(id)
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                ?: error("Não foi possível abrir a imagem de bloqueio.")
            val info = ImageIo.info(dest)
            require(info.width > 0 && info.height > 0) { "O arquivo não é uma imagem válida." }
            id
        } catch (e: Throwable) { dest.delete(); throw e }
    }

    fun discardLockImage(id: String?) {
        if (id != null && id.startsWith("lock_") && _walls.value.none { it.lock?.imageId == id }) file(id).delete()
    }

    // ------------------------------------------------------------------ leitura/gravação do índice
    private fun load() {
        if (!indexFile.exists()) return
        val discarded = try {
            applyIndex(indexFile.readText())
        } catch (e: Exception) {
            -1                                                  // índice ilegível: começa vazio em vez de travar o app
        }
        if (discarded != 0) {
            // Algo foi perdido na leitura. A próxima gravação sobrescreve o índice, então guardamos ANTES uma cópia do
            // arquivo problemático (library.json.corrupt): dá para recuperar à mão (as imagens continuam na pasta).
            runCatching { indexFile.copyTo(File(app.filesDir, "library.json.corrupt"), overwrite = true) }
        }
    }

    /**
     * Lê o JSON do índice e atualiza as listas em memória. Lança exceção se o JSON em si for inválido.
     * Uma entrada isolada com defeito (campo faltando, valor desconhecido de uma versão mais nova…) é descartada sem
     * derrubar o resto da biblioteca. Devolve quantas entradas foram descartadas.
     */
    private fun applyIndex(text: String): Int {
        val root = JSONObject(text)
        var discarded = 0
        val ws = root.optJSONArray("walls") ?: JSONArray()
        val walls = (0 until ws.length()).mapNotNull { i ->
            runCatching {
                val o = ws.getJSONObject(i)
                Wall(
                    id = o.getString("id"), kind = Kind.valueOf(o.optString("kind", "FIXED")),
                    name = o.optString("name", "imagem"), addedAt = o.optLong("addedAt"),
                    albumId = if (o.isNull("albumId")) null else o.optString("albumId"),
                    origW = o.optInt("origW"), origH = o.optInt("origH"),
                    outMode = OutMode.valueOf(o.optString("outMode", "AUTO")),
                    outW = o.optInt("outW"), outH = o.optInt("outH"),
                    dock = o.optJSONObject("dock")?.let { glassFromJson(it) } ?: GlassParams(),
                    lock = o.optJSONObject("lock")?.let { runCatching { lockFromJson(it) }.getOrNull() },   // lock inválido: perde só o bloqueio, não o wallpaper
                    rev = o.optInt("rev", 0)
                )
            }.getOrElse { discarded++; null }
        }
        val al = root.optJSONArray("albums") ?: JSONArray()
        val albums = (0 until al.length()).mapNotNull { i ->
            runCatching {
                val o = al.getJSONObject(i)
                Album(o.getString("id"), o.optString("name"), Kind.valueOf(o.optString("kind", "FIXED")), o.optBoolean("slide"))
            }.getOrElse { discarded++; null }
        }
        // sanitizeAlbumRefs: imagens apontando para álbum inexistente voltam para "sem álbum" (não ficam invisíveis)
        _walls.value = sanitizeAlbumRefs(walls, albums); _albums.value = albums   // só troca depois de ler tudo
        return discarded
    }

    /** Monta o JSON do índice (wallpapers + álbuns + ajustes de cada dock). */
    private fun buildIndex(): JSONObject {
        val root = JSONObject()
        root.put("walls", JSONArray(_walls.value.map { w ->
            JSONObject().put("id", w.id).put("kind", w.kind.name).put("name", w.name).put("addedAt", w.addedAt)
                .put("albumId", w.albumId ?: JSONObject.NULL).put("origW", w.origW).put("origH", w.origH)
                .put("outMode", w.outMode.name).put("outW", w.outW).put("outH", w.outH).put("dock", w.dock.toJson()).put("rev", w.rev)
                .put("lock", w.lock?.toJson() ?: JSONObject.NULL)
        }))
        root.put("albums", JSONArray(_albums.value.map {
            JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name).put("slide", it.slide)
        }))
        return root
    }

    /** O mesmo índice como texto (usado pelo backup .dckl). */
    fun exportIndexJson(): String = buildIndex().toString()

    @Synchronized private fun persist() {
        val tmp = File(app.filesDir, "library.json.tmp")             // grava num temporário e troca: nunca fica pela metade
        tmp.writeText(buildIndex().toString())
        // renameTo devolve false em vez de lançar erro: antes uma falha aqui perdia a gravação em silêncio.
        if (!tmp.renameTo(indexFile)) { tmp.copyTo(indexFile, overwrite = true); tmp.delete() }
    }

    // ------------------------------------------------------------------ wallpapers
    /** Importa fotos escolhidas no seletor do Android. `dockFor` devolve a dock inicial (a última usada ou a padrão) para um tamanho de saída. */
    suspend fun import(uris: List<Uri>, kind: Kind, albumId: String?,
                       dockFor: (outW: Int, outH: Int) -> GlassParams): List<Wall> = importMutex.withLock {
        withContext(Dispatchers.IO) {
            val added = ArrayList<Wall>()
            // Padrão apenas para novas imagens. Não modifica ajustes de wallpapers já salvos.
            val (deviceW, deviceH) = Actions.deviceSize(app)
            val outputW = deviceW.coerceIn(64, 8192)
            val outputH = deviceH.coerceIn(64, 8192)
            // A comparação vale só para as fotos DA MESMA GALERIA (fixa ou automática), inclusive as que estão em álbuns dela.
            // As duas galerias são independentes: a troca automática só usa a automática. Antes a comparação era global, então
            // uma foto que já estava na galeria fixa era descartada ao importar para a automática e a fila ficava quase vazia.
            // Calcula uma vez por lote; imagens inválidas/ausentes são ignoradas.
            val knownHashes = _walls.value.filter { it.kind == kind }.mapNotNull { existing ->
                val existingFile = file(existing.id)
                if (existingFile.isFile) runCatching { sha256(existingFile) }.getOrNull() else null
            }.toHashSet()

            for (uri in uris) {
                val id = UUID.randomUUID().toString().take(12)
                val f = file(id)
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(f).use { input.copyTo(it) }
                    } ?: continue

                    val hash = sha256(f)
                    if (hash in knownHashes) {
                        f.delete() // foto já salva NESTA galeria: não gera outra cópia nem altera seu álbum/dock
                        continue
                    }

                    val info = ImageIo.info(f)
                    if (info.width <= 0 || info.height <= 0) { f.delete(); continue }
                    makeThumb(id)
                    val name = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && i >= 0) c.getString(i) else null
                    } ?: "imagem"
                    added.add(Wall(id, kind, name, System.currentTimeMillis(), albumId, info.width, info.height,
                        OutMode.DEVICE, outputW, outputH, dockFor(outputW, outputH)))
                    knownHashes.add(hash) // também impede duplicatas dentro da mesma seleção
                } catch (e: Exception) {
                    f.delete()
                    thumb(id).delete()
                }
            }
            if (added.isNotEmpty()) {
                // Se o álbum de destino foi apagado enquanto a importação rodava, a imagem entra "sem álbum" (não invisível).
                _walls.update { it + added.map { w -> w.copy(albumId = validAlbumId(w.albumId, w.kind)) } }   // update{} é atômico
                persist()
            }
            added
        }
    }

    /** SHA-256 em streaming: não carrega a foto inteira na memória. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun makeThumb(id: String) {
        val bmp = ImageIo.decodeMaxSide(file(id), 360) ?: return
        val scale = 360f / maxOf(bmp.width, bmp.height)
        val t = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        FileOutputStream(thumb(id)).use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (t !== bmp) t.recycle(); bmp.recycle()
    }

    /**
     * Copia wallpapers (arquivo, miniatura e ajustes) para outra galeria. Usado em "Importar da galeria fixa"
     * na galeria automática: a cópia é independente, então editar uma não altera a outra.
     */
    suspend fun copyTo(ids: Collection<String>, kind: Kind, albumId: String?): Int = withContext(Dispatchers.IO) {
        val added = ArrayList<Wall>()
        for (id in ids) {
            val src = wall(id) ?: continue
            val newId = UUID.randomUUID().toString().take(12)
            try {
                file(id).copyTo(file(newId), overwrite = true)
                if (thumb(id).exists()) thumb(id).copyTo(thumb(newId), overwrite = true)
                val copiedLock = src.lock?.let { config ->
                    config.imageId?.let { asset ->
                        val copyId = "lock_" + newId
                        file(asset).copyTo(file(copyId), overwrite = true)
                        config.copy(imageId = copyId)
                    } ?: config
                }
                added.add(src.copy(id = newId, kind = kind, albumId = albumId, addedAt = System.currentTimeMillis(), lock = copiedLock))
            } catch (e: Exception) { file(newId).delete(); thumb(newId).delete(); file("lock_" + newId).delete() }
        }
        _walls.update { it + added.map { w -> w.copy(albumId = validAlbumId(w.albumId, w.kind)) } }
        persist()
        added.size
    }

    /**
     * Troca o ARQUIVO da foto de um wallpaper (botão "Trocar foto" do editor), mantendo id, álbum e ajustes.
     * Devolve (largura, altura) da nova foto, ou null se o arquivo escolhido não for uma imagem.
     */
    suspend fun replaceFile(id: String, uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val tmp = File(wallsDir, "$id.new")
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                ?: return@withContext null
            val info = ImageIo.info(tmp)
            if (info.width <= 0 || info.height <= 0) { tmp.delete(); return@withContext null }
            synchronized(RotationWorker.applyLock) {
                val dst = file(id)
                if (!dst.isFile || wall(id) == null) throw IllegalStateException("Wallpaper não encontrado.")
                val previous = File(wallsDir, "$id.previous")
                if (previous.exists()) previous.delete()
                if (!dst.renameTo(previous)) throw IllegalStateException("Não foi possível proteger a imagem original.")
                try {
                    if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
                    makeThumb(id)
                    _walls.update { list -> list.map { if (it.id == id) it.copy(origW = info.width, origH = info.height, rev = it.rev + 1) else it } }
                    persist()
                    previous.delete()
                } catch (e: Exception) {
                    dst.delete()
                    if (!previous.renameTo(dst)) previous.copyTo(dst, overwrite = true)
                    runCatching { makeThumb(id) }
                    throw e
                }
            }
            tmp.delete()
            info.width to info.height
        } catch (e: Exception) { tmp.delete(); null }
    }

    // ---- apoio ao backup (.dckl) ----
    fun wallsDir(): File = wallsDir

    /** Apaga TUDO (imagens, miniaturas e índice). Só é chamado pela restauração, depois de validar o backup. */
    fun wipeAll() {
        wallsDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
        _walls.value = emptyList(); _albums.value = emptyList()
        persist()
    }

    /** Aplica o índice de um backup. Wallpapers cujo arquivo de imagem não veio no backup são descartados. */
    fun restoreIndex(json: String) {
        applyIndex(json)
        _walls.update { list -> list.filter { file(it.id).exists() } }
        persist()
    }

    /** Recria as miniaturas (o backup não as inclui, para ficar compacto). */
    fun rebuildThumbs() { for (w in _walls.value) makeThumb(w.id) }

    fun update(w: Wall) {
        _walls.update { list -> list.map { if (it.id == w.id) w else it } }
        persist()
    }

    fun remove(ids: Collection<String>) {
        val assets = _walls.value.filter { it.id in ids }.mapNotNull { it.lock?.imageId }
        for (id in ids) { file(id).delete(); thumb(id).delete() }
        _walls.update { list -> list.filter { it.id !in ids } }
        persist()
        assets.forEach { discardLockImage(it) }
    }

    fun assignAlbum(ids: Collection<String>, albumId: String?) {
        // Só aceita um álbum que exista e seja da mesma galeria da imagem; senão ela fica "sem álbum".
        _walls.update { list -> list.map { if (it.id in ids) it.copy(albumId = validAlbumId(albumId, it.kind)) else it } }
        persist()
    }

    /** O álbum existe e é da galeria `kind`? Devolve o id; senão null. */
    private fun validAlbumId(albumId: String?, kind: Kind): String? =
        albumId?.takeIf { id -> _albums.value.any { it.id == id && it.kind == kind } }

    // ------------------------------------------------------------------ álbuns
    fun createAlbum(name: String, kind: Kind): Album {
        val a = Album(UUID.randomUUID().toString().take(10), name.trim().ifEmpty { "Novo álbum" }, kind)
        _albums.update { it + a }; persist(); return a
    }
    fun renameAlbum(id: String, name: String) {
        _albums.update { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it } }; persist()
    }
    /** Marca/desmarca o álbum no slideshow da troca automática (máximo de 2 álbuns, como no app web). */
    fun setSlide(id: String, on: Boolean): Boolean {
        // Conferir o limite e gravar numa operação só (update é atômico): dois toques rápidos não passam de 2 álbuns.
        var ok = true
        _albums.update { list -> val next = withSlide(list, id, on); ok = next != null; next ?: list }
        if (ok) persist()
        return ok
    }
    fun deleteAlbum(id: String) {                                     // as imagens NÃO são apagadas: só saem do álbum
        _albums.update { list -> list.filter { it.id != id } }
        _walls.update { list -> list.map { if (it.albumId == id) it.copy(albumId = null) else it } }
        persist()
    }

    /** Imagens que a troca automática deve usar: as dos álbuns de slideshow (se houver) ou todas da galeria automática. */
    fun rotationQueue(): List<Wall> {
        val slideIds = _albums.value.filter { it.kind == Kind.ROTATION && it.slide }.map { it.id }.toSet()
        val all = _walls.value.filter { it.kind == Kind.ROTATION }.sortedBy { it.addedAt }
        return if (slideIds.isEmpty()) all else all.filter { it.albumId in slideIds }
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}
