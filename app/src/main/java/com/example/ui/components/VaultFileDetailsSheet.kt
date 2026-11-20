package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.ClipboardAutoClearManager
import com.example.util.JalaliCalendarHelper
import java.util.Calendar
import java.util.Locale

private const val MAX_DISPLAY_NAME_LENGTH = 120
private const val MAX_NOTES_LENGTH = 500

data class VaultGroupHierarchyItem(
    val group: VaultGroup,
    val depth: Int,
    val fullPath: String
)

internal fun buildVaultGroupHierarchy(groups: List<VaultGroup>): List<VaultGroupHierarchyItem> {
    val result = mutableListOf<VaultGroupHierarchyItem>()
    val groupMap = groups.associateBy { it.id }

    fun computeFullPath(group: VaultGroup): String {
        val names = mutableListOf(group.name)
        val visited = mutableSetOf(group.id)
        var curr = group.parentGroupId
        while (curr != null && visited.add(curr)) {
            val parent = groupMap[curr] ?: break
            names.add(0, parent.name)
            curr = parent.parentGroupId
        }
        return names.joinToString(" / ")
    }

    val visitedNodes = mutableSetOf<String>()
    fun addChildren(parentId: String?, depth: Int) {
        val children = groups.filter { it.parentGroupId == parentId }
            .sortedBy { it.name.lowercase() }
        for (child in children) {
            if (!visitedNodes.add(child.id)) continue
            result.add(
                VaultGroupHierarchyItem(
                    group = child,
                    depth = depth,
                    fullPath = computeFullPath(child)
                )
            )
            addChildren(child.id, depth + 1)
        }
    }

    addChildren(null, 0)

    val addedIds = result.map { it.group.id }.toSet()
    val unadded = groups.filter { it.id !in addedIds }.sortedBy { it.name.lowercase() }
    for (orphan in unadded) {
        result.add(
            VaultGroupHierarchyItem(
                group = orphan,
                depth = 0,
                fullPath = computeFullPath(orphan)
            )
        )
    }

    return result
}

