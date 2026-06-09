package com.example.cameraaccess.ui.createsiteblock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cameraaccess.data.model.SiteBlockResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSiteBlockContent(
    data: SiteBlockResponse,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,

    // values (from ViewModel)
    selectedSiteIndex: Int,
    selectedBlockIndex: Int,
    selectedScanTypeIndex: Int,
    rowWidth: String,
    rowHeight: String,
    bayLength: String,
    addLens: Boolean,
    multiplier: String,

    // callbacks (to ViewModel)
    onSelectSite: (Int) -> Unit,
    onSelectBlock: (Int) -> Unit,
    onSelectScanType: (Int) -> Unit,
    onRowWidthChange: (String) -> Unit,
    onRowHeightChange: (String) -> Unit,
    onBayLengthChange: (String) -> Unit,
    onAddLensChange: (Boolean) -> Unit,
    onMultiplierChange: (String) -> Unit,

    onSubmit: () -> Unit
) {
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    val sites = data.sites
    val scanTypes = data.scan_type

    val blocks = if (selectedSiteIndex in sites.indices) {
        sites[selectedSiteIndex].blocks
    } else {
        emptyList()
    }

    // Regex to allow only numbers and a single optional decimal point
    val numberRegex = remember { Regex("^\\d*\\.?\\d*$") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Field Information") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // Ensures the column resizes when the keyboard is open
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(18.dp))

            Text(
                text = "Select site, block and scan type",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(10.dp))

            SimpleDropdown(
                label = "Site",
                options = sites.map { it.address ?: "Site ${it.site_id}" },
                selectedIndex = selectedSiteIndex,
                onSelect = onSelectSite
            )

            Spacer(Modifier.height(12.dp))

            SimpleDropdown(
                label = "Block",
                options = blocks.map { it.block_name ?: "Block ${it.block_id}" },
                selectedIndex = selectedBlockIndex,
                onSelect = onSelectBlock
            )

            Spacer(Modifier.height(12.dp))

            SimpleDropdown(
                label = "Scan type",
                options = scanTypes.map { it.name ?: "Scan ${it.id}" },
                selectedIndex = selectedScanTypeIndex,
                onSelect = onSelectScanType
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Row & bay measurements",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = rowWidth,
                onValueChange = {
                    if (it.isEmpty() || it.matches(numberRegex)) {
                        onRowWidthChange(it)
                    }
                },
                label = { Text("Row width (m)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = rowHeight,
                onValueChange = {
                    if (it.isEmpty() || it.matches(numberRegex)) {
                        onRowHeightChange(it)
                    }
                },
                label = { Text("Row height (m)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = bayLength,
                onValueChange = {
                    if (it.isEmpty() || it.matches(numberRegex)) {
                        onBayLengthChange(it)
                    }
                },
                label = { Text("Bay length (m)") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = if (addLens) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { focusManager.clearFocus() }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add lens multiplier")
                Spacer(Modifier.weight(1f))
                Switch(checked = addLens, onCheckedChange = onAddLensChange)
            }

//            if (addLens) {
//                Spacer(Modifier.height(10.dp))
//                OutlinedTextField(
//                    value = multiplier,
//                    onValueChange = {
//                        if (it.isEmpty() || it.matches(numberRegex)) {
//                            onMultiplierChange(it)
//                        }
//                    },
//                    label = { Text("Multiplier (e.g. 1.2)") },
//                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
//                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
//                    modifier = Modifier.fillMaxWidth()
//                )
//            }

            Spacer(Modifier.height(14.dp))

            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    color = Color(0xFFB00020),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onSubmit,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1D5B47)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .alpha(0.9f),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  Saving...")
                } else {
                    Text("Next")
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText =
        if (selectedIndex in options.indices) options[selectedIndex] else "Select..."

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    }
                )
            }
        }
    }
}
