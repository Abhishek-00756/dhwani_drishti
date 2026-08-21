package com.dhwanidrishti.app.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.dhwanidrishti.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Small REST client used only by Hyper for on-demand visual questions.
 *
 * It is intentionally independent from the Soundscape and Narrated
 * perception pipelines.
 */
class GeminiVisionEngine {

    companion object {
        private const val TAG = "GeminiVisionEngine"

        /**
         * Gemini 2.5 Flash supports multimodal image understanding.
         * Keep the model in one place so it is easy to update later.
         */
        private const val MODEL = "gemini-2.5-flash"

        /**
         * Use the stable Gemini API version for generateContent.
         */
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1/models/"

        private const val JPEG_QUALITY = 80
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
     * This function is called only when Hyper receives an explicit
     * visual question. It does not run continuously.
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

            val requestJson =
                buildRequestJson(
                    userQuestion = userQuestion,
                    imageBase64 = imageBase64
                )

            val url =
                "$BASE_URL$MODEL:generateContent"

            val mediaType =
                "application/json; charset=utf-8".toMediaType()

            val requestBody =
                requestJson
                    .toString()
                    .toRequestBody(mediaType)

            val request =
                Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader(
                        "Content-Type",
                        "application/json"
                    )
                    .addHeader(
                        "x-goog-api-key",
                        apiKey
                    )
                    .build()

            Log.d(
                TAG,
                "Sending Gemini request: model=$MODEL apiVersion=v1"
            )

            client.newCall(request).execute().use { response ->

                val responseBody =
                    response.body?.string().orEmpty()

                Log.d(
                    TAG,
                    "Gemini HTTP status=${response.code}"
                )

                if (!response.isSuccessful) {

                    Log.e(
                        TAG,
                        "Gemini request failed (${response.code}): $responseBody"
                    )

                    return@withContext Result.failure(
                        RuntimeException(
                            "Gemini request failed (${response.code}): " +
                                    extractApiError(responseBody)
                        )
                    )
                }

                val answer =
                    extractTextFromResponse(responseBody)

                if (answer.isBlank()) {

                    Log.e(
                        TAG,
                        "Gemini returned an empty response: $responseBody"
                    )

                    return@withContext Result.failure(
                        RuntimeException(
                            "Gemini returned an empty response"
                        )
                    )
                }

                Log.d(TAG, "Gemini answer: $answer")

                Result.success(answer)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gemini analysis failed",
                e
            )

            Result.failure(e)
        }
    }

    /**
     * Converts the supplied bitmap to JPEG and then Base64.
     */
    private fun bitmapToBase64(
        bitmap: Bitmap
    ): String {

        val outputStream =
            ByteArrayOutputStream()

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

    /**
     * Builds the multimodal generateContent request.
     */
    private fun buildRequestJson(
        userQuestion: String,
        imageBase64: String
    ): JSONObject {

        val imagePart =
            JSONObject()
                .put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", imageBase64)
                )

        val textPart =
            JSONObject()
                .put(
                    "text",
                    userQuestion.trim()
                )

        val content =
            JSONObject()
                .put(
                    "role",
                    "user"
                )
                .put(
                    "parts",
                    JSONArray()
                        .put(textPart)
                        .put(imagePart)
                )

        return JSONObject()
            .put(
                "contents",
                JSONArray().put(content)
            )
    }

    /**
     * Extracts the first text response from candidates[0].content.parts.
     */
    private fun extractTextFromResponse(
        responseBody: String
    ): String {

        val root = JSONObject(responseBody)

        val candidates =
            root.optJSONArray("candidates")
                ?: return ""

        if (candidates.length() == 0) {
            return ""
        }

        val candidate =
            candidates.optJSONObject(0)
                ?: return ""

        val content =
            candidate.optJSONObject("content")
                ?: return ""

        val parts =
            content.optJSONArray("parts")
                ?: return ""

        val result = StringBuilder()

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text", "")

            if (text.isNotBlank()) {
                if (result.isNotEmpty()) {
                    result.append(' ')
                }
                result.append(text.trim())
            }
        }

        return result.toString().trim()
    }

    /**
     * Extracts Google's useful error message without exposing the API key.
     */
    private fun extractApiError(
        responseBody: String
    ): String {

        return try {
            val root = JSONObject(responseBody)
            val error = root.optJSONObject("error")

            if (error != null) {
                val message = error.optString("message", "Unknown API error")
                val status = error.optString("status", "")

                if (status.isBlank()) {
                    message
                } else {
                    "$status: $message"
                }
            } else {
                "Unknown API error"
            }
        } catch (_: Exception) {
            "Unable to parse Gemini error response"
        }
    }
}
