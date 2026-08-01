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

    fun diaryRoot(context: Context): File =
        File(context.getExternalFilesDir(null), "DiaryFlip").apply { mkdirs() }

    /**
     * Creates a completely new diary folder and makes it the current diary.
     * This should only be called from the explicit New diary action.
     */
    fun startNewSession(context: Context): File {
        val root = diaryRoot(context)

        val baseStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        var suffix = 0
        var session: File
        do {
            val name = if (suffix == 0) "diary_$baseStamp" else "diary_${baseStamp}_$suffix"
            session = File(root, name)
            suffix += 1
        } while (session.exists())

        session.mkdirs()
        setCurrentSession(context, session)
        return session
    }

    /**
     * Returns the current diary, creating the first diary only when none exists yet.
     * Starting and stopping scanning must keep returning this same folder.
     */
    fun getOrCreateCurrentSession(context: Context): File =
        currentSession(context) ?: startNewSession(context)

    fun currentSession(context: Context): File? {
        val path = prefs(context).getString(KEY_CURRENT_SESSION, null) ?: return null
        return File(path).takeIf { it.exists() && it.isDirectory }
    }

    fun setCurrentSession(context: Context, session: File?) {
        prefs(context).edit().putString(KEY_CURRENT_SESSION, session?.absolutePath).apply()
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

    fun listSessions(context: Context): List<File> {
        return diaryRoot(context).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()
    }

    fun renameSession(context: Context, session: File, requestedName: String): File? {
        if (!session.exists() || !session.isDirectory) return null
        val cleaned = requestedName.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null

        val root = diaryRoot(context)
        var candidate = cleaned.replace(' ', '_')
        if (candidate.isBlank()) return null

        var target = File(root, candidate)
        var suffix = 2
        while (target.exists() && target.absolutePath != session.absolutePath) {
            target = File(root, "${candidate}_$suffix")
            suffix += 1
        }
        if (target.absolutePath == session.absolutePath) return session
        if (!session.renameTo(target)) return null
        if (currentSession(context)?.absolutePath == session.absolutePath) {
            setCurrentSession(context, target)
        }
        return target
    }

    fun deleteSession(context: Context, session: File): Boolean {
        val currentPath = currentSession(context)?.absolutePath
        val deleted = session.deleteRecursively()
        if (!deleted) return false
        if (currentPath == session.absolutePath) {
            val replacement = listSessions(context).firstOrNull()
            setCurrentSession(context, replacement)
        }
        return true
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

    fun pageCount(session: File): Int = pageFiles(session).size

    fun diaryLabel(session: File): String {
        val stamp = Regex("diary_(\\d{8}_\\d{6})").find(session.name)?.groupValues?.getOrNull(1)
            ?: return session.name.replace('_', ' ')
        return try {
            val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).parse(stamp)
                ?: return session.name.replace('_', ' ')
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(parsed)
        } catch (_: Exception) {
            session.name.replace('_', ' ')
        }
    }

    /**
     * Page filenames are never reused, even after a page has been deleted or reordered.
     */
    fun nextPageNumber(session: File): Int {
        val maximumExistingPage = session.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.mapNotNull(::pageNumberOrNull)
            ?.maxOrNull()
            ?: 0
        val maximumPageReservedBySpreads = (nextSpreadNumber(session) - 1) * 2
        return maxOf(maximumExistingPage, maximumPageReservedBySpreads) + 1
    }

    /**
     * Original spread filenames are also never reused, preserving re-split source images.
     */
    fun nextSpreadNumber(session: File): Int {
        val maximum = session.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                Regex("spread_(\\d{4})\\.jpg")
                    .matchEntire(file.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            ?.maxOrNull()
            ?: 0
        return maximum + 1
    }

    fun latestSpreadFile(session: File): File? = session.listFiles()
        ?.filter { it.isFile && it.name.matches(Regex("spread_\\d{4}\\.jpg")) }
        ?.maxByOrNull { it.name }

    fun savePageOrder(session: File, pages: List<File>) {
        if (!session.exists()) return
        val target = orderFile(session)
        val temporary = File(session, "$ORDER_FILE_NAME.tmp")
        temporary.writeText(
            pages.joinToString(
                separator = "\n",
                postfix = if (pages.isEmpty()) "" else "\n"
            ) { it.name }
        )
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

    fun pageNumber(file: File): Int = pageNumberOrNull(file) ?: 0

    fun transcriptFile(pageFile: File): File =
        File(pageFile.parentFile, pageFile.nameWithoutExtension + ".txt")

    private fun pageNumberOrNull(file: File): Int? =
        Regex("page_(\\d{4})\\.jpg")
            .matchEntire(file.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun orderFile(session: File): File = File(session, ORDER_FILE_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
