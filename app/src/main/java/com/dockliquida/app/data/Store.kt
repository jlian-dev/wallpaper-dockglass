package com.dockliquida.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.dockliquida.app.glass.BevelProfile
import com.dockliquida.app.glass.GlassParams
import com.dockliquida.app.render.ImageIo
import com.dockliquida.app.system.Actions
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
    val rev: Int = 0               // sobe a cada "Trocar foto" (força a interface a recarregar a miniatura)
)

data class Album(val id: String, val name: String, val kind: Kind, val slide: Boolean = false)

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

    init { load() }

    fun file(id: String) = File(wallsDir, id)
    fun thumb(id: String) = File(thumbsDir, "$id.jpg")
    fun wall(id: String?): Wall? = _walls.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ leitura/gravação do índice
    private fun load() {
        if (!indexFile.exists()) return
        try { applyIndex(indexFile.readText()) } catch (e: Exception) { /* índice corrompido: começa vazio em vez de travar o app */ }
    }

    /** Lê o JSON do índice e atualiza as listas em memória. Lança exceção se o JSON for inválido. */
    private fun applyIndex(text: String) {
        val root = JSONObject(text)
        val ws = root.optJSONArray("walls") ?: JSONArray()
        val walls = (0 until ws.length()).map { i ->
            val o = ws.getJSONObject(i)
            Wall(
                id = o.getString("id"), kind = Kind.valueOf(o.optString("kind", "FIXED")),
                name = o.optString("name", "imagem"), addedAt = o.optLong("addedAt"),
                albumId = if (o.isNull("albumId")) null else o.optString("albumId"),
                origW = o.optInt("origW"), origH = o.optInt("origH"),
                outMode = OutMode.valueOf(o.optString("outMode", "AUTO")),
                outW = o.optInt("outW"), outH = o.optInt("outH"),
                dock = glassFromJson(o.getJSONObject("dock")), rev = o.optInt("rev", 0)
            )
        }
        val al = root.optJSONArray("albums") ?: JSONArray()
        val albums = (0 until al.length()).map { i ->
            val o = al.getJSONObject(i)
            Album(o.getString("id"), o.optString("name"), Kind.valueOf(o.optString("kind", "FIXED")), o.optBoolean("slide"))
        }
        _walls.value = walls; _albums.value = albums           // só troca depois de ler tudo com sucesso
    }

    /** Monta o JSON do índice (wallpapers + álbuns + ajustes de cada dock). */
    private fun buildIndex(): JSONObject {
        val root = JSONObject()
        root.put("walls", JSONArray(_walls.value.map { w ->
            JSONObject().put("id", w.id).put("kind", w.kind.name).put("name", w.name).put("addedAt", w.addedAt)
                .put("albumId", w.albumId ?: JSONObject.NULL).put("origW", w.origW).put("origH", w.origH)
                .put("outMode", w.outMode.name).put("outW", w.outW).put("outH", w.outH).put("dock", w.dock.toJson()).put("rev", w.rev)
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
            // A comparação é global: inclui imagens em álbuns e nas duas galerias.
            // Calcula uma vez por lote; imagens inválidas/ausentes são ignoradas.
            val knownHashes = _walls.value.mapNotNull { existing ->
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
                        f.delete() // foto já salva: não gera outra cópia nem altera seu álbum/dock
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
                _walls.update { it + added }                          // update{} é atômico: não perde escritas de outras threads
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
                added.add(src.copy(id = newId, kind = kind, albumId = albumId, addedAt = System.currentTimeMillis()))
            } catch (e: Exception) { file(newId).delete(); thumb(newId).delete() }
        }
        _walls.update { it + added }
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
            val dst = file(id)
            dst.delete()
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
            makeThumb(id)
            _walls.update { list -> list.map { if (it.id == id) it.copy(origW = info.width, origH = info.height, rev = it.rev + 1) else it } }
            persist()
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
        for (id in ids) { file(id).delete(); thumb(id).delete() }
        _walls.update { list -> list.filter { it.id !in ids } }
        persist()
    }

    fun assignAlbum(ids: Collection<String>, albumId: String?) {
        _walls.update { list -> list.map { if (it.id in ids) it.copy(albumId = albumId) else it } }
        persist()
    }

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
        val rot = _albums.value.filter { it.kind == Kind.ROTATION }
        if (on && rot.count { it.slide } >= 2) return false
        _albums.update { list -> list.map { if (it.id == id) it.copy(slide = on) else it } }; persist(); return true
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
