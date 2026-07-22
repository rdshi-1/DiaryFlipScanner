package com.example.diaryflip.network

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.diaryflip.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pagePath = inputData.getString(KEY_PAGE_PATH) ?: return@withContext Result.failure()
        val pageNumber = inputData.getInt(KEY_PAGE_NUMBER, -1)
        val endpoint = inputData.getString(KEY_ENDPOINT)?.trim()?.trimEnd('/').orEmpty()
        val token = inputData.getString(KEY_TOKEN).orEmpty()
        val pageFile = File(pagePath)

        if (!pageFile.exists() || pageNumber < 1 || endpoint.isBlank()) {
            return@withContext Result.failure()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("page_number", pageNumber.toString())
            .addFormDataPart(
                "image",
                pageFile.name,
                pageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val requestBuilder = Request.Builder()
            .url("$endpoint/v1/transcribe")
            .post(body)
        if (token.isNotBlank()) requestBuilder.header("X-DiaryFlip-Token", token)
        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext if (response.code >= 500 || response.code == 429) {
                        Result.retry()
                    } else {
                        Result.failure(workDataOf("error" to "Server returned ${response.code}"))
                    }
                }

                val payload = response.body?.string().orEmpty()
                val json = JSONObject(payload)
                val transcription = json.optString("transcription", "")
                val uncertain = json.optJSONArray("uncertain_passages")

                val output = buildString {
                    append(transcription.trim())
                    if (uncertain != null && uncertain.length() > 0) {
                        append("\n\n[Uncertain passages]\n")
                        for (i in 0 until uncertain.length()) {
                            append("- ").append(uncertain.optString(i)).append('\n')
                        }
                    }
                }
                // A page may have been deleted from Review while this request was running.
                // Do not recreate sidecar files for a deleted page.
                if (!pageFile.exists()) return@withContext Result.success()
                SessionRepository.transcriptFile(pageFile).writeText(output)
                File(pageFile.parentFile, pageFile.nameWithoutExtension + ".json").writeText(json.toString(2))
                Result.success()
            }
        } catch (io: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "Unknown transcription error")))
        }
    }

    companion object {
        private const val KEY_PAGE_PATH = "page_path"
        private const val KEY_PAGE_NUMBER = "page_number"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TOKEN = "token"

        fun enqueue(context: Context, pageFile: File, pageNumber: Int, endpoint: String, token: String) {
            if (endpoint.isBlank()) return
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_PAGE_PATH, pageFile.absolutePath)
                        .putInt(KEY_PAGE_NUMBER, pageNumber)
                        .putString(KEY_ENDPOINT, endpoint)
                        .putString(KEY_TOKEN, token)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
