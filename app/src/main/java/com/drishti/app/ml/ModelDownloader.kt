package com.drishti.app.ml

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.io.File

object ModelDownloader {

    // Gemma 3 1B IT INT4 — ~600 MB, better multilingual than Gemma 2B
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma3-1b-it-int4/float16/1/gemma3-1b-it-int4.bin"
    const val MODEL_FILENAME = "gemma3-1b-it-int4.bin"

    fun modelPath(): String =
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$MODEL_FILENAME"

    fun isModelPresent(): Boolean = File(modelPath()).exists()

    fun downloadProgress(context: Context): Flow<DownloadState> = callbackFlow {
        if (isModelPresent()) {
            trySend(DownloadState.Done(modelPath()))
            close()
            return@callbackFlow
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
            setTitle("Drishti — Gemma 3 1B model")
            setDescription("Downloading on-device AI model (~600 MB)")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, MODEL_FILENAME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val downloadId = dm.enqueue(req)
        trySend(DownloadState.Downloading(0))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val q = DownloadManager.Query().setFilterById(downloadId)
                    dm.query(q).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                trySend(DownloadState.Done(modelPath()))
                                close()
                            } else {
                                trySend(DownloadState.Failed("Download failed"))
                                close()
                            }
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    fun pollProgress(context: Context, downloadId: Long): Flow<DownloadState> = flow {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val q = DownloadManager.Query().setFilterById(downloadId)
            dm.query(q).use { cursor ->
                if (cursor.moveToFirst()) {
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    emit(DownloadState.Downloading(progress))
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }
}

sealed class DownloadState {
    data class Downloading(val percent: Int) : DownloadState()
    data class Done(val path: String) : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}
