package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentGroup
import com.example.data.model.IdentityDocument
import com.example.ui.components.AddEditDocumentDialog
import com.example.ui.components.DocumentCardItem
import com.example.ui.components.DocumentGroupItem

@Composable
fun DocumentsScreen(
    documents: List<IdentityDocument>,
    groups: List<DocumentGroup> = emptyList(),
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDeleteDocument: (IdentityDocument) -> Unit,
    onSaveDocument: (IdentityDocument) -> Unit,
    onAddOrUpdateGroup: (DocumentGroup) -> Unit = {},
    onDeleteGroupCascade: (DocumentGroup, onSuccess: (Int) -> Unit, onError: (String) -> Unit) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Navigation Stack for nested groups
    var navigationStack by remember { mutableStateOf(listOf<String?>(null)) }
    val currentGroupId = navigationStack.lastOrNull()

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
            var c = targetId
            while (c != null) {
                chain.add(0, c)
                c = groups.find { it.id == c }?.parentGroupId
            }
            navigationStack = listOf<String?>(null) + chain
        }
    }

    // Clean up navigation stack if groups are deleted
    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        val filtered = navigationStack.filter { it == null || it in validGroupIds }
        if (filtered.isEmpty()) {
            navigationStack = listOf(null)
        } else if (filtered != navigationStack) {
            navigationStack = filtered
        }
    }

    // BackHandler: Handle phone back button for subgroups or active search
    BackHandler(enabled = navigationStack.size > 1 || searchQuery.isNotBlank()) {
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
        var curr = currentGroupId
        val stack = mutableListOf<Pair<String?, String>>()
        while (curr != null) {
            val grp = groups.find { it.id == curr }
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

    val isSearching = searchQuery.isNotBlank()

    // Sub-groups in current level
    val displayedGroups = remember(currentGroupId, groups, isSearching) {
        if (isSearching) {
            emptyList()
        } else {
            groups.filter { it.parentGroupId == currentGroupId }
                .sortedBy { it.name.lowercase() }
        }
    }

    // Documents in current level (including orphan documents at root)
    val displayedDocuments = remember(currentGroupId, documents, groups, isSearching) {
        if (isSearching) {
            documents
        } else {
            val validGroupIds = groups.map { it.id }.toSet()
            if (currentGroupId == null) {
                // Root: documents with null groupId OR orphan documents whose group no longer exists
                documents.filter { it.groupId == null || it.groupId !in validGroupIds }
            } else {
                // Sub-group: documents belonging to this group
                documents.filter { it.groupId == currentGroupId }
            }
        }
    }

    var isFabExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingDoc by remember { mutableStateOf<IdentityDocument?>(null) }
    var groupToRename by remember { mutableStateOf<DocumentGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<DocumentGroup?>(null) }

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
                        // Option 1: Add Document Group
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                showAddGroupDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.testTag("fab_add_document_group")
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

                        // Option 2: Add Document
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                editingDoc = null
                                showAddDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("fab_add_document")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Badge, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("مدرک جدید", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Main Floating Action Button
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    shape = CircleShape,
                    modifier = Modifier.testTag("documents_fab")
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (isFabExpanded) "بستن منو" else "افزودن"
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
                    placeholder = { Text("جستجو در تمامی مدارک...") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "پاک کردن",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

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
                            if (navigationStack.size > 1) {
                                IconButton(
                                    onClick = { navigateBack() },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("breadcrumbs_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "بازگشت به گروه قبل",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
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
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = !isLast) { navigateToBreadcrumb(id) }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        if (index == 0) {
                                            Icon(
                                                imageVector = Icons.Default.Home,
                                                contentDescription = null,
                                                tint = if (isLast) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isLast) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
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

                val isEmpty = displayedGroups.isEmpty() && displayedDocuments.isEmpty()

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
                                        imageVector = if (isSearching) {
                                            Icons.Default.Search
                                        } else if (currentGroupId != null) {
                                            Icons.Default.Folder
                                        } else {
                                            Icons.Default.Badge
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (isSearching) {
                                    "موردی مطابق با جستجوی شما پیدا نشد"
                                } else if (currentGroupId != null) {
                                    "این گروه خالی است"
                                } else {
                                    "هیچ مدرک شناسایی ثبت نشده است"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isSearching) {
                                    "عبارت دیگری را جستجو کنید"
                                } else if (currentGroupId != null) {
                                    "با استفاده از دکمهٔ + اولین مدرک یا زیرگروه خود را اضافه کنید"
                                } else {
                                    "مدارک شناسایی نظیر کارت ملی، شناسنامه و گواهینامه خود را به صورت امن ذخیره کنید"
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Sub-groups section
                        if (displayedGroups.isNotEmpty()) {
                            items(displayedGroups, key = { "group_${it.id}" }) { group ->
                                val subGroupCount = groups.count { it.parentGroupId == group.id }
                                val documentCount = documents.count { it.groupId == group.id }
                                DocumentGroupItem(
                                    group = group,
                                    subGroupCount = subGroupCount,
                                    documentCount = documentCount,
                                    onClick = { navigateToGroup(group.id) },
                                    onRenameClick = { groupToRename = group },
                                    onDeleteClick = { groupToDelete = group }
                                )
                            }
                        }

                        // Documents section
                        if (displayedDocuments.isNotEmpty()) {
                            items(displayedDocuments, key = { "doc_${it.id}" }) { doc ->
                                DocumentCardItem(
                                    doc = doc,
                                    onEditClick = {
                                        editingDoc = doc
                                        showAddDialog = true
                                    },
                                    onDeleteClick = { onDeleteDocument(doc) }
                                )
                            }
                        }
                    }
                }
            }

            // Click outside to dismiss expanded FAB
            if (isFabExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isFabExpanded = false }
                )
            }
        }
    }

    // Add / Edit Document Dialog
    if (showAddDialog) {
        AddEditDocumentDialog(
            initialDocument = editingDoc,
            availableGroups = groups,
            defaultGroupId = currentGroupId,
            onDismiss = {
                showAddDialog = false
                editingDoc = null
            },
            onSave = { updatedDoc ->
                onSaveDocument(updatedDoc)
                showAddDialog = false
                editingDoc = null
                Toast.makeText(context, "مدرک با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Create Document Group Dialog
    if (showAddGroupDialog) {
        var newGroupName by remember { mutableStateOf("") }
        val siblingNames = remember(groups, currentGroupId) {
            groups.filter { it.parentGroupId == currentGroupId }.map { it.name.trim().lowercase() }
        }
        val isDuplicate = newGroupName.isNotBlank() && siblingNames.contains(newGroupName.trim().lowercase())

        AlertDialog(
            onDismissRequest = { showAddGroupDialog = false },
            title = { Text("گروه جدید", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نام گروه یا دسته‌بندی مدارک را وارد کنید:")
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        placeholder = { Text("مثال: مدارک شغلی، بیمه، خودرو") },
                        isError = isDuplicate,
                        supportingText = if (isDuplicate) {
                            { Text("گروهی با این نام در این سطح وجود دارد", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_document_group_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newGroupName.isNotBlank() && !isDuplicate) {
                            onAddOrUpdateGroup(
                                DocumentGroup(
                                    name = newGroupName.trim(),
                                    parentGroupId = currentGroupId
                                )
                            )
                            showAddGroupDialog = false
                            Toast.makeText(context, "گروه ایجاد شد", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = newGroupName.isNotBlank() && !isDuplicate,
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

    // Rename Document Group Dialog
    groupToRename?.let { grp ->
        var renameText by remember { mutableStateOf(grp.name) }
        val siblingNames = remember(groups, grp) {
            groups.filter { it.parentGroupId == grp.parentGroupId && it.id != grp.id }
                .map { it.name.trim().lowercase() }
        }
        val isDuplicate = renameText.isNotBlank() && siblingNames.contains(renameText.trim().lowercase())

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
                            .testTag("rename_document_group_input")
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

    // Cascade Delete Document Group Confirmation Dialog
    groupToDelete?.let { grp ->
        val totalDocumentsCount = remember(grp.id, groups, documents) {
            fun getSubgroupAndSelfIds(id: String): Set<String> {
                val result = mutableSetOf(id)
                val children = groups.filter { it.parentGroupId == id }
                for (child in children) {
                    result.addAll(getSubgroupAndSelfIds(child.id))
                }
                return result
            }
            val allAffectedGroupIds = getSubgroupAndSelfIds(grp.id)
            documents.count { it.groupId in allAffectedGroupIds }
        }

        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = {
                Text(
                    "حذف آبشاری گروه",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    "آیا از حذف گروه «${grp.name}» اطمینان دارید؟ با حذف این گروه، تمامی زیرگروه‌ها و $totalDocumentsCount مدرک موجود در آن‌ها برای همیشه حذف خواهند شد."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetGroup = grp
                        groupToDelete = null

                        fun isAncestorOrSelf(ancestorId: String, currentId: String?): Boolean {
                            var curr = currentId
                            while (curr != null) {
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
                                    "$deletedCount مدرک با موفقیت حذف شدند",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            { errorMsg ->
                                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
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
}
