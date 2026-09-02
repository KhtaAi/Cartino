package com.example.ui.components

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
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
import com.example.ui.theme.EmeraldStatus
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.VazirmatnFontFamily
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
    onDismiss: () -> Unit,
    onSave: (PasswordEntry) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember { mutableStateOf(initialEntry?.title ?: "") }
    var selectedGroupId by remember { mutableStateOf(initialEntry?.groupId ?: groupId) }
    var username by remember { mutableStateOf(initialEntry?.username ?: "") }
    var password by remember { mutableStateOf(initialEntry?.password ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var website by remember { mutableStateOf(initialEntry?.website ?: "") }
    var notes by remember { mutableStateOf(initialEntry?.notes ?: "") }
    var isGroupDropdownExpanded by remember { mutableStateOf(false) }

    val customFieldsList: SnapshotStateList<PasswordCustomField> = remember {
        mutableStateListOf<PasswordCustomField>().apply {
            initialEntry?.getCustomFields()?.let { addAll(it) }
        }
    }

    val passwordStrength = remember(password) { calculatePasswordStrength(password) }

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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Title
                Text(
                    text = if (initialEntry != null) "ویرایش پسورد" else "افزودن پسورد جدید",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

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
                                IconButton(
                                    onClick = { password = generateQuickPassword() },
                                    modifier = Modifier.size(48.dp)
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
                        onClick = onDismiss,
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

private fun isSensitiveLabel(label: String): Boolean {
    if (label.isBlank()) return false
    val lower = label.lowercase()
    val sensitiveKeywords = listOf("password", "pass", "secret", "pin", "token", "cvv", "otp", "رمز", "پسورد", "کلید")
    return sensitiveKeywords.any { lower.contains(it) }
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
