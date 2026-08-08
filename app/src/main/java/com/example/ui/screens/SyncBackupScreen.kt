package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.CartinoViewModel
import com.example.ui.SyncUiState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.SyncLogger
import com.example.util.WebDavConfig

enum class BackupTarget {
    LOCAL_FILE,
    WEBDAV
}

enum class ActionType {
    BACKUP,
    RESTORE
}

@Composable
fun SyncBackupScreen(
    viewModel: CartinoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val syncState by viewModel.syncState.collectAsState()
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val masterPassword by viewModel.masterEncryptionPassword.collectAsState()
    val logs by SyncLogger.logs.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var activeTarget by remember { mutableStateOf(BackupTarget.LOCAL_FILE) }
    var activeAction by remember { mutableStateOf(ActionType.BACKUP) }

    var passwordInput by remember(masterPassword) { mutableStateOf(masterPassword) }

    // WebDAV Input States - auto sync with viewModel webDavConfig
    var serverUrlInput by remember(webDavConfig) { mutableStateOf(webDavConfig.serverUrl) }
    var usernameInput by remember(webDavConfig) { mutableStateOf(webDavConfig.username) }
    var passwordWebDavInput by remember(webDavConfig) { mutableStateOf(webDavConfig.password) }
    var remoteFileNameInput by remember(webDavConfig) { mutableStateOf(webDavConfig.remotePath) }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // SAF Create File Launcher (Local Backup to phone internal/external storage)
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { destinationUri ->
            val passwordToUse = passwordInput.ifBlank { masterPassword }
            if (passwordToUse.isBlank()) {
                activeTarget = BackupTarget.LOCAL_FILE
                activeAction = ActionType.BACKUP
                pendingRestoreUri = destinationUri
                showPasswordDialog = true
            } else {
                viewModel.createLocalBackupToUri(
                    uri = destinationUri,
                    password = passwordToUse,
                    onSuccess = { Toast.makeText(context, "فایل پشتیبان با موفقیت در حافظه ذخیره شد", Toast.LENGTH_LONG).show() },
                    onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                )
            }
        }
    }

    // SAF Open File Launcher (Local Restore from phone internal/external storage)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            pendingRestoreUri = sourceUri
            activeTarget = BackupTarget.LOCAL_FILE
            activeAction = ActionType.RESTORE
            showPasswordDialog = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "پشتیبان‌گیری و همگام‌سازی ابری و محلی",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "تمام داده‌های شما با الگوریتم AES-256 و کلید اختصاصی شما رمزنگاری می‌شوند.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Status Indicator
        when (syncState) {
            is SyncUiState.Loading -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("در حال پردازش عملیات همگام‌سازی...")
                    }
                }
            }
            is SyncUiState.Success -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text((syncState as SyncUiState.Success).message, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            is SyncUiState.Error -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text((syncState as SyncUiState.Error).message, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            SyncUiState.Idle -> {}
        }

        // Section 1: Local / Cloud File Backup (.enc on phone or cloud storage)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "پشتیبان‌گیری روی دستگاه یا فضای ابری",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Info Hint
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "می‌توانید پوشه‌ای از هر ارائه‌دهندهٔ نصب‌شده روی دستگاه انتخاب کنید: Files داخلی، Google Drive، Dropbox، OneDrive و غیره.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            createDocumentLauncher.launch("cartino_backup.enc")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("بکاپ", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            openDocumentLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("بازیابی از فایل", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Section 2: WebDAV Personal Server
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "پشتیبان‌گیری WebDAV (Koofr / سرور شخصی / ...)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Force LTR text direction for Server URL input
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { newValue ->
                            serverUrlInput = newValue
                            viewModel.updateWebDavConfig(
                                WebDavConfig(newValue, usernameInput, passwordWebDavInput, remoteFileNameInput)
                            )
                        },
                        label = {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Text("آدرس کامل سرور و پوشه (Server URL)")
                            }
                        },
                        placeholder = { Text("https://app.koofr.net/dav/Koofr/Backups/") },
                        singleLine = true,
                        textStyle = TextStyle(textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { newValue ->
                                usernameInput = newValue
                                viewModel.updateWebDavConfig(
                                    WebDavConfig(serverUrlInput, newValue, passwordWebDavInput, remoteFileNameInput)
                                )
                            },
                            label = {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text("نام کاربری / ایمیل")
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(textDirection = TextDirection.Ltr),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordWebDavInput,
                            onValueChange = { newValue ->
                                passwordWebDavInput = newValue
                                viewModel.updateWebDavConfig(
                                    WebDavConfig(serverUrlInput, usernameInput, newValue, remoteFileNameInput)
                                )
                            },
                            label = {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text("کلمه عبور / App Token")
                                }
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(textDirection = TextDirection.Ltr),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // WebDAV Backup Filename Field (LTR, automatic .enc extension)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = remoteFileNameInput,
                        onValueChange = { newValue ->
                            remoteFileNameInput = newValue
                            viewModel.updateWebDavConfig(
                                WebDavConfig(serverUrlInput, usernameInput, passwordWebDavInput, newValue)
                            )
                        },
                        label = {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Text("نام فایل بک‌آپ در سرور")
                            }
                        },
                        placeholder = { Text("cartino_backup") },
                        supportingText = {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Text(
                                    text = "فرمت فایل به صورت خودکار enc. ذخیره می‌شود.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (serverUrlInput.isBlank() || usernameInput.isBlank() || passwordWebDavInput.isBlank()) {
                                Toast.makeText(context, "لطفاً ابتدا آدرس سرور، نام کاربری و کلمه عبور WebDAV را وارد کنید", Toast.LENGTH_LONG).show()
                            } else {
                                activeTarget = BackupTarget.WEBDAV
                                activeAction = ActionType.BACKUP
                                showPasswordDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ارسال به سرور", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            if (serverUrlInput.isBlank() || usernameInput.isBlank() || passwordWebDavInput.isBlank()) {
                                Toast.makeText(context, "لطفاً ابتدا آدرس سرور، نام کاربری و کلمه عبور WebDAV را وارد کنید", Toast.LENGTH_LONG).show()
                            } else {
                                activeTarget = BackupTarget.WEBDAV
                                activeAction = ActionType.RESTORE
                                showPasswordDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("دریافت از سرور", maxLines = 1, softWrap = false)
                    }
                }
            }
        }



        // Section 3: Diagnostic Sync Logs Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "لاگ‌ها (${logs.size} ثبت شده)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    var showLogsMenu by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            onClick = { showLogsMenu = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "منوی لاگ‌ها",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showLogsMenu,
                            onDismissRequest = { showLogsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("کپی تمام لاگ‌ها") },
                                onClick = {
                                    showLogsMenu = false
                                    val allLogsText = SyncLogger.getAllLogsText()
                                    if (allLogsText.isBlank()) {
                                        Toast.makeText(context, "هنوز لاگی ثبت نشده است", Toast.LENGTH_SHORT).show()
                                    } else {
                                        clipboardManager.setText(AnnotatedString(allLogsText))
                                        Toast.makeText(context, "تمام لاگ‌ها در حافظه کپی شدند", Toast.LENGTH_LONG).show()
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("پاک‌سازی تمام لاگ‌ها", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showLogsMenu = false
                                    SyncLogger.clear()
                                    Toast.makeText(context, "لاگ‌ها پاک‌سازی شدند", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ثبت جزئیات دقیق اتصال WebDAV، وضعیت رمزنگاری و خطاهای رخ‌داده جهت عیب‌یابی.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showLogsDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ListAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مشاهده لاگ‌ها", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            val allLogsText = SyncLogger.getAllLogsText()
                            if (allLogsText.isBlank()) {
                                Toast.makeText(context, "هنوز لاگی ثبت نشده است", Toast.LENGTH_SHORT).show()
                            } else {
                                clipboardManager.setText(AnnotatedString(allLogsText))
                                Toast.makeText(context, "تمام لاگ‌ها در حافظه کپی شدند", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("کپی لاگ‌ها", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }

    // Password & Confirmation Dialog
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        showPasswordDialog = false
                        val effectivePwd = passwordInput.ifBlank { masterPassword }
                        when (activeTarget) {
                            BackupTarget.WEBDAV -> {
                                if (activeAction == ActionType.BACKUP) {
                                    viewModel.backupToWebDav(effectivePwd)
                                } else {
                                    viewModel.restoreFromWebDav(effectivePwd)
                                }
                            }
                            BackupTarget.LOCAL_FILE -> {
                                pendingRestoreUri?.let { uri ->
                                    if (activeAction == ActionType.BACKUP) {
                                        viewModel.createLocalBackupToUri(
                                            uri = uri,
                                            password = effectivePwd,
                                            onSuccess = { Toast.makeText(context, "فایل پشتیبان با موفقیت ذخیره شد", Toast.LENGTH_LONG).show() },
                                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                                        )
                                    } else {
                                        viewModel.restoreLocalBackupFromUri(uri, effectivePwd)
                                    }
                                }
                            }
                        }
                    },
                    enabled = passwordInput.isNotBlank() || masterPassword.isNotBlank()
                ) {
                    Text("تایید و اجرا")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) { Text("انصراف") }
            },
            title = {
                Text(
                    text = if (activeAction == ActionType.BACKUP) "تعیین کلمه عبور رمزنگاری" else "ورود کلمه عبور رمزنگاری",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("جهت امنیت داده‌های شما، تمام اطلاعات با کلمه عبور زیر رمزنگاری می‌شوند.")

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text("کلمه عبور اختصاصی (حداقل ۴ کاراکتر)")
                                }
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(textDirection = TextDirection.Ltr),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        )
    }

    // Full Diagnostic Logs Viewer Dialog
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val allLogsText = SyncLogger.getAllLogsText()
                        if (allLogsText.isNotBlank()) {
                            clipboardManager.setText(AnnotatedString(allLogsText))
                            Toast.makeText(context, "تمام لاگ‌ها در حافظه کپی شدند", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("کپی لاگ‌ها")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("بستن")
                }
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("لاگ‌های همگام‌سازی", fontWeight = FontWeight.Bold)
                    Text("${logs.size} لاگ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                if (logs.isEmpty()) {
                    Text(
                        text = "هنوز لاگی ثبت نشده است. عملیات پشتیبان‌گیری یا بازیابی را اجرا کنید تا اطلاعات ثبت شوند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(logs) { logLine ->
                                    Text(
                                        text = logLine,
                                        fontSize = 11.sp,
                                        fontFamily = VazirmatnFontFamily,
                                        color = if (logLine.contains("خطا") || logLine.contains("Error") || logLine.contains("401") || logLine.contains("404"))
                                            MaterialTheme.colorScheme.error
                                        else if (logLine.contains("موفقیت") || logLine.contains("SUCCESS") || logLine.contains("200") || logLine.contains("201"))
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}
