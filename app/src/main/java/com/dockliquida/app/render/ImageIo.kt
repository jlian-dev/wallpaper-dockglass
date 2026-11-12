package com.dockliquida.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/*
 * ImageIo.kt
 * --------------------------------------------------------------------------
 * Leitura de imagens do disco com DOIS cuidados que o navegador fazia sozinho:
 *   1) respeitar a ORIENTAÇÃO EXIF (fotos de celular costumam vir "deitadas" com um aviso de rotação);
 *   2) decodificar já REDUZIDO (inSampleSize) quando a foto é maior do que precisamos, para não
 *      estourar a memória com fotos de 50 megapixels.
 */
object ImageIo {

    /** Tamanho da imagem JÁ com a orientação aplicada + o código EXIF original. */
    class Info(val width: Int, val height: Int, val exif: Int)

    /** Lê só o cabeçalho do arquivo (rápido, quase sem memória). */
    fun info(file: File): Info {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }   // "só me diga o tamanho"
        BitmapFactory.decodeFile(file.absolutePath, o)
        val exif = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val swap = exif in intArrayOf(
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270
        )                                                                      // 90°/270° trocam largura por altura
        return if (swap) Info(o.outHeight, o.outWidth, exif) else Info(o.outWidth, o.outHeight, exif)
    }

    /** Matriz que desfaz a orientação EXIF (ou null se a foto já está em pé). */
    private fun exifMatrix(exif: Int): Matrix? {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }

    /** Maior potência de 2 que não passa de `v` (o inSampleSize do Android só aceita potências de 2). */
    private fun floorPow2(v: Int): Int { var p = 1; while (p * 2 <= v) p *= 2; return p }

    /**
     * Decodifica a imagem já na orientação certa. `scale` é a escala (em relação ao original) em que ela
     * será usada: 0,3 significa que vai ser desenhada com 30% do tamanho, então podemos ler reduzida.
     * Mantém sempre pelo menos 50% de folga de resolução para o desenho final ficar nítido.
     */
    fun decodeForScale(file: File, scale: Double): Bitmap? {
        val sample = floorPow2(maxOf(1, (1.0 / scale).toInt()))                // 1, 2, 4, 8…
        return decode(file, sample)
    }

    /** Decodifica com um lado máximo (usado na prévia e nas miniaturas). */
    fun decodeMaxSide(file: File, maxSide: Int): Bitmap? {
        val i = info(file)
        val sample = floorPow2(maxOf(1, maxOf(i.width, i.height) / maxSide))
        return decode(file, sample)
    }

    private fun decode(file: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888                       // 8 bits por canal: sem perda de cor
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val exif = info(file).exif
        val m = exifMatrix(exif) ?: return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()                                    // libera a versão sem rotação
        return rotated
    }
}
