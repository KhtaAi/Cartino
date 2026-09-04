package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PasswordCustomField
import com.example.data.model.PasswordEntry
import com.example.data.model.PasswordGroup
import com.example.ui.theme.CustomCyanBlue
import com.example.ui.theme.EmeraldStatus
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.ClipboardAutoClearManager
import java.security.SecureRandom

private enum class PasswordStrength(val labelFa: String, val color: Color, val progress: Float) {
    WEAK("ضعیف", Color(0xFFEF4444), 0.33f),
    MEDIUM("متوسط", Color(0xFFF59E0B), 0.66f),
    STRONG("قوی", Color(0xFF10B981), 1f)
}

private fun calculatePasswordStrength(password: String): PasswordStrength {
    if (password.length < 8) return PasswordStrength.WEAK
    var score = 0
    if (password.any { it.isUpperCase() }) score++
    if (password.any { it.isLowerCase() }) score++
    if (password.any { it.isDigit() }) score++
    if (password.any { !it.isLetterOrDigit() }) score++

    return when {
        password.length >= 12 && score >= 3 -> PasswordStrength.STRONG
        password.length >= 8 && score >= 2 -> PasswordStrength.MEDIUM
        else -> PasswordStrength.WEAK
    }
}

private fun generateQuickPassword(length: Int = 16): String {
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val lower = "abcdefghijkmnopqrstuvwxyz"
    val digits = "23456789"
    val symbols = "!@#$%^&*()-_=+"
    val allChars = upper + lower + digits + symbols
    val random = SecureRandom()

    val chars = mutableListOf(
        upper[random.nextInt(upper.length)],
        lower[random.nextInt(lower.length)],
        digits[random.nextInt(digits.length)],
        symbols[random.nextInt(symbols.length)]
    )
    for (i in 4 until length) {
        chars.add(allChars[random.nextInt(allChars.length)])
    }
    chars.shuffle(random)
    return chars.joinToString("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditPasswordDialog(
    initialEntry: PasswordEntry? = null,
    groupId: String? = null,
    availableGroups: List<PasswordGroup> = emptyList(),
    isLoading: Boolean = false,
    initialIsReadOnly: Boolean = (initialEntry != null),
    onDismiss: () -> Unit,
    onSave: (PasswordEntry) -> Unit,
    onDelete: ((PasswordEntry) -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var isReadOnly by remember(initialEntry, initialIsReadOnly) {
        mutableStateOf(if (initialEntry == null) false else initialIsReadOnly)
    }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var title by remember(initialEntry) { mutableStateOf(initialEntry?.title ?: "") }
    var selectedGroupId by remember(initialEntry, groupId) { mutableStateOf(initialEntry?.groupId ?: groupId) }
    var username by remember(initialEntry) { mutableStateOf(initialEntry?.username ?: "") }
    var password by remember(initialEntry) { mutableStateOf(initialEntry?.password ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var website by remember(initialEntry) { mutableStateOf(initialEntry?.website ?: "") }
    var notes by remember(initialEntry) { mutableStateOf(initialEntry?.notes ?: "") }
    var isGroupDropdownExpanded by remember { mutableStateOf(false) }
    var qrCodeDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }

    val customFieldsList: SnapshotStateList<PasswordCustomField> = remember(initialEntry) {
        mutableStateListOf<PasswordCustomField>().apply {
            initialEntry?.getCustomFields()?.let { addAll(it) }
        }
    }

    val passwordStrength = remember(password) { calculatePasswordStrength(password) }

    fun copyToClipboard(label: String, text: String, toastMsg: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        ClipboardAutoClearManager.onCopied(context, text)
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    fun openWebsite(url: String) {
        if (url.isBlank()) return
        try {
            val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "امکان باز کردن نشانی وب وجود ندارد", Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = VazirmatnFontFamily)) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = GoldPrimary,
                        strokeWidth = 3.dp
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header
                    val headerTitle = when {
                        isReadOnly -> initialEntry?.title?.ifBlank { "جزئیات پسورد" } ?: "جزئیات پسورد"
                        initialEntry != null -> "ویرایش پسورد"
                        else -> "افزودن پسورد جدید"
                    }
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isReadOnly) {
                        // ==================== READ-ONLY MODE ====================
                        // Title
                        OutlinedTextField(
                            value = title,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("عنوان یا سرویس") },
                            leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = GoldPrimary) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_title_readonly")
                        )

                        // Group indicator (if any)
                        val currentGroupName = availableGroups.find { it.id == selectedGroupId }?.name
                        if (!currentGroupName.isNullOrBlank()) {
                            OutlinedTextField(
                                value = currentGroupName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("دسته‌بندی / گروه") },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Username / Email (Read-only with Copy & QR)
                        OutlinedTextField(
                            value = username,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("نام کاربری یا ایمیل") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            trailingIcon = if (username.isNotBlank()) {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { copyToClipboard("Username", username, "نام کاربری کپی شد") },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("copy_username_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "کپی نام کاربری",
                                                tint = EmeraldStatus,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { qrCodeDialogData = Pair("نام کاربری ($title)", username) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("qr_username_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.QrCode2,
                                                contentDescription = "کد QR نام کاربری",
                                                tint = GoldPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_username_readonly")
                        )

                        // Password (Read-only with Mask, Visibility Toggle, Copy & QR)
                        OutlinedTextField(
                            value = password,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("کلمه عبور (پسورد)") },
                            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { isPasswordVisible = !isPasswordVisible },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = if (isPasswordVisible) "پنهان کردن" else "نمایش"
                                        )
                                    }
                                    if (password.isNotBlank()) {
                                        IconButton(
                                            onClick = { copyToClipboard("Password", password, "پسورد کپی شد") },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("copy_password_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "کپی پسورد",
                                                tint = EmeraldStatus,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { qrCodeDialogData = Pair("کلمه عبور ($title)", password) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("qr_password_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.QrCode2,
                                                contentDescription = "کد QR پسورد",
                                                tint = GoldPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_secret_readonly")
                        )

                        // Website (Read-only with Open in Browser)
                        if (website.isNotBlank()) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = website,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("نشانی وب‌سایت") },
                                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { openWebsite(website) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("open_website_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInBrowser,
                                                contentDescription = "بازکردن در مرورگر",
                                                tint = CustomCyanBlue,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("password_website_readonly")
                                )
                            }
                        }

                        // Notes (Read-only)
                        if (notes.isNotBlank()) {
                            OutlinedTextField(
                                value = notes,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("یادداشت") },
                                leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
                                maxLines = 6,
                                minLines = 2,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("password_notes_readonly")
                            )
                        }

                        // Custom Fields in Read-only
                        if (customFieldsList.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "فیلدهای اختصاصی (${customFieldsList.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                customFieldsList.forEach { field ->
                                    ReadOnlyCustomFieldRow(
                                        field = field,
                                        onCopy = { copyToClipboard(field.label, field.value, "«${field.label}» کپی شد") },
                                        onShowQr = { qrCodeDialogData = Pair("${field.label} ($title)", field.value) }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Action Buttons in Read-Only Mode (Edit, Delete, Close)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { isReadOnly = false },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(48.dp)
                                    .testTag("edit_password_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ویرایش", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            OutlinedButton(
                                onClick = { showDeleteConfirmDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("delete_password_button")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("حذف", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            OutlinedButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("بستن", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    } else {
                        // ==================== EDITING MODE ====================
                        // Title Field (Required)
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("عنوان یا سرویس *") },
                            placeholder = { Text("مثال: حساب گوگل، دیجی‌کالا، گیت‌هاب") },
                            leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = GoldPrimary) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_title_input")
                        )

                        // Group Selector
                        if (availableGroups.isNotEmpty()) {
                            ExposedDropdownMenuBox(
                                expanded = isGroupDropdownExpanded,
                                onExpandedChange = { isGroupDropdownExpanded = it }
                            ) {
                                val currentGroupName = availableGroups.find { it.id == selectedGroupId }?.name ?: "بدون گروه (ریشه)"
                                OutlinedTextField(
                                    value = currentGroupName,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("دسته‌بندی / گروه") },
                                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isGroupDropdownExpanded) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = isGroupDropdownExpanded,
                                    onDismissRequest = { isGroupDropdownExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("بدون گروه (ریشه)") },
                                        onClick = {
                                            selectedGroupId = null
                                            isGroupDropdownExpanded = false
                                        }
                                    )
                                    availableGroups.forEach { grp ->
                                        DropdownMenuItem(
                                            text = { Text(grp.name) },
                                            onClick = {
                                                selectedGroupId = grp.id
                                                isGroupDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Username / Email
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("نام کاربری یا ایمیل") },
                            placeholder = { Text("user@example.com") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            trailingIcon = if (username.isNotBlank()) {
                                {
                                    IconButton(
                                        onClick = { qrCodeDialogData = Pair("نام کاربری ($title)", username) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("qr_username_input_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QrCode2,
                                            contentDescription = "کد QR نام کاربری",
                                            tint = GoldPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_username_input")
                        )

                        // Password Field with Visibility Toggle & Quick Generate
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("کلمه عبور (پسورد)") },
                                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (password.isNotBlank()) {
                                            IconButton(
                                                onClick = { qrCodeDialogData = Pair("کلمه عبور ($title)", password) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("qr_password_input_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.QrCode2,
                                                    contentDescription = "کد QR پسورد",
                                                    tint = GoldPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { password = generateQuickPassword() },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("generate_secure_password_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = "تولید پسورد امن",
                                                tint = GoldPrimary
                                            )
                                        }
                                        IconButton(
                                            onClick = { isPasswordVisible = !isPasswordVisible },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = if (isPasswordVisible) "پنهان کردن" else "نمایش"
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("password_secret_input")
                            )

                            // Strength Indicator (if password entered)
                            if (password.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "قدرت پسورد: ${passwordStrength.labelFa}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = passwordStrength.color
                                    )
                                    LinearProgressIndicator(
                                        progress = { passwordStrength.progress },
                                        color = passwordStrength.color,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .width(100.dp)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }

                        // Website Field (LTR)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = website,
                                onValueChange = { website = it },
                                label = { Text("نشانی وب‌سایت (اختیاری)") },
                                placeholder = { Text("https://example.com") },
                                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("password_website_input")
                            )
                        }

                        // Notes Field
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("یادداشت (اختیاری)") },
                            placeholder = { Text("اطلاعات تکمیلی یا سوالات امنیتی...") },
                            leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
                            maxLines = 4,
                            minLines = 2,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_notes_input")
                        )

                        // Custom Fields Section
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "فیلدهای اختصاصی (${customFieldsList.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = {
                                        customFieldsList.add(
                                            PasswordCustomField(label = "", value = "", isHidden = false)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("افزودن فیلد", fontSize = 12.sp)
                                }
                            }

                            customFieldsList.forEachIndexed { index, field ->
                                PasswordCustomFieldEditor(
                                    index = index,
                                    field = field,
                                    onUpdate = { updated -> customFieldsList[index] = updated },
                                    onDelete = { customFieldsList.removeAt(index) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Action Buttons (Save & Cancel)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val validCustomFields = customFieldsList
                                        .filter { it.label.isNotBlank() || it.value.isNotBlank() }
                                    val encodedCustom = PasswordEntry.encodeCustomFields(validCustomFields)

                                    val entryToSave = (initialEntry ?: PasswordEntry(
                                        groupId = selectedGroupId,
                                        title = title.trim()
                                    )).copy(
                                        groupId = selectedGroupId,
                                        title = title.trim(),
                                        username = username.trim(),
                                        password = password,
                                        website = website.trim(),
                                        notes = notes.trim(),
                                        customFieldsJson = encodedCustom,
                                        updatedAt = System.currentTimeMillis()
                                    )

                                    onSave(entryToSave)
                                },
                                enabled = title.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("save_password_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("ذخیره پسورد", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }

                            Button(
                                onClick = {
                                    if (initialEntry != null) {
                                        isReadOnly = true
                                    } else {
                                        onDismiss()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                            ) {
                                Text("انصراف", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog for Read-Only mode
    if (showDeleteConfirmDialog && initialEntry != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = "حذف پسورد",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = VazirmatnFontFamily
                )
            },
            text = {
                Text(
                    text = "آیا از حذف پسورد «${initialEntry.title.ifBlank { title }}» اطمینان دارید؟",
                    fontFamily = VazirmatnFontFamily,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete?.invoke(initialEntry)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حذف", fontFamily = VazirmatnFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("انصراف", fontFamily = VazirmatnFontFamily)
                }
            }
        )
    }
    // QR Code Dialog
    qrCodeDialogData?.let { (label, value) ->
        PasswordQrCodeDialog(
            fieldLabel = label,
            secretValue = value,
            onDismiss = { qrCodeDialogData = null }
        )
    }
}

private fun isSensitiveLabel(label: String): Boolean {
    if (label.isBlank()) return false
    val lower = label.lowercase()
    val sensitiveKeywords = listOf("password", "pass", "secret", "pin", "token", "cvv", "otp", "رمز", "پسورد", "کلید")
    return sensitiveKeywords.any { lower.contains(it) }
}

@Composable
private fun ReadOnlyCustomFieldRow(
    field: PasswordCustomField,
    onCopy: () -> Unit,
    onShowQr: (() -> Unit)? = null
) {
    var isVisible by remember { mutableStateOf(!field.isHidden) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.label.ifBlank { "فیلد اختصاصی" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = if (isVisible) field.value else "••••••••",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (field.isHidden) {
                    IconButton(
                        onClick = { isVisible = !isVisible },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (isVisible) "پنهان کردن" else "نمایش",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (field.value.isNotBlank()) {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "کپی",
                            tint = EmeraldStatus,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (onShowQr != null && field.value.isNotBlank()) {
                    IconButton(
                        onClick = onShowQr,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = "کد QR",
                            tint = GoldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordCustomFieldEditor(
    index: Int,
    field: PasswordCustomField,
    onUpdate: (PasswordCustomField) -> Unit,
    onDelete: () -> Unit
) {
    var isPasswordVisible by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (field.label.isNotBlank()) "فیلد: ${field.label}" else "فیلد شماره ${index + 1}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "حذف فیلد",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Normal vs Hidden segmented button
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !field.isHidden,
                    onClick = { onUpdate(field.copy(isHidden = false)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {}
                ) {
                    Text("عادی", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                SegmentedButton(
                    selected = field.isHidden,
                    onClick = { onUpdate(field.copy(isHidden = true)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {}
                ) {
                    Text("مخفی (مانند رمز)", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            OutlinedTextField(
                value = field.label,
                onValueChange = { newLabel ->
                    val shouldHide = isSensitiveLabel(newLabel)
                    val updatedHidden = if (shouldHide) true else field.isHidden
                    onUpdate(field.copy(label = newLabel, isHidden = updatedHidden))
                },
                label = { Text("عنوان فیلد") },
                placeholder = { Text("مثال: پین ۴ رقمی، سوال امنیتی") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = field.value,
                onValueChange = { newValue -> onUpdate(field.copy(value = newValue)) },
                label = { Text("مقدار فیلد") },
                singleLine = true,
                visualTransformation = if (field.isHidden && !isPasswordVisible) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    if (field.isHidden) {
                        IconButton(
                            onClick = { isPasswordVisible = !isPasswordVisible },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (isPasswordVisible) "پنهان کردن" else "نمایش"
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

