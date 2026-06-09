package com.example.cameraaccess.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cameraaccess.data.entities.RecordingEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun RecordingItem(
    modifier: Modifier = Modifier,
    item: RecordingEntity,
    isOnline: Boolean,
    onPlay: (RecordingEntity) -> Unit,
    onUpload: (RecordingEntity) -> Unit,
    onDelete: (RecordingEntity) -> Unit,
    uploadEnabled: Boolean,
    deleteEnabled: Boolean,
    isUploading: Boolean,
    progress: Float,
    stage: String,
    isSelected: Boolean,
    onToggleSelection: () -> Unit
) {
    val date = remember(item.createdAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(item.createdAt))
    }
    val durationFormatted = remember(item.duration) {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(item.duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(item.duration) - TimeUnit.MINUTES.toSeconds(minutes)
        String.format("%02d:%02d", minutes, seconds)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    enabled = !isUploading
                )
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Videocam,
                            contentDescription = "Video File",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.gpxPath.substringAfterLast('/').substringBeforeLast('.'),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        InfoChip(text = date, icon = Icons.Outlined.Schedule)
                        InfoChip(text = durationFormatted, icon = Icons.Outlined.Timer)
                    }
                }
                IconButton(
                    onClick = { onPlay(item) },
                    enabled = !isUploading,
                    modifier = Modifier
                        .size(35.dp)
                        .clip(CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play Video", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            AnimatedVisibility(visible = isUploading) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = stage, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${String.format("%.1f", progress * 100)}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onUpload(item) },
                    enabled = uploadEnabled && isOnline && !isUploading
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = "Upload", modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (isOnline) "Upload" else "No Internet")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onDelete(item) },
                    enabled = deleteEnabled && !isUploading,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
