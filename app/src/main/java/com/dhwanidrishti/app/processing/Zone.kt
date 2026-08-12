package com.dhwanidrishti.app.processing

/**
 * Horizontal field sectors used by both modes. Mode A uses the same
 * third-width split for L/C/R sound placement; Mode B uses it to say
 * "to your left / center / right".
 */
enum class Zone(val spoken: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right");

    companion object {
        /** Maps a normalized (0..1) x coordinate into a zone. */
        fun fromNormalizedX(x: Float): Zone = when {
            x < 1f / 3f -> LEFT
            x < 2f / 3f -> CENTER
            else -> RIGHT
        }
    }
}
