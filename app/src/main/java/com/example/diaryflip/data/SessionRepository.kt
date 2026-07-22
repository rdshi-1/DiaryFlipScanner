package com.example.diaryflip.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionRepository {
    private const val PREFS = "diaryflip_preferences"
    private const val KEY_CURRENT_SESSION = "current_session"
    private const val ORDER_FILE_NAME = ".page_order"
    const val KEY_ENDPOINT = "transcription_endpoint"
    const val KEY_KEEP_SPREAD = "keep_spread"
    const val KEY_TOKEN = "server_token"

    fun startNewSession(context: Context): File {
        val root = File(context.getExternalFilesDir(null), "DiaryFlip")
        root.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val session = File(root, "diary_$stamp")
        session.mkdirs()
        prefs(context).edit().putString(KEY_CURRENT_SESSION, session.absolutePath).apply()
        return session
    }

    fun currentSession(context: Context): File? {
        val path = prefs(context).getString(KEY_CURRENT_SESSION, null) ?: return null
        return File(path).takeIf { it.exists() }
    }

    fun endpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, "")?.trim().orEmpty()

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "")?.trim().orEmpty()

    fun keepSpread(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SPREAD, true)

    fun saveSettings(context: Context, endpoint: String, token: String, keepSpread: Boolean) {
        prefs(context).edit()
            .putString(KEY_ENDPOINT, endpoint.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .putBoolean(KEY_KEEP_SPREAD, keepSpread)
            .apply()
    }

    /**
     * Returns pages in the order chosen on the Review screen. Newly captured pages that are not
     * yet listed in the order file are appended in capture order.
     */
    fun pageFiles(session: File): List<File> {
        val allPages = session.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("page_\\d{4}\\.jpg")) }
            ?.sortedBy { it.name }
            .orEmpty()

        if (allPages.isEmpty()) return emptyList()

        val byName = allPages.associateBy { it.name }
        val storedNames = orderFile(session)
            .takeIf { it.exists() }
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val ordered = buildList {
            storedNames.forEach { name -> byName[name]?.let(::add) }
            allPages.forEach { file -> if (none { it.name == file.name }) add(file) }
        }

        if (storedNames != ordered.map { it.name }) {
            savePageOrder(session, ordered)
        }
        return ordered
    }

    fun savePageOrder(session: File, pages: List<File>) {
        if (!session.exists()) return
        val target = orderFile(session)
        val temporary = File(session, "$ORDER_FILE_NAME.tmp")
        temporary.writeText(pages.joinToString(separator = "\n", postfix = if (pages.isEmpty()) "" else "\n") { it.name })
        if (!temporary.renameTo(target)) {
            target.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun deletePage(session: File, pageFile: File): Boolean {
        val deletedImage = !pageFile.exists() || pageFile.delete()
        transcriptFile(pageFile).delete()
        File(pageFile.parentFile, pageFile.nameWithoutExtension + ".json").delete()
        savePageOrder(session, pageFiles(session).filterNot { it.absolutePath == pageFile.absolutePath })
        return deletedImage
    }

    fun pageNumber(file: File): Int =
        Regex("page_(\\d{4})\\.jpg").find(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun transcriptFile(pageFile: File): File =
        File(pageFile.parentFile, pageFile.nameWithoutExtension + ".txt")

    private fun orderFile(session: File): File = File(session, ORDER_FILE_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
