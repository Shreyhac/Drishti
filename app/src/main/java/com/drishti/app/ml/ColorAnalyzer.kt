package com.drishti.app.ml

import android.graphics.Bitmap
import android.graphics.Color

object ColorAnalyzer {

    private data class NamedColor(val name: String, val r: Float, val g: Float, val b: Float)

    private val PALETTE = listOf(
        NamedColor("red",        220f, 30f,  30f),
        NamedColor("orange",     255f, 140f, 0f),
        NamedColor("yellow",     255f, 220f, 0f),
        NamedColor("green",      34f,  139f, 34f),
        NamedColor("cyan",       0f,   188f, 212f),
        NamedColor("blue",       30f,  60f,  180f),
        NamedColor("purple",     128f, 0f,   128f),
        NamedColor("pink",       255f, 105f, 180f),
        NamedColor("white",      240f, 240f, 240f),
        NamedColor("light gray", 180f, 180f, 180f),
        NamedColor("gray",       128f, 128f, 128f),
        NamedColor("dark gray",  64f,  64f,  64f),
        NamedColor("black",      20f,  20f,  20f),
        NamedColor("brown",      139f, 69f,  19f),
        NamedColor("beige",      245f, 222f, 179f),
        NamedColor("maroon",     128f, 0f,   0f),
        NamedColor("navy",       0f,   0f,   128f),
        NamedColor("olive",      128f, 128f, 0f),
        NamedColor("gold",       255f, 215f, 0f),
        NamedColor("cream",      255f, 253f, 208f),
        NamedColor("silver",     192f, 192f, 192f),
    )

    fun analyze(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, false)
        val counts = mutableMapOf<String, Int>()
        for (x in 0 until small.width step 2) {
            for (y in 0 until small.height step 2) {
                val pixel = small.getPixel(x, y)
                val name = nearest(
                    Color.red(pixel).toFloat(),
                    Color.green(pixel).toFloat(),
                    Color.blue(pixel).toFloat()
                )
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        small.recycle()
        return counts.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(", ") { it.key }
    }

    private fun nearest(r: Float, g: Float, b: Float): String =
        PALETTE.minByOrNull { nc ->
            val dr = r - nc.r; val dg = g - nc.g; val db = b - nc.b
            dr * dr + dg * dg + db * db
        }?.name ?: "unknown"
}
