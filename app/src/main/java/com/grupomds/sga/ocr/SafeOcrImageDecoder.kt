package com.grupomds.sga.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.max

/**
 * Decodifica una foto a un tamaño seguro antes de pasarla a ML Kit.
 *
 * Las cámaras actuales pueden generar fotos de 12-50 MP. Decodificarlas completas varias veces
 * puede provocar picos de memoria y el cierre del proceso por Android. Para OCR de un A4 no hace
 * falta tanta resolución. Además, la rotación EXIF se entrega a ML Kit como metadato en lugar de
 * crear una segunda copia rotada del bitmap (eso evita duplicar temporalmente la memoria usada).
 */
object SafeOcrImageDecoder {
    private const val MAX_DIMENSION = 1800
    private const val MAX_RETRIES = 3

    data class DecodedImage(
        val bitmap: Bitmap,
        val rotationDegrees: Int
    )

    fun decode(context: Context, uri: Uri): DecodedImage {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: throw IOException("No se puede abrir la fotografía")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("La fotografía no tiene un tamaño válido")
        }

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_DIMENSION) {
            sampleSize *= 2
        }

        var lastFailure: Throwable? = null
        repeat(MAX_RETRIES) { attempt ->
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            try {
                val bitmap = resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                } ?: throw IOException("No se ha podido decodificar la fotografía")

                return DecodedImage(
                    bitmap = bitmap,
                    rotationDegrees = readRotation(context, uri)
                )
            } catch (oom: OutOfMemoryError) {
                // Un móvil con poca RAM puede fallar incluso tras el primer muestreo. Reintentamos
                // con la mitad de resolución en cada lado sin dejar que el proceso muera por OOM.
                lastFailure = oom
                sampleSize *= 2
                if (attempt < MAX_RETRIES - 1) {
                    runCatching { System.gc() }
                }
            }
        }

        throw IOException(
            "La fotografía es demasiado grande para la memoria disponible. Acerca el albarán, vuelve a hacer la foto e inténtalo de nuevo.",
            lastFailure
        )
    }

    private fun readRotation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }
}
