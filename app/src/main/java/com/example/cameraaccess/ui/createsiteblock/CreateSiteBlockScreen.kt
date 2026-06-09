package com.example.cameraaccess.ui.createsiteblock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameraaccess.viewmodel.CreateSiteBlockViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSiteBlockScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    createViewModel: CreateSiteBlockViewModel = viewModel()
) {
    val state = createViewModel.uiState.value

    if (state.navigateNext) {
        LaunchedEffect(Unit) {
            createViewModel.consumeNavigation()
            onNext()
        }
    }

    if (state.data == null) {
        // Show error screen if no data is available
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Error") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.error ?: "Unknown error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    } else {
        // Render content if data is present
        state.data?.let { data ->
            CreateSiteBlockContent(
                data = data,
                isLoading = state.isLoading,
                error = state.error,
                onBack = onBack,

                // form values from ViewModel
                selectedSiteIndex = state.selectedSiteIndex,
                selectedBlockIndex = state.selectedBlockIndex,
                selectedScanTypeIndex = state.selectedScanTypeIndex,
                rowWidth = state.rowWidth,
                rowHeight = state.rowHeight,
                bayLength = state.bayLength,
                addLens = state.addLens,
                multiplier = state.multiplier,

                // callbacks to ViewModel setters
                onSelectSite = createViewModel::setSelectedSiteIndex,
                onSelectBlock = createViewModel::setSelectedBlockIndex,
                onSelectScanType = createViewModel::setSelectedScanTypeIndex,
                onRowWidthChange = createViewModel::setRowWidth,
                onRowHeightChange = createViewModel::setRowHeight,
                onBayLengthChange = createViewModel::setBayLength,
                onAddLensChange = createViewModel::setAddLens,
                onMultiplierChange = createViewModel::setMultiplier,

                onSubmit = { createViewModel.submit() }
            )
        }
    }
}
