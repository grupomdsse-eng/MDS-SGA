package com.grupomds.sga.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleSheetStockSource {
    const val SHEET_ID = "1HmU9IPRGRWte1iXxUvYaoBc4jEmvNncHvMviI2Ggt3c"
    const val GID = "0"
    const val EDIT_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/edit?gid=$GID#gid=$GID"
    const val CSV_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/export?format=csv&gid=$GID"
    private const val GVIZ_CSV_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/gviz/tq?tqx=out:csv&gid=$GID"
    private const val MAX_CSV_CHARS = 4_000_000

    suspend fun downloadCsv(): String = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val cacheBuster = System.currentTimeMillis()
        for (baseUrl in listOf(CSV_URL, GVIZ_CSV_URL)) {
            val separator = if (baseUrl.contains("?")) "&" else "?"
            val url = "$baseUrl${separator}_ts=$cacheBuster"
            try {
                return@withContext download(url)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                errors += error.message ?: error.javaClass.simpleName
            }
        }
        throw IllegalStateException(
            "No se ha podido leer Google Sheets. Comprueba que la hoja tenga permiso de lectura mediante enlace. ${errors.distinct().joinToString(" | ")}"
        )
    }

    private fun download(url: String): String {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 7_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "text/csv,text/plain,*/*")
            setRequestProperty("User-Agent", "SGA-MDS-Android/2.0.0")
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status")
            }

            val contentType = connection.contentType.orEmpty().lowercase()
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_CSV_CHARS * 4L) {
                throw IllegalStateException("la hoja es demasiado grande para sincronizarla de forma segura")
            }

            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val output = StringBuilder(minOf(256_000, MAX_CSV_CHARS))
                val buffer = CharArray(8_192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (output.length + read > MAX_CSV_CHARS) {
                        throw IllegalStateException("la hoja supera el tamaño máximo seguro")
                    }
                    output.append(buffer, 0, read)
                }
                output.toString()
            }

            if (body.isBlank()) throw IllegalStateException("archivo vacío")
            val beginning = body.trimStart().take(200).lowercase()
            if (
                contentType.contains("text/html") ||
                beginning.startsWith("<!doctype html") ||
                beginning.startsWith("<html") ||
                beginning.contains("accounts.google.com")
            ) {
                throw IllegalStateException("la hoja requiere iniciar sesión")
            }

            return body
        } finally {
            connection.disconnect()
        }
    }
}
