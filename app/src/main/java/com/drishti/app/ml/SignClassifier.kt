package com.drishti.app.ml

import kotlin.math.sqrt

/**
 * Rule-based finger-state classifier that runs on the 21 hand landmarks.
 *
 * This is NOT a trained ML model. MediaPipe's built-in GestureRecognizer only knows
 * 8 gestures, and no public pre-trained ISL/ASL .task model exists. So for additional
 * static signs we use simple geometry on the landmarks: a finger is "extended" when its
 * tip is farther from the wrist than its middle joint. This reliably recognises number
 * signs (counting fingers) but cannot do the full ASL/ISL alphabet — those handshapes
 * are too subtle for pure heuristics and would need a trained classifier.
 */
object SignClassifier {

    data class Sign(val label: String, val speech: String)

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    fun classify(lm: List<Pair<Float, Float>>): Sign? {
        if (lm.size != 21) return null
        val wrist = lm[0]

        // A finger is extended if its tip is farther from the wrist than its PIP joint.
        // Orientation-independent because it compares distances, not screen coordinates.
        fun extended(tip: Int, pip: Int) = dist(lm[tip], wrist) > dist(lm[pip], wrist)

        val index  = extended(8, 6)
        val middle = extended(12, 10)
        val ring   = extended(16, 14)
        val pinky  = extended(20, 18)
        // Thumb compared against its MCP joint (index 2)
        val thumb  = dist(lm[4], wrist) > dist(lm[2], wrist) * 1.05f

        val fingersOnly = listOf(index, middle, ring, pinky).count { it }
        val total = fingersOnly + if (thumb) 1 else 0

        return when {
            // 0,1,2 and full-open are already covered well by MediaPipe
            // (Closed_Fist, Pointing_Up, Victory, Open_Palm) so we skip those
            // to avoid contradicting the trained model.
            total == 0 -> null
            index && middle && !ring && !pinky -> null  // Victory — MediaPipe handles it
            index && !middle && !ring && !pinky -> null  // Pointing — MediaPipe handles it
            total >= 5 -> null                            // Open hand — MediaPipe handles it
            fingersOnly == 3 && !thumb -> Sign("Three", "Three")
            fingersOnly == 4 -> Sign("Four", "Four")
            else -> Sign("Count$total", "Showing $total fingers")
        }
    }
}
