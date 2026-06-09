package com.example.cameraaccess.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tau.research.R
import com.example.cameraaccess.data.entities.RecordingEntity
import com.example.cameraaccess.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onStartCapture: () -> Unit,
    onLogout: () -> Unit,
    dashboardViewModel: DashboardViewModel = viewModel()
) {
    val state = dashboardViewModel.uiState.value
    var videoToPlay by remember { mutableStateOf<RecordingEntity?>(null) }
    var itemToDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val brandColor = Color(0xFF164D3D)

    if (videoToPlay != null) {
        VideoPlayerDialog(
            videoUri = videoToPlay!!.videoPath.toUri(),
            onDismiss = { videoToPlay = null }
        )
    }

    if (itemToDelete != null) {
        DeleteConfirmationDialog(
            onConfirm = {
                dashboardViewModel.deleteRecording(itemToDelete!!)
                itemToDelete = null
            },
            onDismiss = { itemToDelete = null }
        )
    }

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = {
                showLogoutDialog = false
                dashboardViewModel.logout()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    LaunchedEffect(state.navigateToMetadata) {
        if (state.navigateToMetadata) {
            dashboardViewModel.consumeNavigation()
            onStartCapture()
        }
    }

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) {
            dashboardViewModel.consumeNavigation()
            onLogout()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (state.selectedIds.isNotEmpty()) "${state.selectedIds.size} Selected" else "Dashboard",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                actions = {
                    if (state.selectedIds.isNotEmpty()) {
                        IconButton(onClick = { dashboardViewModel.uploadSelected() }) {
                            Icon(Icons.Default.Upload, contentDescription = "Upload Selected")
                        }
                    }
                    IconButton(onClick = { showLogoutDialog = true }) {
                        Icon(Icons.Outlined.Logout, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = brandColor,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.uploadQueue.isNotEmpty() || state.uploadingRecordingId != null) {
                    val queueSize = state.uploadQueue.size + (if (state.uploadingRecordingId != null) 1 else 0)
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text("$queueSize in queue", modifier = Modifier.padding(4.dp))
                    }
                }
                
                ExtendedFloatingActionButton(
                    onClick = { dashboardViewModel.startNewScan() },
                    modifier = Modifier.navigationBarsPadding(),
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add Icon") },
                    text = { Text("New Scan") }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.isLoading && state.recordings.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.recordings.isEmpty()) {
                Text(
                    "No recordings yet. Tap 'New Scan' to begin.",
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.recordings, key = { it.id }) { item ->
                        val isUploadingThis = state.uploadingRecordingId == item.id
                        val isInQueue = state.uploadQueue.contains(item.id)

                        val animatedProgress by animateFloatAsState(
                            targetValue = if (isUploadingThis) state.uploadProgress else 0f,
                            animationSpec = tween(durationMillis = 300),
                            label = "UploadProgressAnimation"
                        )

                        RecordingItem(
                            modifier = Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(300)),
                            item = item,
                            isOnline = state.isOnline,
                            onPlay = { videoToPlay = it },
                            onUpload = { dashboardViewModel.uploadRecording(it) },
                            onDelete = { itemToDelete = it },
                            uploadEnabled = !isUploadingThis && !isInQueue,
                            deleteEnabled = !isUploadingThis && !isInQueue,
                            isUploading = isUploadingThis || isInQueue,
                            progress = animatedProgress,
                            stage = if (isUploadingThis) state.uploadStage else if (isInQueue) "In Queue" else "",
                            isSelected = state.selectedIds.contains(item.id),
                            onToggleSelection = { dashboardViewModel.toggleSelection(item.id) }
                        )
                    }
                }
            }

            // Error Snackbar
            state.error?.let {
                val snackbarHostState = remember { SnackbarHostState() }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                LaunchedEffect(it) {
                    snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
                }
            }
        }
    }
}

@Composable
fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logout") },
        text = { Text("Are you sure you want to log out?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Logout")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text = { Text("Are you sure you want to delete this recording? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No")
            }
        }
    )
}

@Composable
fun VideoPlayerDialog(
    videoUri: android.net.Uri,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }
}
