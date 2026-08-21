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

class GeminiVisionEngine {

    companion object {
        private const val TAG = "GeminiVisionEngine"

        /*
         * We use Gemini's generateContent REST endpoint.
         *
         * Keep the model name in one place so it is easy to change later.
         */
        private const val MODEL = "gemini-2.5-flash"

        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"

        private const val JPEG_QUALITY = 80
    }

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Sends one camera frame to Gemini and asks it to identify objects.
     *
     * This function does NOT run continuously.
     * It is intended to be called only when the user asks something
     * that needs Gemini's visual understanding.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        userQuestion: String
    ): Result<String> = withContext(Dispatchers.IO) {

        try {

            val apiKey = BuildConfig.GEMINI_API_KEY

            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "GEMINI_API_KEY is missing. " +
                                "Check local.properties."
                    )
                )
            }

            Log.d(TAG, "Preparing image for Gemini")

            val imageBase64 = bitmapToBase64(bitmap)

            Log.d(
                TAG,
                "Image encoded successfully. " +
                        "Base64 size=${imageBase64.length}"
            )

            val prompt = buildPrompt(userQuestion)

            val requestJson =
                buildRequestJson(
                    prompt = prompt,
                    imageBase64 = imageBase64
                )

            val url =
                "$BASE_URL$MODEL:generateContent?key=$apiKey"

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
                    .build()

            Log.d(TAG, "Sending request to Gemini")

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
                        "Gemini request failed: $responseBody"
                    )

                    return@withContext Result.failure(
                        RuntimeException(
                            "Gemini request failed " +
                                    "(${response.code})"
                        )
                    )
                }

                val answer =
                    extractTextFromResponse(responseBody)

                if (answer.isBlank()) {

                    Log.e(
                        TAG,
                        "Gemini returned an empty response"
                    )

                    return@withContext Result.failure(
                        RuntimeException(
                            "Gemini returned an empty response"
                        )
                    )
                }

                Log.d(
                    TAG,
                    "Gemini answer: $answer"
                )

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

        val bytes =
            outputStream.toByteArray()

        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP
        )
    }

    /**
     * Prompt specifically designed for Dhwani Drishti.
     *
     * Gemini is being used for visual understanding here,
     * not for distance measurement.
     */
    private fun buildPrompt(
        userQuestion: String
    ): String {

        return """
            You are the visual understanding component of
            an assistive Android application called Dhwani Drishti.

            Analyze the provided camera image carefully.

            User question:
            "$userQuestion"

            Instructions:

            1. Identify objects that are clearly visible.
            2. Pay particular attention to the object requested
               by the user.
            3. Determine the approximate horizontal position
               of the requested object:
               LEFT, CENTER, or RIGHT.
            4. Do not invent objects that are not visible.
            5. Do not claim an exact physical distance.
            6. If the requested object is not visible, clearly
               say that it was not found.
            7. Keep the answer concise because the response
               will be spoken aloud by text-to-speech.

            For a location question, answer in this style:

            "Door is on your left."

            or:

            "I cannot see a door."

            User question:
            "$userQuestion"
        """.trimIndent()
    }

    /**
     * Builds the Gemini generateContent request.
     */
    private fun buildRequestJson(
        prompt: String,
        imageBase64: String
    ): JSONObject {

        val inlineData =
            JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", imageBase64)

        val imagePart =
            JSONObject()
                .put("inline_data", inlineData)

        val textPart =
            JSONObject()
                .put("text", prompt)

        val parts =
            JSONArray()
                .put(textPart)
                .put(imagePart)

        val content =
            JSONObject()
                .put("parts", parts)

        return JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(content)
            )
    }

    /**
     * Extracts:
     *
     * candidates[0]
     *     -> content
     *         -> parts[0]
     *             -> text
     */
    private fun extractTextFromResponse(
        responseBody: String
    ): String {

        val root =
            JSONObject(responseBody)

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

        val result =
            StringBuilder()

        for (i in 0 until parts.length()) {

            val part =
                parts.optJSONObject(i)
                    ?: continue

            val text =
                part.optString("text", "")

            if (text.isNotBlank()) {
                result.append(text)
            }
        }

        return result
            .toString()
            .trim()
    }
}