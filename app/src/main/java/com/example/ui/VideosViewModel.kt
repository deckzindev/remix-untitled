package com.example.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.RecordingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideosViewModel : ViewModel() {

    private val _videos = MutableStateFlow<List<RecordingModel>>(emptyList())
    val videos: StateFlow<List<RecordingModel>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadVideos(context: Context) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = mutableListOf<RecordingModel>()
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE
                )

                val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                // Query videos from Movies/GravadorDeTela folder
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                } else {
                    "${MediaStore.Video.Media.DATA} LIKE ?"
                }

                val selectionArgs = arrayOf("%Movies/GravadorDeTela%")
                val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                        val path = cursor.getString(pathColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        val durationMs = cursor.getLong(durationColumn)
                        val sizeBytes = cursor.getLong(sizeColumn)

                        val contentUri = Uri.withAppendedPath(collectionUri, id.toString())
                        val formattedDate = dateFormat.format(Date(dateAdded * 1000))
                        
                        // Formata duração (HH:MM:SS ou MM:SS)
                        val durSec = durationMs / 1000
                        val formattedDuration = if (durSec >= 3600) {
                            String.format(Locale.getDefault(), "%02d:%02d:%02d", durSec / 3600, (durSec % 3600) / 60, durSec % 60)
                        } else {
                            String.format(Locale.getDefault(), "%02d:%02d", durSec / 60, durSec % 60)
                        }

                        // Formata tamanho do arquivo
                        val sizeKb = sizeBytes / 1024.0
                        val sizeMb = sizeKb / 1024.0
                        val formattedSize = if (sizeMb >= 1.0) {
                            String.format(Locale.getDefault(), "%.1f MB", sizeMb)
                        } else {
                            String.format(Locale.getDefault(), "%.1f KB", sizeKb)
                        }

                        list.add(
                            RecordingModel(
                                id = id,
                                uri = contentUri,
                                filePath = path,
                                name = name,
                                dateAdded = dateAdded,
                                formattedDate = formattedDate,
                                durationMs = durationMs,
                                formattedDuration = formattedDuration,
                                sizeBytes = sizeBytes,
                                formattedSize = formattedSize
                            )
                        )
                    }
                }

                _videos.value = list
            } catch (e: Exception) {
                Log.e("VideosViewModel", "Erro ao carregar vídeos: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteVideo(context: Context, video: RecordingModel, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                // Deleta via ContentResolver
                val deletedRows = context.contentResolver.delete(video.uri, null, null)
                success = deletedRows > 0

                // Fallback para arquivo físico direto
                val file = File(video.filePath)
                if (file.exists()) {
                    val fileDeleted = file.delete()
                    if (fileDeleted) success = true
                }
            } catch (e: Exception) {
                Log.e("VideosViewModel", "Erro ao deletar vídeo: ${e.message}", e)
            } finally {
                launch(Dispatchers.Main) {
                    if (success) {
                        loadVideos(context) // Recarrega
                    }
                    onComplete(success)
                }
            }
        }
    }

    fun renameVideo(context: Context, video: RecordingModel, newName: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                var cleanName = newName.trim()
                if (cleanName.isEmpty()) return@launch

                if (!cleanName.endsWith(".mp4", ignoreCase = true)) {
                    cleanName += ".mp4"
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, cleanName)
                }

                val updatedRows = context.contentResolver.update(video.uri, contentValues, null, null)
                success = updatedRows > 0
            } catch (e: Exception) {
                Log.e("VideosViewModel", "Erro ao renomear vídeo: ${e.message}", e)
            } finally {
                launch(Dispatchers.Main) {
                    if (success) {
                        loadVideos(context) // Recarrega
                    }
                    onComplete(success)
                }
            }
        }
    }

    fun shareVideo(context: Context, video: RecordingModel) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, video.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Compartilhar vídeo com..."))
        } catch (e: Exception) {
            Log.e("VideosViewModel", "Erro ao compartilhar vídeo: ${e.message}")
        }
    }

    fun playVideo(context: Context, video: RecordingModel) {
        try {
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(video.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(playIntent)
        } catch (e: Exception) {
            Log.e("VideosViewModel", "Erro ao abrir player de vídeo: ${e.message}")
            Toast.makeText(context, "Nenhum player de vídeo compatível encontrado", Toast.LENGTH_SHORT).show()
        }
    }
}
