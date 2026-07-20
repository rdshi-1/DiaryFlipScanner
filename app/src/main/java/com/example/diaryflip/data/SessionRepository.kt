package com.example.diaryflip.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionRepository {
    private const val PREFS = "diaryflip_preferences"
    private const val KEY_CURRENT_SESSION = "current_session"
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

    fun pageFiles(session: File): List<File> =
        session.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("page_\\d{4}\\.jpg")) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun pageNumber(file: File): Int =
        Regex("page_(\\d{4})\\.jpg").find(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun transcriptFile(pageFile: File): File =
        File(pageFile.parentFile, pageFile.nameWithoutExtension + ".txt")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