internal fun formatVaultTimestampJalali(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val jDate = JalaliCalendarHelper.gregorianToJalali(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    return String.format(
        Locale.US,
        "%04d/%02d/%02d • %02d:%02d",
        jDate.year,
        jDate.month,
        jDate.day,
        hour,
        minute
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultFileDetailsSheet(
    file: VaultFile,
    availableGroups: List<VaultGroup>,
    onDismiss: () -> Unit,
    onSave: (newDisplayName: String, newNotes: String, newGroupId: String?) -> Unit,
    onDelete: (VaultFile) -> Unit,
    onRequestExport: (VaultFile) -> Unit = {}
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initialGroupId = remember(file.id, file.groupId, availableGroups) {
        val rawId = file.groupId
        if (rawId != null && availableGroups.none { it.id == rawId }) null else rawId
    }

    var displayName by remember(file.id) {
        mutableStateOf(file.displayName.take(MAX_DISPLAY_NAME_LENGTH))
    }
    var notes by remember(file.id) {
        mutableStateOf(file.notes.take(MAX_NOTES_LENGTH))
    }
    var selectedGroupId by remember(file.id, initialGroupId) {
        mutableStateOf(initialGroupId)
    }
    var isGroupDropdownExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showExportWarningDialog by remember { mutableStateOf(false) }

    val hierarchicalGroups = remember(availableGroups) {
        buildVaultGroupHierarchy(availableGroups)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = VazirmatnFontFamily)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Bar: Sheet Title (Start/Right in RTL) + Export & Delete Actions (End/Top-Left in RTL)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "جزئیات فایل",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showExportWarningDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("vault_file_export_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("خروجی فایل", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        TextButton(
                            onClick = { showDeleteConfirmDialog = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("vault_file_delete_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("حذف فایل", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                // Header Summary Card: Large Icon, Name, Size, Jalali Date, SHA-256
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = iconForVaultMimeType(file.mimeType),
                                        contentDescription = "آیکون نوع فایل",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatVaultFileSize(file.sizeBytes)}  •  \u2068${formatVaultTimestampJalali(file.createdAt)}\u2069",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (file.originalFileName.isNotBlank() &&
                                    file.originalFileName.trim() != file.displayName.trim()
                                ) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "نام اصلی: ${file.originalFileName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Copyable Single-Line Monospace SHA-256 Box
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", file.sha256))
                                    ClipboardAutoClearManager.onCopied(context, file.sha256)
                                    Toast.makeText(context, "هش SHA-256 کپی شد", Toast.LENGTH_SHORT).show()
                                }
                                .testTag("copy_vault_file_sha256")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SHA-256:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = file.sha256,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "کپی هش SHA-256",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Display Name Input
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= MAX_DISPLAY_NAME_LENGTH) displayName = it },
                    label = { Text("نام نمایشی", style = MaterialTheme.typography.bodyMedium) },
                    isError = displayName.isBlank(),
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (displayName.isBlank()) "نام نمایشی نمی‌تواند خالی باشد" else "",
                                color = MaterialTheme.colorScheme.error
                            )
                            Text("\u2068${displayName.length}/$MAX_DISPLAY_NAME_LENGTH\u2069")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_file_display_name_input")
                )

                // Notes Multi-line Input
                OutlinedTextField(
                    value = notes,
                    onValueChange = { if (it.length <= MAX_NOTES_LENGTH) notes = it },
                    label = { Text("یادداشت", style = MaterialTheme.typography.bodyMedium) },
                    placeholder = { Text("توضیحات اختیاری درباره این فایل...") },
                    minLines = 3,
                    maxLines = 5,
                    supportingText = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text("\u2068${notes.length}/$MAX_NOTES_LENGTH\u2069")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_file_notes_input")
                )

                // Group Selector (Move File Between Groups)
                ExposedDropdownMenuBox(
                    expanded = isGroupDropdownExpanded,
                    onExpandedChange = { isGroupDropdownExpanded = !isGroupDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val selectedItem = hierarchicalGroups.find { it.group.id == selectedGroupId }
                    val selectedGroupLabel = selectedItem?.fullPath ?: "بدون گروه (ریشه)"

                    OutlinedTextField(
                        value = selectedGroupLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("گروه", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = isGroupDropdownExpanded)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .testTag("vault_file_group_selector")
                    )

                    ExposedDropdownMenu(
                        expanded = isGroupDropdownExpanded,
                        onDismissRequest = { isGroupDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "بدون گروه (ریشه)",
                                    fontWeight = if (selectedGroupId == null) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedGroupId == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (selectedGroupId == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                selectedGroupId = null
                                isGroupDropdownExpanded = false
                            },
                            modifier = Modifier.testTag("vault_file_group_option_root")
                        )

                        hierarchicalGroups.forEach { item ->
                            val isSelected = selectedGroupId == item.group.id
                            val indent = "    ".repeat(item.depth)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (item.depth > 0) "$indent${item.fullPath}" else item.group.name,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    selectedGroupId = item.group.id
                                    isGroupDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("vault_file_group_option_${item.group.id}")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (displayName.isNotBlank()) {
                                onSave(displayName.trim(), notes.trim(), selectedGroupId)
                            }
                        },
                        enabled = displayName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("vault_file_save_button")
                    ) {
                        Text("ذخیره تغییرات", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("vault_file_cancel_button")
                    ) {
                        Text("انصراف", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = "حذف فایل",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "آیا از حذف فایل «${file.displayName}» اطمینان دارید؟ این فایل رمزنگاری‌شده برای همیشه از صندوق و حافظه دستگاه پاک خواهد شد."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete(file)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_delete_vault_file")
                ) {
                    Text("حذف فایل")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showExportWarningDialog) {
        AlertDialog(
            onDismissRequest = { showExportWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "هشدار امنیتی",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "هشدار امنیتی خروجی فایل",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "• فایل خروجی رمزنگاری‌شده نیست و به‌صورت خام نوشته می‌شود.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• هر کسی یا هر برنامه‌ای که به این فایل دسترسی داشته باشد می‌تواند محتوای آن را ببیند یا استفاده کند (مخصوصاً مهم برای keyfileها).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• پس از خروجی گرفتن، فایل را در جای امن نگهداری کنید.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportWarningDialog = false
                        onRequestExport(file)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_export_vault_file")
                ) {
                    Text("ادامه و خروجی", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportWarningDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}
