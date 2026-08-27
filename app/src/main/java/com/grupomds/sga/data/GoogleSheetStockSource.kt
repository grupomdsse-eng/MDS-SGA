package com.grupomds.sga.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GoogleSheetStockSource {
    const val SHEET_ID = "1HmU9IPRGRWte1iXxUvYaoBc4jEmvNncHvMviI2Ggt3c"
    const val GID = "0"
    const val EDIT_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/edit?gid=$GID#gid=$GID"
    const val CSV_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/export?format=csv&gid=$GID"
    private const val GVIZ_CSV_URL = "https://docs.google.com/spreadsheets/d/$SHEET_ID/gviz/tq?tqx=out:csv&gid=$GID"

    suspend fun downloadCsv(): String = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (url in listOf(CSV_URL, GVIZ_CSV_URL)) {
            try {
                return@withContext download(url)
            } catch (error: Throwable) {
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
            setRequestProperty("User-Agent", "SGA-MDS-Android/1.3")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status")
            }

            val contentType = connection.contentType.orEmpty().lowercase()
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
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
