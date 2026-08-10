package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.CartinoViewModel
import com.example.util.SecurityManager
import com.example.util.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private sealed interface UpdateCheckState {
    object Idle : UpdateCheckState
    object Checking : UpdateCheckState
    data class Available(val latestTag: String, val releaseUrl: String) : UpdateCheckState
    data class UpToDate(val installedVersion: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

@Composable
fun SecuritySettingsScreen(
    viewModel: CartinoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val isFlagSecureEnabled by viewModel.isFlagSecureEnabled.collectAsState()
    val isClipboardAutoClearEnabled by viewModel.clipboardAutoClearEnabled.collectAsState()
    val clipboardAutoClearSeconds by viewModel.clipboardAutoClearSeconds.collectAsState()
    val isTotpEnabled by viewModel.isTotpEnabled.collectAsState()
    val appThemeMode by viewModel.appThemeMode.collectAsState()
    val appAccentPalette by viewModel.appAccentPalette.collectAsState()
    val masterPassword by viewModel.masterEncryptionPassword.collectAsState()

    val isBiometricHardwareAvailable = remember(context) { SecurityManager.isBiometricAvailable(context) }

    val versionName = remember(context) {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.7"
        } catch (e: Exception) {
            "1.0.7"
        }
    }

    var isGroup1Expanded by remember { mutableStateOf(false) }
    var isGroup2Expanded by remember { mutableStateOf(false) }
    var isGroup3Expanded by remember { mutableStateOf(false) }

    var showTotpSetupDialog by remember { mutableStateOf(false) }
    var showTotpDisableConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "تنظیمات و امنیت",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // ==================== GROUP 1: ظاهر و تم ====================
        CollapsibleSectionCard(
            title = "ظاهر و تم",
            icon = Icons.Default.Palette,
            isExpanded = isGroup1Expanded,
            onToggle = { isGroup1Expanded = !isGroup1Expanded }
        ) {
            // Item 1: Theme Mode Selection
            Column {
                Text("۱- حالت پوسته (تیره / روشن / سیستم)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))

                var themeDropdownExpanded by remember { mutableStateOf(false) }

                val themeOptions = listOf(
                    Triple("DARK", "تیره (دارک)", Icons.Default.DarkMode),
                    Triple("LIGHT", "روشن (لایت)", Icons.Default.LightMode),
                    Triple("SYSTEM", "تابع سیستم", Icons.Default.SettingsBrightness)
                )

                val selectedThemeOption = themeOptions.find { it.first == appThemeMode } ?: themeOptions[0]

                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { themeDropdownExpanded = !themeDropdownExpanded },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = selectedThemeOption.third,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = selectedThemeOption.second,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "بازکردن منو",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = themeDropdownExpanded,
                        onDismissRequest = { themeDropdownExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        themeOptions.forEach { (modeKey, modeLabel, icon) ->
                            val isSelected = appThemeMode == modeKey
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = modeLabel,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    viewModel.setAppThemeMode(modeKey)
                                    themeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Item 2: UI Color Palette Selection
            Column {
                Text("۲- پالت رنگی کامل UI برنامه", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(10.dp))

                val paletteOptions = listOf(
                    Triple("GREEN", Color(0xFF10B981), "سبز اصلی"),
                    Triple("GOLD", Color(0xFFF59E0B), "طلایی"),
                    Triple("CYAN", Color(0xFF06B6D4), "فیروزه‌ای"),
                    Triple("PURPLE", Color(0xFF8B5CF6), "بنفش"),
                    Triple("EMERALD", Color(0xFF059669), "زمردی")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    paletteOptions.forEach { (paletteKey, paletteColor, paletteName) ->
                        val isSelected = appAccentPalette == paletteKey
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setAppAccentPalette(paletteKey) }
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(paletteColor),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "انتخاب شده",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = paletteName,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ==================== GROUP 2: امنیت و احراز هویت ====================
        CollapsibleSectionCard(
            title = "امنیت و احراز هویت",
            icon = Icons.Default.Security,
            isExpanded = isGroup2Expanded,
            onToggle = { isGroup2Expanded = !isGroup2Expanded }
        ) {
            // Item 1: Biometric Lock
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("قفل بیومتریک (اثر انگشت/چهره)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = if (isBiometricHardwareAvailable) "سنسور بیومتریک دستگاه فعال است" else "سنسور بیومتریک روی دستگاه یافت نشد",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = isBiometricEnabled,
                        onCheckedChange = { viewModel.toggleBiometricEnabled(it) }
                    )
                }

                if (isBiometricEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val activity = context.findActivity()
                            if (activity != null) {
                                SecurityManager.authenticateBiometric(
                                    activity = activity,
                                    onSuccess = {
                                        Toast.makeText(context, "احراز هویت بیومتریک با موفقیت انجام شد!", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("آزمایش سنسور اثر انگشت")
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Item 2: TOTP 2FA
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("احراز هویت دو مرحله‌ای (TOTP)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = if (isTotpEnabled) "فعال - آماده استفاده در بازیابی" else "غیرفعال",
                                fontSize = 11.sp,
                                color = if (isTotpEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = isTotpEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showTotpSetupDialog = true
                            } else {
                                showTotpDisableConfirmDialog = true
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "TOTP فقط هنگام بازیابی فایل پشتیبانی لازم است. پشتیبان‌گیری و همگام‌سازی نیازی به آن ندارند.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                if (isTotpEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showTotpDisableConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("غیرفعال‌سازی TOTP")
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Item 3: Master Encryption Password
            Column {
                var passwordInput by remember { mutableStateOf("") }
                var isPasswordVisible by remember { mutableStateOf(false) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = com.example.ui.theme.GoldPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("رمز عبور همگام‌سازی و پشتیبان‌گیری", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = "رمز عبور ثابت برای رمزنگاری فایلهای همگام‌سازی ابری و محلی",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (masterPassword.length in 1..7) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "رمز فعلی شما کوتاه و ضعیف است؛ لطفاً هرچه زودتر آن را تغییر دهید.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { newValue ->
                            if (newValue.length <= 70) {
                                passwordInput = newValue
                            }
                        },
                        label = { Text("کلمه عبور رمزنگاری (تا ۷۰ کاراکتر)") },
                        placeholder = { Text("برای تغییر، رمز جدید وارد کنید") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Password Visibility"
                                )
                            }
                        },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val helperText = when {
                                    passwordInput.length in 1..7 -> "حداقل \u2068۸\u2069 کاراکتر الزامی است"
                                    passwordInput.length in 8..59 -> "برای امنیت بیشتر، \u2068۶۰\u2069 تا \u2068۷۰\u2069 کاراکتر توصیه می‌شود"
                                    passwordInput.length in 60..70 -> "طول کلمه عبور ایده‌آل و امن است"
                                    else -> "طول توصیه شده: \u2068۶۰\u2069 تا \u2068۷۰\u2069 کاراکتر"
                                }
                                val helperColor = when {
                                    passwordInput.length in 1..7 -> MaterialTheme.colorScheme.error
                                    passwordInput.length in 60..70 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = helperText,
                                    color = helperColor,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "\u2068${passwordInput.length}\u2069 / \u2068۷۰\u2069",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (masterPassword.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val activity = context.findActivity()
                            if (activity != null && SecurityManager.isBiometricAvailable(context)) {
                                SecurityManager.authenticateBiometric(
                                    activity = activity,
                                    title = "احراز هویت بیومتریک",
                                    subtitle = "برای مشاهده رمز عبور همگام‌سازی اثر انگشت یا چهره خود را اسکن کنید",
                                    onSuccess = {
                                        passwordInput = masterPassword
                                    },
                                    onError = {
                                        passwordInput = ""
                                        Toast.makeText(context, "احراز هویت لغو شد؛ رمز نمایش داده نشد", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                passwordInput = ""
                                Toast.makeText(context, "احراز هویت لغو شد؛ رمز نمایش داده نشد", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("نمایش رمز فعلی")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        viewModel.updateMasterEncryptionPassword(passwordInput)
                        passwordInput = ""
                        isPasswordVisible = false
                        Toast.makeText(context, "کلمه عبور همگام‌سازی با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = passwordInput.length >= 8
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save Password", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ذخیره کلمه عبور همگام‌سازی", fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Item 4: FLAG_SECURE Screenshot Protection
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("محافظت در برابر اسکرین‌شات (FLAG_SECURE)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = "جلوگیری از ضبط صفحه و اسکرین‌شات در صفحات کارت‌ها",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = isFlagSecureEnabled,
                        onCheckedChange = {
                            viewModel.toggleFlagSecureEnabled(it)
                            val activity = context.findActivity()
                            if (activity != null) {
                                SecurityManager.setScreenProtection(activity, it)
                            }
                        }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Item 5: Auto-Clear Clipboard
            Column {
                var showDisableClipboardDialog by remember { mutableStateOf(false) }

                if (showDisableClipboardDialog) {
                    AlertDialog(
                        onDismissRequest = { showDisableClipboardDialog = false },
                        title = { Text("غیرفعال‌سازی پاکسازی خودکار کلیپ‌بورد", fontWeight = FontWeight.Bold) },
                        text = {
                            Text("بدون پاکسازی خودکار، داده‌های کپی‌شده (مانند CVV2 یا شماره کارت) در کلیپ‌بورد باقی می‌مانند و ممکن است توسط سایر اپ‌ها خوانده شوند.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showDisableClipboardDialog = false
                                    viewModel.setClipboardAutoClearEnabled(false)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("غیرفعال‌سازی")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showDisableClipboardDialog = false }) {
                                Text("انصراف")
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("پاکسازی خودکار کلیپ‌بورد", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = "حذف خودکار داده‌های کپی‌شده از حافظه موقت پس از زمان مشخص",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = isClipboardAutoClearEnabled,
                        onCheckedChange = { checked ->
                            if (!checked) {
                                showDisableClipboardDialog = true
                            } else {
                                viewModel.setClipboardAutoClearEnabled(true)
                            }
                        }
                    )
                }

                if (isClipboardAutoClearEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "پیش‌تنظیم‌های زمان پاکسازی:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val presets = listOf(5, 15, 30, 60, 120)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { sec ->
                            val isSelected = (clipboardAutoClearSeconds == sec)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setClipboardAutoClearSeconds(sec) },
                                label = {
                                    Text(
                                        text = "\u2068$sec\u2069 ثانیه",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "مقدار سفارشی (\u2068۱\u2069 تا \u2068۳۶۰۰\u2069 ثانیه):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    var customInputText by remember(clipboardAutoClearSeconds) {
                        mutableStateOf(clipboardAutoClearSeconds.toString())
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = customInputText,
                            onValueChange = { newValue ->
                                val digitsOnly = newValue.filter { it.isDigit() }
                                if (digitsOnly.length <= 4) {
                                    customInputText = digitsOnly
                                    val parsed = digitsOnly.toIntOrNull()
                                    if (parsed != null && parsed in 1..3600) {
                                        viewModel.setClipboardAutoClearSeconds(parsed)
                                    }
                                }
                            },
                            label = { Text("زمان (ثانیه)") },
                            placeholder = { Text("مثلاً 30") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "زمان فعلی: \u2068$clipboardAutoClearSeconds\u2069 ثانیه",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ==================== GROUP 3: به‌روزرسانی و اطلاعات ====================
        CollapsibleSectionCard(
            title = "به‌روزرسانی و اطلاعات",
            icon = Icons.Default.SystemUpdate,
            isExpanded = isGroup3Expanded,
            onToggle = { isGroup3Expanded = !isGroup3Expanded }
        ) {
            // Item 1: Check for Updates
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = com.example.ui.theme.GoldPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("بررسی به‌روزرسانی برنامه", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = "ارتباط با گیتهاب جهت دریافت نسخه جدید",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val coroutineScope = rememberCoroutineScope()
                var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }

                when (val state = updateState) {
                    is UpdateCheckState.Idle -> {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    updateState = UpdateCheckState.Checking
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val url = URL("https://api.github.com/repos/KhtaAi/Cartino/releases/latest")
                                            val conn = url.openConnection() as HttpURLConnection
                                            conn.requestMethod = "GET"
                                            conn.setRequestProperty("User-Agent", "CartinoApp")
                                            conn.connectTimeout = 7000
                                            conn.readTimeout = 7000

                                            if (conn.responseCode == 200) {
                                                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                                                val json = JSONObject(responseText)
                                                val tagName = json.optString("tag_name", "").trim()
                                                val htmlUrl = json.optString("html_url", "https://github.com/KhtaAi/Cartino/releases")

                                                val cleanTag = tagName.removePrefix("v").trim()
                                                val cleanInstalled = versionName.removePrefix("v").trim()

                                                if (cleanTag.isNotBlank() && cleanTag != cleanInstalled) {
                                                    updateState = UpdateCheckState.Available(latestTag = "v$cleanTag", releaseUrl = htmlUrl)
                                                } else {
                                                    updateState = UpdateCheckState.UpToDate(installedVersion = "v$cleanInstalled")
                                                }
                                            } else {
                                                updateState = UpdateCheckState.Error("کد پاسخ گیتهاب: ${conn.responseCode}")
                                            }
                                        } catch (e: Exception) {
                                            updateState = UpdateCheckState.Error(e.localizedMessage ?: "خطای ارتباط با شبکه")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("بررسی نسخه جدید در گیتهاب")
                        }
                    }
                    is UpdateCheckState.Checking -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("در حال بررسی آخرین انتشار گیتهاب...", fontSize = 12.sp)
                        }
                    }
                    is UpdateCheckState.Available -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = com.example.ui.theme.GoldPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "نسخه جدید ${state.latestTag} در گیتهاب منتشر شده است!",
                                    fontWeight = FontWeight.Bold,
                                    color = com.example.ui.theme.GoldPrimary,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.GoldPrimary)
                            ) {
                                Text("دریافت نسخه جدید از گیتهاب (Releases)", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    is UpdateCheckState.UpToDate -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "برنامه شما آپدیت است (${state.installedVersion})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                    is UpdateCheckState.Error -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "خطا در بررسی به‌روزرسانی: ${state.message}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { updateState = UpdateCheckState.Idle },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("تلاش مجدد")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Item 2: About Cartino & Database Security Details
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("درباره Cartino و امنیت داده‌ها", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Cartino اپلیکیشن حرفه‌ای و امن مدیریت کارت‌های بانکی و مدارک شناسایی ایرانی با قابلیت اسکن هوشمند OCR، همگام‌سازی ابری WebDAV و پشتیبان‌گیری محلی است.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "تمام رکوردها با الگوریتم پیشرفته AES-256 (پایه‌گذاری شده بر Argon2id) و کلیدهای امنیتی Android KeyStore به‌صورت آفلاین ذخیره می‌شوند.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "نسخه نصب‌شده: v\u2068$versionName\u2069",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // Dialogs at Screen level
    if (showTotpSetupDialog) {
        com.example.ui.components.TotpSetupDialog(
            onConfirmEnable = { secret ->
                viewModel.enableTotp(secret)
                showTotpSetupDialog = false
                Toast.makeText(context, "احراز هویت دو مرحله‌ای (TOTP) با موفقیت فعال شد", Toast.LENGTH_LONG).show()
            },
            onDismiss = { showTotpSetupDialog = false }
        )
    }

    if (showTotpDisableConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showTotpDisableConfirmDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.disableTotp()
                        showTotpDisableConfirmDialog = false
                        Toast.makeText(context, "احراز هویت دو مرحله‌ای غیرفعال شد", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("غیرفعال‌سازی")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTotpDisableConfirmDialog = false }) {
                    Text("انصراف")
                }
            },
            title = { Text("غیرفعال‌سازی TOTP", fontWeight = FontWeight.Bold) },
            text = { Text("آیا از غیرفعال‌سازی احراز هویت دو مرحله‌ای اطمینان دارید؟ پس از غیرفعال‌سازی، بازیابی فایل‌های پشتیبان نیازی به کد یکبارمصرف نخواهد داشت.") }
        )
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ArrowRotation"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = if (isExpanded) "بستن گروه" else "بازکردن گروه",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotationAngle)
                )
            }

            if (isExpanded) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    content()
                }
            }
        }
    }
}
