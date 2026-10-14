package com.example.data

import android.net.Uri

data class RecordingModel(
    val id: Long,
    val uri: Uri,
    val filePath: String,
    val name: String,
    val dateAdded: Long, // timestamp
    val formattedDate: String,
    val durationMs: Long,
    val formattedDuration: String,
    val sizeBytes: Long,
    val formattedSize: String
)
