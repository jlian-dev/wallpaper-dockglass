package com.dockliquida.app.system

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/*
 * Actions.kt
 * --------------------------------------------------------------------------
 * Ações do sistema Android: aplicar como papel de parede, salvar na Galeria e descobrir o tamanho real
 * da tela. No app web tudo isso passava pelo plugin Java (setWallpaper, saveImageToGallery…); agora é chamada direta.
 */

/** Onde aplicar o wallpaper. `key` é o texto guardado nas preferências. */
enum class WallTarget(val key: String, val label: String) {
    HOME("home", "Tela inicial"), LOCK("lock", "Tela de bloqueio"), BOTH("both", "Ambas");
    companion object { fun fromKey(k: String) = values().firstOrNull { it.key == k } ?: HOME }
}

object Actions {

    /** Resolução FÍSICA da tela (a mesma da tela inicial real). Usada pelo modo "Tela deste aparelho". */
    fun deviceSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Aplica o bitmap como papel de parede. Chamar FORA da thread principal (é pesado). */
    fun applyWallpaper(ctx: Context, bmp: Bitmap, target: WallTarget) {
        val wm = WallpaperManager.getInstance(ctx)
        // Aparelhos com restrição (perfil de trabalho, modo infantil, ROM sem suporte) recusam o wallpaper.
        // Avisar aqui dá uma mensagem clara em vez de "trocar" sem trocar nada.
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) {
            throw IllegalStateException("O sistema não permite alterar o papel de parede neste aparelho.")
        }
        val flags = when (target) {
            WallTarget.HOME -> WallpaperManager.FLAG_SYSTEM
            WallTarget.LOCK -> WallpaperManager.FLAG_LOCK
            WallTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        // setBitmap devolve o ID do novo wallpaper, ou 0 se o Android NÃO conseguiu aplicar. Antes esse valor era
        // ignorado: a troca automática achava que deu certo e avançava a fila sem o wallpaper ter mudado.
        val id = wm.setBitmap(bmp, null, true, flags)                // visibleCropHint = null: usa a imagem inteira
        if (id == 0) throw IllegalStateException("O Android recusou aplicar o papel de parede.")
    }

    /**
     * Salva um PNG em Imagens/Dock Líquida (aparece na Galeria). No Android 10+ usa o MediaStore, que não
     * exige permissão. Devolve a Uri salva ou lança exceção com uma mensagem em português.
     */
    fun saveToGallery(ctx: Context, bmp: Bitmap, baseName: String): Uri {
        val safe = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_").let { if (it.lowercase().endsWith(".png")) it else "$it.png" }
        if (Build.VERSION.SDK_INT < 29) {                            // Android 8/9: grava na pasta Pictures direto
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Dock Líquida").apply { mkdirs() }
            val f = File(dir, safe)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/png"), null)
            return Uri.fromFile(f)
        }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safe)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Dock Líquida")
            put(MediaStore.Images.Media.IS_PENDING, 1)               // "ainda gravando": a Galeria não mostra pela metade
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Não foi possível criar a imagem na Galeria.")
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: throw IllegalStateException("Não foi possível abrir o destino.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }          // não deixa arquivo quebrado na Galeria
            throw e
        }
    }
}
