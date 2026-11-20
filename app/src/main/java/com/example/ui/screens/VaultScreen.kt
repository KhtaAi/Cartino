package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.ui.VaultDuplicateAction
import com.example.ui.VaultDuplicateCandidate
import com.example.ui.VaultImportReport
import com.example.ui.components.VaultFileDetailsSheet
import com.example.ui.components.VaultFileItem
import com.example.ui.components.VaultGroupItem
import com.example.util.VaultZipExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VaultScreen(
    viewModel: CartinoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val groups by viewModel.allVaultGroups.collectAsState()
    val files by viewModel.allVaultFiles.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var pendingDuplicatePrompt by remember {
        mutableStateOf<Pair<VaultDuplicateCandidate, CompletableDeferred<VaultDuplicateAction>>?>(null)
    }

    VaultScreenContent(
        groups = groups,
        files = files,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        onAddOrUpdateGroup = { group -> viewModel.addOrUpdateVaultGroup(group) },
        onDeleteGroupCascade = { group, onSuccess, onError ->
            viewModel.deleteVaultGroupCascade(
                group = group,
                onSuccess = { deletedCount, _ -> onSuccess(deletedCount) },
                onError = onError
            )
        },
        isImporting = isImporting,
        isExporting = isExporting,
        pendingDuplicateCandidate = pendingDuplicatePrompt?.first,
        onResolveDuplicate = { action ->
            val current = pendingDuplicatePrompt
            pendingDuplicatePrompt = null
            current?.second?.complete(action)
        },
        onImportFiles = { uris, targetGroupId ->
            isImporting = true
            viewModel.importVaultFiles(
                uris = uris,
                currentGroupId = targetGroupId,
                onDuplicatePrompt = { candidate ->
                    val deferred = CompletableDeferred<VaultDuplicateAction>()
                    withContext(Dispatchers.Main) {
                        pendingDuplicatePrompt = candidate to deferred
                    }
                    deferred.await()
                },
                onSuccess = { report ->
                    isImporting = false
                    Toast.makeText(
                        context,
                        formatVaultImportReportToast(report),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { errorMsg ->
                    isImporting = false
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        },
        onUpdateFileMetadata = { fileId, newName, newNotes, newGroupId ->
            viewModel.updateVaultFileMetadata(
                fileId = fileId,
                newDisplayName = newName,
                newNotes = newNotes,
                newGroupId = newGroupId,
                onSuccess = {
                    Toast.makeText(context, "تغییرات فایل ذخیره شد", Toast.LENGTH_SHORT).show()
                },
                onError = { errorMsg ->
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        },
        onDeleteFile = { file ->
            viewModel.deleteVaultFile(
                file = file,
                onSuccess = {
                    Toast.makeText(context, "فایل حذف شد", Toast.LENGTH_SHORT).show()
                },
                onError = { errorMsg ->
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        },
        onExportFile = { file, targetUri ->
            isExporting = true
            viewModel.exportVaultFile(
                file = file,
                targetUri = targetUri,
                onSuccess = {
                    isExporting = false
                    val pathLabel = resolveExportedUriDisplayPath(context, targetUri)
                    Toast.makeText(
                        context,
                        "فایل با موفقیت خارج شد: $pathLabel",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { errorMsg ->
                    isExporting = false
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        },
        onExportGroupAsZip = { group, targetUri, password ->
            isExporting = true
            viewModel.exportVaultGroupAsZip(
                groupId = group.id,
                targetUri = targetUri,
                zipPassword = password,
                onSuccess = { result ->
                    isExporting = false
                    Toast.makeText(
                        context,
                        "گروه ${group.name} با \u2068${result.totalFiles}\u2069 فایل به‌صورت ZIP رمزدار خارج شد",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { errorMsg ->
                    isExporting = false
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        },
        modifier = modifier
    )
}

@Composable
internal fun VaultScreenContent(
    groups: List<VaultGroup>,
    files: List<VaultFile>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onAddOrUpdateGroup: (VaultGroup) -> Unit,
    onDeleteGroupCascade: (
        VaultGroup,
        onSuccess: (deletedFilesCount: Int) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    isImporting: Boolean = false,
    isExporting: Boolean = false,
    pendingDuplicateCandidate: VaultDuplicateCandidate? = null,
    onResolveDuplicate: (VaultDuplicateAction) -> Unit = {},
    onImportFiles: (uris: List<Uri>, targetGroupId: String?) -> Unit = { _, _ -> },
    onUpdateFileMetadata: (
        fileId: String,
        newDisplayName: String,
        newNotes: String,
        newGroupId: String?
    ) -> Unit = { _, _, _, _ -> },
    onDeleteFile: (VaultFile) -> Unit = {},
    onExportFile: (file: VaultFile, targetUri: Uri) -> Unit = { _, _ -> },
    onExportGroupAsZip: (group: VaultGroup, targetUri: Uri, password: CharArray) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Nested group navigation stack; null represents root
    var navigationStack by remember { mutableStateOf(listOf<String?>(null)) }
    val currentGroupId = navigationStack.lastOrNull()

    // SAF multi-document picker registered unconditionally at top level of composable
    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportFiles(uris, currentGroupId)
        }
    }

    // SAF CreateDocument launcher registered unconditionally at top level (independent of sheet lifecycle)
    var pendingFileToExport by remember { mutableStateOf<VaultFile?>(null) }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.CreateDocument("*/*") {
            override fun createIntent(context: Context, input: String): Intent {
                val targetMime = pendingFileToExport?.mimeType
                    ?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                return super.createIntent(context, input).setType(targetMime)
            }
        }
    ) { targetUri ->
        val exportedFile = pendingFileToExport
        pendingFileToExport = null
        if (targetUri != null && exportedFile != null) {
            onExportFile(exportedFile, targetUri)
        }
    }

    // SAF CreateDocument launcher for encrypted ZIP export of a Vault group
    var pendingZipExportRequest by remember { mutableStateOf<Pair<VaultGroup, String>?>(null) }
    val exportGroupZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { targetUri ->
        val request = pendingZipExportRequest
        pendingZipExportRequest = null
        if (targetUri != null && request != null) {
            val passwordChars = request.second.toCharArray()
            try {
                onExportGroupAsZip(request.first, targetUri, passwordChars)
            } finally {
                passwordChars.fill('\u0000')
            }
        }
    }

    fun navigateToGroup(groupId: String?) {
        if (groupId != currentGroupId) {
            navigationStack = navigationStack + groupId
        }
    }

    fun navigateBack(): Boolean {
        if (navigationStack.size > 1) {
            navigationStack = navigationStack.dropLast(1)
            return true
        }
        return false
    }

    fun navigateToBreadcrumb(targetId: String?) {
        val idx = navigationStack.lastIndexOf(targetId)
        if (idx != -1) {
            navigationStack = navigationStack.subList(0, idx + 1)
        } else {
            val chain = mutableListOf<String?>()
            val visited = mutableSetOf<String>()
            var curr = targetId
            while (curr != null && visited.add(curr)) {
                val grp = groups.find { it.id == curr } ?: break
                chain.add(0, grp.id)
                curr = grp.parentGroupId
            }
            navigationStack = listOf<String?>(null) + chain
        }
    }

    // Repair navigation stack if groups are deleted or hierarchy changes
    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        val filtered = navigationStack.filter { it == null || it in validGroupIds }
        if (filtered.isEmpty()) {
            navigationStack = listOf(null)
        } else if (filtered != navigationStack) {
            navigationStack = filtered
        }
    }

    // System back: first clear active search, then pop one folder level, otherwise default
    BackHandler(enabled = searchQuery.isNotBlank() || navigationStack.size > 1) {
        if (searchQuery.isNotBlank()) {
            onSearchQueryChange("")
        } else {
            navigateBack()
        }
    }

    // Compute breadcrumbs path
    val breadcrumbs = remember(currentGroupId, groups) {
        val path = mutableListOf<Pair<String?, String>>()
        path.add(Pair(null, "ریشه"))
        val stack = mutableListOf<Pair<String?, String>>()
        val visited = mutableSetOf<String>()
        var curr = currentGroupId
        while (curr != null && visited.add(curr)) {
            val grp = groups.find { it.id == curr } ?: break
            stack.add(0, Pair(grp.id, grp.name))
            curr = grp.parentGroupId
        }
        path.addAll(stack)
        path
    }

    val isSearching = searchQuery.isNotBlank()

    // Sub-groups for current level, sorted alphabetically
    val displayedGroups = remember(currentGroupId, groups, isSearching) {
        if (isSearching) {
            emptyList()
        } else {
            groups.filter { it.parentGroupId == currentGroupId }
                .sortedBy { it.name.lowercase() }
        }
    }

    // Files for current level (or cross-group matches while searching)
    val displayedFiles = remember(currentGroupId, files, groups, isSearching, searchQuery) {
        if (isSearching) {
            val normalizedQuery = normalizeVaultQuery(searchQuery)
            files.filter { file ->
                normalizeVaultQuery(file.displayName).contains(normalizedQuery) ||
                    normalizeVaultQuery(file.originalFileName).contains(normalizedQuery) ||
                    normalizeVaultQuery(file.notes).contains(normalizedQuery)
            }
        } else {
            val validGroupIds = groups.map { it.id }.toSet()
            if (currentGroupId == null) {
                files.filter { it.groupId == null || it.groupId !in validGroupIds }
            } else {
                files.filter { it.groupId == currentGroupId }
            }
        }
    }

    var isFabExpanded by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var groupToRename by remember { mutableStateOf<VaultGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<VaultGroup?>(null) }
    var groupForZipExport by remember { mutableStateOf<VaultGroup?>(null) }
    var selectedFileForDetails by remember { mutableStateOf<VaultFile?>(null) }

    val fabIconRotation by animateFloatAsState(
        targetValue = if (isFabExpanded) 45f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "VaultFabRotation"
    )

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
                        // Option 1: New Group
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                showAddGroupDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("fab_add_vault_group")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("گروه جدید", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Option 2: New File (SAF Multi-Document Picker)
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                importFilesLauncher.launch(arrayOf("*/*"))
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("fab_add_vault_file")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("فایل جدید", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Main Expandable FAB (+ rotates into × when expanded)
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.testTag("vault_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isFabExpanded) "بستن منو" else "افزودن",
                        modifier = Modifier.rotate(fabIconRotation)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
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
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("جستجو در صندوق...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "جستجو",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { onSearchQueryChange("") },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("vault_search_clear")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "پاک کردن جستجو",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_search_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Breadcrumbs Bar (hidden while searching)
                if (!isSearching) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_breadcrumbs_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (navigationStack.size > 1) {
                                IconButton(
                                    onClick = { navigateBack() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("vault_breadcrumbs_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "بازگشت به گروه قبل",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

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
                                            .heightIn(min = 40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = !isLast) { navigateToBreadcrumb(id) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                            .testTag("vault_breadcrumb_item_$index")
                                    ) {
                                        if (index == 0) {
                                            Icon(
                                                imageVector = Icons.Default.Home,
                                                contentDescription = null,
                                                tint = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (isImporting || isExporting) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .testTag("vault_import_progress")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isExporting) {
                                    "در حال رمزگشایی و خروجی گرفتن فایل..."
                                } else {
                                    "در حال رمزنگاری و ذخیره فایل‌ها در صندوق..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                val isEmpty = displayedGroups.isEmpty() && displayedFiles.isEmpty()

                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isSearching) {
                                            Icons.Default.Search
                                        } else if (currentGroupId != null) {
                                            Icons.Default.Folder
                                        } else {
                                            Icons.Default.Inventory2
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (isSearching) {
                                    "موردی مطابق با جستجوی شما پیدا نشد"
                                } else {
                                    "این گروه خالی است"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isSearching) {
                                    "عبارت دیگری را جستجو کنید"
                                } else {
                                    "با استفاده از دکمهٔ + گروه یا فایل جدید اضافه کنید"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.testTag("vault_items_list")
                    ) {
                        // Sub-groups section
                        if (displayedGroups.isNotEmpty()) {
                            items(displayedGroups, key = { "vault_group_${it.id}" }) { group ->
                                val subGroupCount = groups.count { it.parentGroupId == group.id }
                                val fileCount = files.count { it.groupId == group.id }
                                VaultGroupItem(
                                    group = group,
                                    subGroupCount = subGroupCount,
                                    fileCount = fileCount,
                                    onClick = { navigateToGroup(group.id) },
                                    onRenameClick = { groupToRename = group },
                                    onDeleteClick = { groupToDelete = group },
                                    onExportGroupClick = { gid ->
                                        groupForZipExport = groups.find { it.id == gid } ?: group
                                    }
                                )
                            }
                        }

                        // Files section
                        if (displayedFiles.isNotEmpty()) {
                            items(displayedFiles, key = { "vault_file_${it.id}" }) { file ->
                                VaultFileItem(
                                    file = file,
                                    onFileClick = { selectedFileForDetails = file }
                                )
                            }
                        }
                    }
                }
            }

            // Scrim overlay to dismiss expanded FAB on outside tap
            AnimatedVisibility(
                visible = isFabExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isFabExpanded = false }
                        .testTag("vault_fab_scrim")
                )
            }
        }
    }

    // Create Group Dialog
    if (showAddGroupDialog) {
        var newGroupName by remember { mutableStateOf("") }
        val siblingNames = remember(groups, currentGroupId) {
            groups.filter { it.parentGroupId == currentGroupId }
                .map { it.name.trim().lowercase() }
        }
        val isDuplicate = newGroupName.isNotBlank() &&
            siblingNames.contains(newGroupName.trim().lowercase())

        AlertDialog(
            onDismissRequest = { showAddGroupDialog = false },
            title = { Text("گروه جدید", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نام گروه جدید را وارد کنید:")
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        placeholder = { Text("مثال: اسناد ملکی، قراردادها، رسیدها") },
                        isError = isDuplicate,
                        supportingText = if (isDuplicate) {
                            { Text("گروهی با این نام در این سطح وجود دارد", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_vault_group_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newGroupName.isNotBlank() && !isDuplicate) {
                            onAddOrUpdateGroup(
                                VaultGroup(
                                    name = newGroupName.trim(),
                                    parentGroupId = currentGroupId
                                )
                            )
                            showAddGroupDialog = false
                            isFabExpanded = false
                            Toast.makeText(context, "گروه ایجاد شد", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = newGroupName.isNotBlank() && !isDuplicate,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("create_vault_group_confirm")
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
        var renameText by remember(grp.id) { mutableStateOf(grp.name) }
        val siblingNames = remember(groups, grp) {
            groups.filter { it.parentGroupId == grp.parentGroupId && it.id != grp.id }
                .map { it.name.trim().lowercase() }
        }
        val isDuplicate = renameText.isNotBlank() &&
            siblingNames.contains(renameText.trim().lowercase())

        AlertDialog(
            onDismissRequest = { groupToRename = null },
            title = { Text("تغییر نام گروه", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نام جدید گروه را وارد کنید:")
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        isError = isDuplicate,
                        supportingText = if (isDuplicate) {
                            { Text("گروهی با این نام در این سطح وجود دارد", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_vault_group_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank() && !isDuplicate) {
                            onAddOrUpdateGroup(grp.copy(name = renameText.trim()))
                            groupToRename = null
                            Toast.makeText(context, "نام گروه ویرایش شد", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = renameText.isNotBlank() && !isDuplicate,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("rename_vault_group_confirm")
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

    // Cascade Delete Group Confirmation Dialog
    groupToDelete?.let { grp ->
        val totalFilesCount = remember(grp.id, groups, files) {
            countVaultFilesRecursive(grp.id, groups, files)
        }

        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = {
                Text(
                    text = "حذف آبشاری گروه",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "آیا از حذف گروه «${grp.name}» اطمینان دارید؟ با حذف این گروه، خود گروه، همه زیرگروه‌ها در هر عمق و \u2068$totalFilesCount\u2069 فایل موجود در آن‌ها برای همیشه حذف خواهند شد."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetGroup = grp
                        groupToDelete = null

                        fun isAncestorOrSelf(ancestorId: String, nodeId: String?): Boolean {
                            val visited = mutableSetOf<String>()
                            var curr = nodeId
                            while (curr != null && visited.add(curr)) {
                                if (curr == ancestorId) return true
                                curr = groups.find { it.id == curr }?.parentGroupId
                            }
                            return false
                        }

                        if (isAncestorOrSelf(targetGroup.id, currentGroupId)) {
                            navigateToBreadcrumb(targetGroup.parentGroupId)
                        }

                        onDeleteGroupCascade(
                            targetGroup,
                            { deletedCount ->
                                Toast.makeText(
                                    context,
                                    "\u2068$deletedCount\u2069 فایل با موفقیت حذف شد",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            { errorMsg ->
                                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_cascade_delete_vault_group")
                ) {
                    Text("حذف همه")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("انصراف")
                }
            }
        )
    }

    // File Details Bottom Sheet (Rename, Notes, Move Group, Delete, Export)
    selectedFileForDetails?.let { selected ->
        val latestFile = files.find { it.id == selected.id } ?: selected
        VaultFileDetailsSheet(
            file = latestFile,
            availableGroups = groups,
            onDismiss = { selectedFileForDetails = null },
            onSave = { newName, newNotes, newGroupId ->
                onUpdateFileMetadata(latestFile.id, newName, newNotes, newGroupId)
                selectedFileForDetails = null
            },
            onDelete = { fileToDelete ->
                onDeleteFile(fileToDelete)
                selectedFileForDetails = null
            },
            onRequestExport = { fileToExport ->
                pendingFileToExport = fileToExport
                selectedFileForDetails = null
                val defaultName = fileToExport.originalFileName.ifBlank { fileToExport.displayName }
                exportDocumentLauncher.launch(defaultName)
            }
        )
    }

    // Duplicate File Prompt Dialog (Default: Skip)
    pendingDuplicateCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { onResolveDuplicate(VaultDuplicateAction.SKIP) },
            title = {
                Text(
                    text = "فایل تکراری یافت شد",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "فایل تکراری یافت شد. رد شود یا نگهداری شود؟\n\n" +
                        "فایل جدید: «${candidate.fileName}»\n" +
                        "فایل موجود در همین گروه: «${candidate.existingFile.displayName}»"
                )
            },
            confirmButton = {
                Button(
                    onClick = { onResolveDuplicate(VaultDuplicateAction.SKIP) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("duplicate_vault_file_skip")
                ) {
                    Text("رد شود")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onResolveDuplicate(VaultDuplicateAction.KEEP) },
                    modifier = Modifier.testTag("duplicate_vault_file_keep")
                ) {
                    Text("نگهداری شود")
                }
            }
        )
    }

    // Encrypted ZIP Export Password Dialog with Weak-KDF Security Warning
    groupForZipExport?.let { grp ->
        var zipPassword by remember(grp.id) { mutableStateOf("") }
        var confirmZipPassword by remember(grp.id) { mutableStateOf("") }
        var showZipPassword by remember(grp.id) { mutableStateOf(false) }
        var showConfirmZipPassword by remember(grp.id) { mutableStateOf(false) }

        val isPasswordTooShort = zipPassword.isNotEmpty() && zipPassword.length < 12
        val isConfirmMismatch = confirmZipPassword.isNotEmpty() && confirmZipPassword != zipPassword
        val isZipPasswordValid = zipPassword.length >= 12 && confirmZipPassword == zipPassword

        AlertDialog(
            onDismissRequest = { groupForZipExport = null },
            title = {
                Text(
                    text = "رمز فایل ZIP",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "هشدار امنیتی",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .size(22.dp)
                                    .padding(top = 2.dp)
                            )
                            Text(
                                text = "استاندارد AES در ZIP از PBKDF2-SHA1 با تنها ۱۰۰۰ تکرار استفاده می‌کند که بسیار ضعیف‌تر از Argon2id است. رمز باید طولانی و تصادفی باشد (حداقل ۱۶ کاراکتر پیشنهاد می‌شود). برای باز کردن این فایل در ۷-Zip یا WinRAR نیاز دارید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    OutlinedTextField(
                        value = zipPassword,
                        onValueChange = { zipPassword = it },
                        label = { Text("رمز عبور (حداقل \u206812\u2069 کاراکتر)") },
                        singleLine = true,
                        isError = isPasswordTooShort,
                        supportingText = if (isPasswordTooShort) {
                            {
                                Text(
                                    text = "رمز باید حداقل \u206812\u2069 کاراکتر باشد",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else null,
                        visualTransformation = if (showZipPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(
                                onClick = { showZipPassword = !showZipPassword },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (showZipPassword) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (showZipPassword) "مخفی کردن رمز" else "نمایش رمز"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_zip_password_input")
                    )

                    OutlinedTextField(
                        value = confirmZipPassword,
                        onValueChange = { confirmZipPassword = it },
                        label = { Text("تکرار رمز عبور") },
                        singleLine = true,
                        isError = isConfirmMismatch,
                        supportingText = if (isConfirmMismatch) {
                            {
                                Text(
                                    text = "رمز عبور و تکرار آن یکسان نیستند",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else null,
                        visualTransformation = if (showConfirmZipPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(
                                onClick = { showConfirmZipPassword = !showConfirmZipPassword },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (showConfirmZipPassword) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (showConfirmZipPassword) "مخفی کردن رمز" else "نمایش رمز"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_zip_confirm_password_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isZipPasswordValid) {
                            pendingZipExportRequest = grp to zipPassword
                            groupForZipExport = null
                            val zipTitle = "${VaultZipExporter.sanitizeFileName(grp.name)}.zip"
                            exportGroupZipLauncher.launch(zipTitle)
                        }
                    },
                    enabled = isZipPasswordValid,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_vault_zip_export")
                ) {
                    Text("ادامه و خروجی")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { groupForZipExport = null },
                    modifier = Modifier.testTag("cancel_vault_zip_export")
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}

internal fun formatVaultImportReportToast(report: VaultImportReport): String {
    val parts = mutableListOf<String>()
    if (report.importedCount > 0) {
        parts.add("\u2068${report.importedCount}\u2069 فایل موفق")
    }
    if (report.duplicateSkippedCount > 0) {
        parts.add("\u2068${report.duplicateSkippedCount}\u2069 تکراری ردشده")
    }
    if (report.rejectedCount > 0) {
        parts.add("\u2068${report.rejectedCount}\u2069 ردشده")
    }
    if (parts.isEmpty()) {
        return "فایلی به صندوق اضافه نشد"
    }
    val summary = "نتیجه وارد کردن: ${parts.joinToString("، ")}"
    val firstReason = report.messages.firstOrNull()
    return if (report.rejectedCount > 0 && !firstReason.isNullOrBlank()) {
        "$summary ($firstReason)"
    } else {
        summary
    }
}

internal fun countVaultFilesRecursive(
    rootGroupId: String,
    groups: List<VaultGroup>,
    files: List<VaultFile>
): Int {
    val visited = mutableSetOf<String>()
    fun collectSubtreeIds(groupId: String) {
        if (!visited.add(groupId)) return
        val children = groups.filter { it.parentGroupId == groupId }
        for (child in children) {
            collectSubtreeIds(child.id)
        }
    }
    collectSubtreeIds(rootGroupId)
    return files.count { it.groupId in visited }
}

private fun normalizeVaultQuery(text: String): String {
    if (text.isBlank()) return ""
    return text.trim()
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ۀ', 'ه')
        .replace('ة', 'ه')
        .lowercase()
}

internal fun resolveExportedUriDisplayPath(context: Context, uri: Uri): String {
    val queriedName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
            } else {
                null
            }
        }
    }.getOrNull()

    if (!queriedName.isNullOrBlank()) return queriedName

    val segment = uri.lastPathSegment?.substringAfter(':')?.takeIf { it.isNotBlank() }
    if (!segment.isNullOrBlank()) return segment

    return uri.path?.takeIf { it.isNotBlank() } ?: uri.toString()
}
