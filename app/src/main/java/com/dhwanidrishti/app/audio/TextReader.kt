package com.dhwanidrishti.app.audio

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TextReader {

    companion object {
        private const val TAG = "DHWANI_OCR"
    }

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /**
     * Reads text from the supplied camera frame.
     *
     * Result:
     *
     * text != null -> text was detected
     * text == null -> no readable text
     */
    fun read(
        bitmap: Bitmap,
        onResult: (String?) -> Unit
    ) {

        try {

            val image =
                InputImage.fromBitmap(
                    bitmap,
                    0
                )

            recognizer
                .process(image)
                .addOnSuccessListener { result ->

                    val text =
                        result.text
                            .replace(
                                Regex("\\s+"),
                                " "
                            )
                            .trim()

                    Log.d(
                        TAG,
                        "OCR RESULT = [$text]"
                    )

                    if (text.isBlank()) {

                        onResult(null)

                    } else {

                        onResult(text)
                    }
                }
                .addOnFailureListener { error ->

                    Log.e(
                        TAG,
                        "OCR FAILED",
                        error
                    )

                    onResult(null)
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "OCR EXCEPTION",
                e
            )

            onResult(null)
        }
    }

    fun close() {

        recognizer.close()
    }
}