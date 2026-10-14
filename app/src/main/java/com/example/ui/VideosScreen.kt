package com.example.ui

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.RecordingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
    viewModel: VideosViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videos by viewModel.videos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Dialog state
    var videoToRename by remember { mutableStateOf<RecordingModel?>(null) }
    var videoToDelete by remember { mutableStateOf<RecordingModel?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    // Reload list when screen enters
    LaunchedEffect(Unit) {
        viewModel.loadVideos(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meus Vídeos", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadVideos(context) }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Atualizar")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading && videos.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (videos.isEmpty()) {
                // Beautiful Empty State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Nenhuma gravação encontrada",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "As gravações da tela salvas no aplicativo aparecerão aqui para reprodução e exportação rápida.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(videos, key = { it.id }) { video ->
                        VideoItemCard(
                            video = video,
                            onPlay = { viewModel.playVideo(context, video) },
                            onShare = { viewModel.shareVideo(context, video) },
                            onRename = {
                                videoToRename = video
                                renameInputText = video.name.removeSuffix(".mp4")
                            },
                            onDelete = { videoToDelete = video }
                        )
                    }
                }
            }
        }

        // --- Dialogs ---

        // Rename Dialog
        if (videoToRename != null) {
            AlertDialog(
                onDismissRequest = { videoToRename = null },
                title = { Text("Renomear Vídeo", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            text = "Insira o novo nome para o arquivo:",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = renameInputText,
                            onValueChange = { renameInputText = it },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("rename_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val currentVideo = videoToRename
                            if (currentVideo != null && renameInputText.isNotBlank()) {
                                viewModel.renameVideo(context, currentVideo, renameInputText) { success ->
                                    if (success) {
                                        Toast.makeText(context, "Vídeo renomeado", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Falha ao renomear", Toast.LENGTH_SHORT).show()
                                    }
                                    videoToRename = null
                                }
                            }
                        }
                    ) {
                        Text("Renomear")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { videoToRename = null }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        // Delete Confirmation Dialog
        if (videoToDelete != null) {
            AlertDialog(
                onDismissRequest = { videoToDelete = null },
                icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Excluir Gravação?") },
                text = {
                    Text(
                        "Tem certeza de que deseja excluir permanentemente o arquivo \"${videoToDelete?.name}\"? Esta ação é irreversível.",
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            val currentVideo = videoToDelete
                            if (currentVideo != null) {
                                viewModel.deleteVideo(context, currentVideo) { success ->
                                    if (success) {
                                        Toast.makeText(context, "Vídeo excluído com sucesso", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Falha ao excluir vídeo", Toast.LENGTH_SHORT).show()
                                    }
                                    videoToDelete = null
                                }
                            }
                        }
                    ) {
                        Text("Excluir", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { videoToDelete = null }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

@Composable
fun VideoItemCard(
    video: RecordingModel,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .testTag("video_card_${video.id}")
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail container
                Box(
                    modifier = Modifier
                        .size(width = 110.dp, height = 74.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    VideoThumbnail(
                        filePath = video.filePath,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Duration Pill overlay on thumbnail
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = video.formattedDuration,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Info Section
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.formattedDate,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = video.formattedSize,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            // Action footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play action
                TextButton(onClick = onPlay) {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Abrir", fontSize = 13.sp)
                }

                Row {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Compartilhar",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onRename) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Renomear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Excluir",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoThumbnail(filePath: String, modifier: Modifier = Modifier) {
    var thumbnail by remember(filePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val file = File(filePath)
                    if (file.exists()) {
                        ThumbnailUtils.createVideoThumbnail(
                            file,
                            android.util.Size(220, 148),
                            null
                        )
                    } else null
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        filePath,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                }
            } catch (e: Exception) {
                null
            }
        }?.let {
            thumbnail = it
        }
    }

    if (thumbnail != null) {
        Image(
            bitmap = thumbnail!!.asImageBitmap(),
            contentDescription = "Video preview",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
