package com.dhwanidrishti.app.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.dhwanidrishti.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Small REST client used only by Hyper for on-demand visual questions.
 *
 * It is intentionally independent from the Soundscape and Narrated
 * perception pipelines.
 */
class GeminiVisionEngine {

    companion object {
        private const val TAG = "GeminiVisionEngine"

        private const val MODEL = "gemini-3.6-flash"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"

        private const val JPEG_QUALITY = 80

        // Retry only transient failures. Do not retry bad requests,
        // authentication failures, or unavailable models.
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 8_000L
    }

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Sends one camera frame to Gemini and asks the supplied question.
     *
     * Hyper is on-demand: this function runs only after an explicit
     * visual question and does not affect Soundscape or Narrated.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        userQuestion: String
    ): Result<String> = withContext(Dispatchers.IO) {

        try {
            val apiKey = BuildConfig.GEMINI_API_KEY.trim()

            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "GEMINI_API_KEY is missing. " +
                                "Add it to local.properties and sync Gradle."
                    )
                )
            }

            Log.d(TAG, "Preparing image for Gemini")

            val imageBase64 = bitmapToBase64(bitmap)

            Log.d(
                TAG,
                "Image encoded successfully. Base64 size=${imageBase64.length}"
            )

            val requestJson = buildRequestJson(
                userQuestion = userQuestion,
                imageBase64 = imageBase64
            )

            val url = "$BASE_URL$MODEL:generateContent"

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson
                .toString()
                .toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-goog-api-key", apiKey)
                .build()

            var attempt = 0

            while (true) {
                attempt++

                Log.d(
                    TAG,
                    "Sending Gemini request: model=$MODEL apiVersion=v1beta " +
                            "attempt=$attempt/$MAX_RETRIES"
                )

                val result = executeRequest(request)

                if (result.isSuccess) {
                    return@withContext result
                }

                val error = result.exceptionOrNull()

                if (error !is GeminiHttpException ||
                    !isRetryableStatus(error.statusCode) ||
                    attempt >= MAX_RETRIES
                ) {
                    return@withContext result
                }

                val backoff = calculateBackoff(attempt)

                Log.w(
                    TAG,
                    "Transient Gemini error ${error.statusCode}. " +
                            "Retrying in ${backoff}ms"
                )

                delay(backoff)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Gemini analysis failed", e)
            Result.failure(e)
        }
    }

    /**
     * Performs one HTTP request. Retry decisions are made by analyzeImage().
     */
    private fun executeRequest(
        request: Request
    ): Result<String> {
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                Log.d(TAG, "Gemini HTTP status=${response.code}")

                if (!response.isSuccessful) {
                    Log.e(
                        TAG,
                        "Gemini request failed (${response.code}): $responseBody"
                    )

                    Result.failure(
                        GeminiHttpException(
                            statusCode = response.code,
                            message = extractApiError(responseBody)
                        )
                    )
                } else {
                    val answer = extractTextFromResponse(responseBody)

                    if (answer.isBlank()) {
                        Log.e(
                            TAG,
                            "Gemini returned an empty response: $responseBody"
                        )

                        Result.failure(
                            RuntimeException("Gemini returned an empty response")
                        )
                    } else {
                        Log.d(TAG, "Gemini answer: $answer")
                        Result.success(answer)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini HTTP request failed", e)
            Result.failure(e)
        }
    }

    /**
     * Retry transient server/rate-limit failures, but not configuration errors.
     */
    private fun isRetryableStatus(statusCode: Int): Boolean {
        return statusCode == 408 ||
                statusCode == 429 ||
                statusCode in 500..599
    }

    /**
     * Sequential exponential backoff with small random jitter:
     * attempt 1 -> ~1s, attempt 2 -> ~2s, attempt 3 -> ~4s.
     */
    private fun calculateBackoff(attempt: Int): Long {
        val exponential = INITIAL_BACKOFF_MS * (1L shl (attempt - 1))
        val capped = min(exponential, MAX_BACKOFF_MS)
        val jitter = Random.nextLong(0L, 251L)
        return capped + jitter
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            JPEG_QUALITY,
            outputStream
        )

        return Base64.encodeToString(
            outputStream.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun buildRequestJson(
        userQuestion: String,
        imageBase64: String
    ): JSONObject {
        val imagePart = JSONObject()
            .put(
                "inline_data",
                JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", imageBase64)
            )

        val textPart = JSONObject()
            .put("text", userQuestion.trim())

        val content = JSONObject()
            .put("role", "user")
            .put(
                "parts",
                JSONArray()
                    .put(textPart)
                    .put(imagePart)
            )

        return JSONObject()
            .put("contents", JSONArray().put(content))
    }

    private fun extractTextFromResponse(responseBody: String): String {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: return ""

        if (candidates.length() == 0) return ""

        val candidate = candidates.optJSONObject(0) ?: return ""
        val content = candidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""

        val result = StringBuilder()

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text", "")

            if (text.isNotBlank()) {
                if (result.isNotEmpty()) result.append(' ')
                result.append(text.trim())
            }
        }

        return result.toString().trim()
    }

    private fun extractApiError(responseBody: String): String {
        return try {
            val root = JSONObject(responseBody)
            val error = root.optJSONObject("error")

            if (error != null) {
                val message = error.optString(
                    "message",
                    "Unknown API error"
                )
                val status = error.optString("status", "")

                if (status.isBlank()) message else "$status: $message"
            } else {
                "Unknown API error"
            }
        } catch (_: Exception) {
            "Unable to parse Gemini error response"
        }
    }

    private class GeminiHttpException(
        val statusCode: Int,
        message: String
    ) : RuntimeException(
        "Gemini request failed ($statusCode): $message"
    )
}
