package com.drishti.app.ml

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved person: a name plus one or more face embeddings captured for them. */
data class SavedFace(val name: String, val embeddings: MutableList<FloatArray>)

/**
 * Stores recognised people in a small JSON file in the app's private storage.
 * Multiple embeddings per person improve accuracy across angles/lighting.
 */
class FaceStore(context: Context) {

    private val file = File(context.filesDir, "saved_faces.json")
    private val faces = mutableListOf<SavedFace>()

    init { load() }

    fun all(): List<SavedFace> = faces

    fun add(name: String, embedding: FloatArray) {
        val existing = faces.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            existing.embeddings.add(embedding)
            if (existing.embeddings.size > 5) existing.embeddings.removeAt(0) // keep recent 5
        } else {
            faces.add(SavedFace(name, mutableListOf(embedding)))
        }
        save()
    }

    fun remove(name: String): Boolean {
        val removed = faces.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) save()
        return removed
    }

    /** Best matching name for an embedding, or null if none clears the threshold. */
    fun match(embedding: FloatArray, threshold: Float): Pair<String, Float>? {
        var bestName: String? = null
        var bestSim = -1f
        for (f in faces) {
            for (e in f.embeddings) {
                val sim = cosineSimilarity(embedding, e)
                if (sim > bestSim) { bestSim = sim; bestName = f.name }
            }
        }
        return if (bestName != null && bestSim >= threshold) Pair(bestName, bestSim) else null
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val embArr = obj.getJSONArray("embeddings")
                val embList = mutableListOf<FloatArray>()
                for (j in 0 until embArr.length()) {
                    val vec = embArr.getJSONArray(j)
                    embList.add(FloatArray(vec.length()) { vec.getDouble(it).toFloat() })
                }
                if (embList.isNotEmpty()) faces.add(SavedFace(name, embList))
            }
        } catch (e: Exception) {
            Log.e("FaceStore", "load failed", e)
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (f in faces) {
                val embArr = JSONArray()
                for (e in f.embeddings) {
                    val vec = JSONArray()
                    for (x in e) vec.put(x.toDouble())
                    embArr.put(vec)
                }
                arr.put(JSONObject().put("name", f.name).put("embeddings", embArr))
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("FaceStore", "save failed", e)
        }
    }
}
