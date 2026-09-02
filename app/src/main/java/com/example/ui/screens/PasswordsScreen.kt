package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PasswordEntry
import com.example.data.model.PasswordGroup
import com.example.ui.CartinoViewModel
import com.example.ui.SyncUiState
import com.example.util.BitwardenFormat
import com.example.ui.components.AddEditPasswordDialog
import com.example.ui.components.PasswordEntryItem
import com.example.ui.components.PasswordGeneratorDialog
import com.example.ui.components.PasswordGroupItem
import com.example.ui.theme.CustomCyanBlue
import com.example.ui.theme.EmeraldStatus
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.ClipboardAutoClearManager

@Composable
fun PasswordsScreen(
    viewModel: CartinoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var currentGroupId by remember { mutableStateOf<String?>(null) }
    var filterFavoritesOnly by remember { mutableStateOf(false) }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val allGroups by viewModel.allPasswordGroups.collectAsState()
    val allEntries by viewModel.allPasswordEntries.collectAsState()

    var isFabExpanded by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var showAddEntryDialog by remember { mutableStateOf(false) }
    var showGeneratorDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    var groupToRename by remember { mutableStateOf<PasswordGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<PasswordGroup?>(null) }
    var entryToDelete by remember { mutableStateOf<PasswordEntry?>(null) }

    val syncState by viewModel.syncState.collectAsState()
    var showChromeMenu by remember { mutableStateOf(false) }
    var showExportWarningDialog by remember { mutableStateOf(false) }

    var showBitwardenMenu by remember { mutableStateOf(false) }
    var showBitwardenExportDialog by remember { mutableStateOf(false) }
    var selectedBitwardenExportFormat by remember { mutableStateOf(BitwardenFormat.JSON) }

    // Identify if current folder is "Chrome Password" or "Bitwarden Password" group
    val currentGroup = remember(currentGroupId, allGroups) {
        allGroups.find { it.id == currentGroupId }
    }
    val isChromePasswordGroup = remember(currentGroup) {
        currentGroup != null && currentGroup.name.trim().equals("Chrome Password", ignoreCase = true)
    }
    val isBitwardenPasswordGroup = remember(currentGroup) {
        currentGroup != null && currentGroup.name.trim().equals("Bitwarden Password", ignoreCase = true)
    }

    // SAF OpenDocument launcher for Chrome CSV import
    val openChromeCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importChromePasswordsFromUri(it)
        }
    }

    // SAF CreateDocument launcher for Chrome CSV export
    val createChromeCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            viewModel.exportChromePasswordsToUri(it)
        }
    }

    // SAF OpenDocument launcher for Bitwarden import (auto-detecting JSON vs CSV)
    val openBitwardenLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importBitwardenFromUri(it)
        }
    }

    // SAF CreateDocument launchers for Bitwarden export
    val createBitwardenJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportBitwardenToUri(it, BitwardenFormat.JSON)
        }
    }

    val createBitwardenCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            viewModel.exportBitwardenToUri(it, BitwardenFormat.CSV)
        }
    }

    LaunchedEffect(syncState) {
        when (val state = syncState) {
            is SyncUiState.Success -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            is SyncUiState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            else -> Unit
        }
    }

    // Compute breadcrumbs path
    val breadcrumbs = remember(currentGroupId, allGroups) {
        val path = mutableListOf<Pair<String?, String>>()
        path.add(Pair(null, "ریشه"))
        var curr = currentGroupId
        val stack = mutableListOf<Pair<String?, String>>()
        while (curr != null) {
            val grp = allGroups.find { it.id == curr }
            if (grp != null) {
                stack.add(0, Pair(grp.id, grp.name))
                curr = grp.parentGroupId
            } else {
                break
            }
        }
        path.addAll(stack)
        path
    }

    // Filtered items
    val isSearching = searchQuery.isNotBlank()

    val displayedGroups = remember(currentGroupId, allGroups, isSearching) {
        if (isSearching) {
            emptyList()
        } else {
            allGroups.filter { it.parentGroupId == currentGroupId }
        }
    }

    val displayedEntries = remember(currentGroupId, allEntries, isSearching, filterFavoritesOnly) {
        val base = if (isSearching) {
            allEntries
        } else {
            allEntries.filter { it.groupId == currentGroupId }
        }
        if (filterFavoritesOnly) base.filter { it.isFavorite } else base
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(visible = isFabExpanded) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Option 1: Password Generator
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                showGeneratorDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.testTag("fab_generate_password")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تولید پسورد امن", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Option 2: Add Group
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                showAddGroupDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.testTag("fab_add_group")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("افزودن گروه جدید", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Option 3: Add Password Entry
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                editingEntry = null
                                showAddEntryDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("fab_add_entry")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("افزودن پسورد دستی", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Main Floating Action Button
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = GoldPrimary,
                    contentColor = Color.Black,
                    shape = CircleShape,
                    modifier = Modifier.testTag("passwords_fab")
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Menu"
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = {
                    Text(
                        "جستجوی عنوان، نام کاربری، وب‌سایت...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = GoldPrimary) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "پاک کردن", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = GoldPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("passwords_search_bar")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sync / CSV Import-Export State Banner
            if (syncState is SyncUiState.Loading) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "در حال پردازش عملیات پسوردها...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = VazirmatnFontFamily
                        )
                    }
                }
            } else if (syncState is SyncUiState.Success || syncState is SyncUiState.Error) {
                val isSuccess = syncState is SyncUiState.Success
                val msg = if (isSuccess) (syncState as SyncUiState.Success).message else (syncState as SyncUiState.Error).message
                val bannerColor = if (isSuccess) EmeraldStatus.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                val textColor = if (isSuccess) EmeraldStatus else MaterialTheme.colorScheme.onErrorContainer
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bannerColor,
                    border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor,
                                fontWeight = FontWeight.Medium,
                                fontFamily = VazirmatnFontFamily
                            )
                        }
                        IconButton(
                            onClick = { viewModel.resetSyncState() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "بستن",
                                tint = textColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Breadcrumbs Bar (only when not searching)
            if (!isSearching) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            breadcrumbs.forEachIndexed { index, (id, name) ->
                                val isLast = index == breadcrumbs.size - 1
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isLast) { currentGroupId = id }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    if (index == 0) {
                                        Icon(
                                            imageVector = Icons.Default.Home,
                                            contentDescription = null,
                                            tint = if (isLast) GoldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = name,
                                        fontSize = 13.sp,
                                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isLast) GoldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (!isLast) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronLeft,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // More options menu for Chrome Password group
                        if (isChromePasswordGroup) {
                            Box {
                                IconButton(
                                    onClick = { showChromeMenu = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("chrome_passwords_more_menu")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "گزینه‌های کروم",
                                        tint = GoldPrimary
                                    )
                                }

                                DropdownMenu(
                                    expanded = showChromeMenu,
                                    onDismissRequest = { showChromeMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "وارد کردن پسورد از گوگل کروم",
                                                fontFamily = VazirmatnFontFamily,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            showChromeMenu = false
                                            openChromeCsvLauncher.launch(
                                                arrayOf(
                                                    "text/csv",
                                                    "text/comma-separated-values",
                                                    "application/vnd.ms-excel"
                                                )
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.UploadFile,
                                                contentDescription = null,
                                                tint = CustomCyanBlue
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "خروجی گرفتن پسوردها برای گوگل کروم",
                                                fontFamily = VazirmatnFontFamily,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            showChromeMenu = false
                                            showExportWarningDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = null,
                                                tint = GoldPrimary
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // More options menu for Bitwarden Password group
                        if (isBitwardenPasswordGroup) {
                            Box {
                                IconButton(
                                    onClick = { showBitwardenMenu = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("bitwarden_passwords_more_menu")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "گزینه‌های بیتواردن",
                                        tint = GoldPrimary
                                    )
                                }

                                DropdownMenu(
                                    expanded = showBitwardenMenu,
                                    onDismissRequest = { showBitwardenMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "وارد کردن پسوردها از بیتواردن",
                                                fontFamily = VazirmatnFontFamily,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            showBitwardenMenu = false
                                            openBitwardenLauncher.launch(
                                                arrayOf(
                                                    "application/json",
                                                    "text/csv",
                                                    "text/comma-separated-values",
                                                    "text/plain",
                                                    "application/octet-stream",
                                                    "*/*"
                                                )
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.UploadFile,
                                                contentDescription = null,
                                                tint = CustomCyanBlue
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "خروجی گرفتن پسوردها برای بیتواردن",
                                                fontFamily = VazirmatnFontFamily,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            showBitwardenMenu = false
                                            selectedBitwardenExportFormat = BitwardenFormat.JSON
                                            showBitwardenExportDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = null,
                                                tint = GoldPrimary
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Segmented Filter Toggle
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, CustomCyanBlue.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val filterAccentColor = CustomCyanBlue

                    // Option 1: All
                    val isAllSelected = !filterFavoritesOnly
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { filterFavoritesOnly = false },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAllSelected) filterAccentColor else Color.Transparent,
                        border = if (isAllSelected) BorderStroke(1.dp, filterAccentColor) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = if (isAllSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "همه (${displayedEntries.size + displayedGroups.size})",
                                fontSize = 13.sp,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAllSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Option 2: Pinned
                    val isPinnedSelected = filterFavoritesOnly
                    val pinnedCount = displayedEntries.count { it.isFavorite }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { filterFavoritesOnly = true },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isPinnedSelected) filterAccentColor else Color.Transparent,
                        border = if (isPinnedSelected) BorderStroke(1.dp, filterAccentColor) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = if (isPinnedSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "سنجاق‌شده ($pinnedCount)",
                                fontSize = 13.sp,
                                fontWeight = if (isPinnedSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isPinnedSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main List
            val isEmpty = displayedGroups.isEmpty() && displayedEntries.isEmpty()

            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (filterFavoritesOnly) "هیچ پسورد سنجاق‌شده‌ای یافت نشد" else "هیچ پسوردی در این بخش وجود ندارد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isSearching) "موردی مطابق با جستجوی شما پیدا نشد" else "با استفاده از دکمهٔ + اولین پسورد یا گروه خود را اضافه کنید",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Sub-groups section
                    if (displayedGroups.isNotEmpty()) {
                        items(displayedGroups, key = { "group_${it.id}" }) { group ->
                            val subGroupCount = allGroups.count { it.parentGroupId == group.id }
                            val entryCount = allEntries.count { it.groupId == group.id }
                            PasswordGroupItem(
                                group = group,
                                subGroupCount = subGroupCount,
                                entryCount = entryCount,
                                onClick = { currentGroupId = group.id },
                                onRenameClick = { groupToRename = group },
                                onDeleteClick = { groupToDelete = group }
                            )
                        }
                    }

                    // Password Entries section
                    if (displayedEntries.isNotEmpty()) {
                        items(displayedEntries, key = { "entry_${it.id}" }) { entry ->
                            PasswordEntryItem(
                                entry = entry,
                                onClick = {
                                    editingEntry = entry
                                },
                                onFavoriteToggle = {
                                    viewModel.toggleFavoritePasswordEntry(entry)
                                },
                                onEditClick = {
                                    editingEntry = entry
                                },
                                onDeleteClick = {
                                    entryToDelete = entry
                                },
                                onCopyUsername = {
                                    if (entry.username.isNotBlank()) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Username", entry.username))
                                        ClipboardAutoClearManager.onCopied(context, entry.username)
                                        Toast.makeText(context, "نام کاربری کپی شد", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onCopyPassword = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Password", entry.password))
                                    ClipboardAutoClearManager.onCopied(context, entry.password)
                                    Toast.makeText(context, "پسورد کپی شد", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Add / Edit Entry BottomSheet
    if (showAddEntryDialog || editingEntry != null) {
        AddEditPasswordDialog(
            initialEntry = editingEntry,
            groupId = currentGroupId,
            availableGroups = allGroups,
            onDismiss = {
                showAddEntryDialog = false
                editingEntry = null
            },
            onSave = { entryToSave ->
                viewModel.addOrUpdatePasswordEntry(entryToSave)
                showAddEntryDialog = false
                editingEntry = null
            }
        )
    }

    // Password Generator BottomSheet
    if (showGeneratorDialog) {
        PasswordGeneratorDialog(
            viewModel = viewModel,
            onDismiss = { showGeneratorDialog = false }
        )
    }

    // Add Group Dialog
    if (showAddGroupDialog) {
        var newGroupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddGroupDialog = false },
            title = { Text("افزودن گروه جدید", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نام گروه یا دسته‌بندی را وارد کنید:")
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        placeholder = { Text("مثال: شبکه‌های اجتماعی، حساب‌های بانکی") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_group_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            viewModel.addOrUpdateGroup(
                                PasswordGroup(
                                    name = newGroupName.trim(),
                                    parentGroupId = currentGroupId
                                )
                            )
                            showAddGroupDialog = false
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ایجاد گروه")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddGroupDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    // Rename Group Dialog
    groupToRename?.let { grp ->
        var renameText by remember { mutableStateOf(grp.name) }
        AlertDialog(
            onDismissRequest = { groupToRename = null },
            title = { Text("تغییر نام گروه", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نام جدید گروه را وارد کنید:")
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_group_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.addOrUpdateGroup(grp.copy(name = renameText.trim()))
                            groupToRename = null
                        }
                    },
                    enabled = renameText.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToRename = null }) {
                    Text("انصراف")
                }
            }
        )
    }

    // Cascade Delete Group Dialog
    groupToDelete?.let { grp ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("حذف آبشاری گروه", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = {
                Text(
                    "آیا از حذف گروه «${grp.name}» اطمینان دارید؟ با حذف این گروه، تمامی زیرگروه‌ها و پسوردهای موجود در آن نیز به طور کامل حذف خواهند شد."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (currentGroupId == grp.id) {
                            currentGroupId = grp.parentGroupId
                        }
                        viewModel.deleteGroupCascade(grp)
                        groupToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حذف قطعی")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("انصراف")
                }
            }
        )
    }

    // Delete Entry Dialog
    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("حذف پسورد", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = {
                Text("آیا از حذف پسورد «${entry.title}» اطمینان دارید؟")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePasswordEntry(entry)
                        entryToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text("انصراف")
                }
            }
        )
    }

    // Chrome Password CSV Export Warning Dialog
    if (showExportWarningDialog) {
        AlertDialog(
            onDismissRequest = { showExportWarningDialog = false },
            title = {
                Text(
                    text = "هشدار امنیتی",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = VazirmatnFontFamily
                )
            },
            text = {
                Text(
                    text = "خروجی CSV شامل پسوردها بهصورت متن ساده است. این فایل را در مکان امن نگه دارید.",
                    fontFamily = VazirmatnFontFamily,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportWarningDialog = false
                        createChromeCsvLauncher.launch("Chrome Passwords.csv")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ادامه", fontFamily = VazirmatnFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportWarningDialog = false }) {
                    Text("انصراف", fontFamily = VazirmatnFontFamily)
                }
            }
        )
    }

    // Bitwarden Password Export Format & Security Warning Dialog
    if (showBitwardenExportDialog) {
        AlertDialog(
            onDismissRequest = { showBitwardenExportDialog = false },
            title = {
                Text(
                    text = "خروجی پسوردهای بیتواردن",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = VazirmatnFontFamily
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "خروجی شامل پسوردها به‌صورت متن ساده است. این فایل را در مکان امن نگه دارید.",
                        fontFamily = VazirmatnFontFamily,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "انتخاب قالب استاندارد خروجی:",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = VazirmatnFontFamily,
                        fontSize = 13.sp
                    )

                    // Option JSON
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedBitwardenExportFormat == BitwardenFormat.JSON)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = BorderStroke(
                            1.dp,
                            if (selectedBitwardenExportFormat == BitwardenFormat.JSON) GoldPrimary else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedBitwardenExportFormat = BitwardenFormat.JSON }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = selectedBitwardenExportFormat == BitwardenFormat.JSON,
                                onClick = { selectedBitwardenExportFormat = BitwardenFormat.JSON },
                                colors = RadioButtonDefaults.colors(selectedColor = GoldPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "JSON (استاندارد بیتواردن)",
                                fontFamily = VazirmatnFontFamily,
                                fontSize = 13.sp,
                                fontWeight = if (selectedBitwardenExportFormat == BitwardenFormat.JSON) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    // Option CSV
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedBitwardenExportFormat == BitwardenFormat.CSV)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = BorderStroke(
                            1.dp,
                            if (selectedBitwardenExportFormat == BitwardenFormat.CSV) GoldPrimary else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedBitwardenExportFormat = BitwardenFormat.CSV }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = selectedBitwardenExportFormat == BitwardenFormat.CSV,
                                onClick = { selectedBitwardenExportFormat = BitwardenFormat.CSV },
                                colors = RadioButtonDefaults.colors(selectedColor = GoldPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CSV (استاندارد بیتواردن)",
                                fontFamily = VazirmatnFontFamily,
                                fontSize = 13.sp,
                                fontWeight = if (selectedBitwardenExportFormat == BitwardenFormat.CSV) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBitwardenExportDialog = false
                        when (selectedBitwardenExportFormat) {
                            BitwardenFormat.JSON -> createBitwardenJsonLauncher.launch("Bitwarden Passwords.json")
                            BitwardenFormat.CSV -> createBitwardenCsvLauncher.launch("Bitwarden Passwords.csv")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ادامه", fontFamily = VazirmatnFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBitwardenExportDialog = false }) {
                    Text("انصراف", fontFamily = VazirmatnFontFamily)
                }
            }
        )
    }
}
