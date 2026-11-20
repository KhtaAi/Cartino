package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import android.content.Context
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.example.ui.MergeStrategy
import com.example.ui.components.BackupScope
import com.example.ui.components.BackupScopeDialog
import com.example.ui.components.ScopeDialogMode
import com.example.ui.components.VaultMergeDialog
import com.example.util.BackupSyncManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.CartinoViewModel
import com.example.ui.SyncUiState
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.ClipboardAutoClearManager
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

@Stable
class SyncBackupController internal constructor(
    val viewModel: CartinoViewModel,
    internal val launchCreateDocument: (String) -> Unit,
    internal val launchOpenDocument: (Array<String>) -> Unit,
    initialMasterPassword: String
) {
    var showPasswordDialog by mutableStateOf(false)
    var showLogsDialog by mutableStateOf(false)
    var showTotpVerifyDialog by mutableStateOf(false)
    var activeTarget by mutableStateOf(BackupTarget.LOCAL_FILE)
    var activeAction by mutableStateOf(ActionType.BACKUP)
    var passwordInput by mutableStateOf(initialMasterPassword)
    var pendingRestoreUri by mutableStateOf<Uri?>(null)
    var pendingRestorePassword by mutableStateOf("")
    var pendingRestoreTarget by mutableStateOf(BackupTarget.LOCAL_FILE)

    // Scope selection state
    var showScopeDialog by mutableStateOf(false)
    var scopeDialogMode by mutableStateOf(ScopeDialogMode.BACKUP)
    var selectedScope by mutableStateOf(BackupScope.APP_DATA_ONLY)

    // Merge conflict dialog state
    var showVaultMergeDialog by mutableStateOf(false)
    var pendingMergeCallback by mutableStateOf<((MergeStrategy) -> Unit)?>(null)

    // Two-step BOTH progress: 0 = idle/not both, 1 = step 1 in progress, 2 = step 2 in progress
    var bothStep by mutableStateOf(0)
    var isVaultRestorePending by mutableStateOf(false)

    fun launchLocalBackup() {
        activeTarget = BackupTarget.LOCAL_FILE
        activeAction = ActionType.BACKUP
        scopeDialogMode = ScopeDialogMode.BACKUP
        showScopeDialog = true
    }

    fun launchLocalRestore() {
        activeTarget = BackupTarget.LOCAL_FILE
        activeAction = ActionType.RESTORE
        scopeDialogMode = ScopeDialogMode.RESTORE
        showScopeDialog = true
    }

    fun openWebDavDialog(action: ActionType) {
        activeTarget = BackupTarget.WEBDAV
        activeAction = action
        scopeDialogMode = if (action == ActionType.BACKUP) ScopeDialogMode.BACKUP else ScopeDialogMode.RESTORE
        showScopeDialog = true
    }

    fun onScopeConfirmed(scope: BackupScope, context: Context) {
        selectedScope = scope
        showScopeDialog = false

        if (activeTarget == BackupTarget.LOCAL_FILE) {
            if (activeAction == ActionType.BACKUP) {
                when (scope) {
                    BackupScope.APP_DATA_ONLY -> {
                        bothStep = 0
                        launchCreateDocument("cartino_backup.enc")
                    }
                    BackupScope.VAULT_ONLY -> {
                        bothStep = 0
                        launchCreateDocument("cartino_vault.cvault")
                    }
                    BackupScope.BOTH -> {
                        bothStep = 1
                        Toast.makeText(context, "مرحله ۱ از ۲: ایجاد فایل پشتیبان داده‌ها (.enc)", Toast.LENGTH_SHORT).show()
                        launchCreateDocument("cartino_backup.enc")
                    }
                }
            } else { // RESTORE
                when (scope) {
                    BackupScope.APP_DATA_ONLY -> {
                        bothStep = 0
                        launchOpenDocument(arrayOf("*/*"))
                    }
                    BackupScope.VAULT_ONLY -> {
                        bothStep = 0
                        launchOpenDocument(arrayOf("*/*"))
                    }
                    BackupScope.BOTH -> {
                        bothStep = 1
                        Toast.makeText(context, "مرحله ۱ از ۲: انتخاب فایل پشتیبان داده‌ها (.enc)", Toast.LENGTH_SHORT).show()
                        launchOpenDocument(arrayOf("*/*"))
                    }
                }
            }
        } else { // WEBDAV
            showPasswordDialog = true
        }
    }
}

@Composable
fun rememberSyncBackupController(
    viewModel: CartinoViewModel,
    onEnsureExpanded: () -> Unit = {}
): SyncBackupController {
    val context = LocalContext.current
    val masterPassword by viewModel.masterEncryptionPassword.collectAsState()

    var launchCreateRef: ((String) -> Unit)? by remember { mutableStateOf(null) }
    var launchOpenRef: ((Array<String>) -> Unit)? by remember { mutableStateOf(null) }

    val controller = remember(viewModel) {
        SyncBackupController(
            viewModel = viewModel,
            launchCreateDocument = { fileName -> launchCreateRef?.invoke(fileName) },
            launchOpenDocument = { mimeTypes -> launchOpenRef?.invoke(mimeTypes) },
            initialMasterPassword = masterPassword
        )
    }

    // Keep passwordInput synchronized whenever masterPassword updates in Settings
    LaunchedEffect(masterPassword) {
        controller.passwordInput = masterPassword
    }

    // SAF Create File Launcher (hoisted so it survives collapsible section collapse/expand)
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { destinationUri ->
            onEnsureExpanded()
            val currentMaster = viewModel.masterEncryptionPassword.value
            val passwordToUse = controller.passwordInput.ifBlank { currentMaster }
            if (passwordToUse.isBlank()) {
                controller.activeTarget = BackupTarget.LOCAL_FILE
                controller.activeAction = ActionType.BACKUP
                controller.pendingRestoreUri = destinationUri
                controller.showPasswordDialog = true
            } else {
                if (controller.bothStep == 1) {
                    // Step 1: create .enc
                    viewModel.createLocalBackupToUri(
                        uri = destinationUri,
                        password = passwordToUse,
                        onSuccess = {
                            Toast.makeText(context, "مرحله ۱ از ۲ انجام شد. مرحله ۲: ایجاد فایل آرشیو صندوق (.cvault)", Toast.LENGTH_LONG).show()
                            controller.bothStep = 2
                            controller.launchCreateDocument("cartino_vault.cvault")
                        },
                        onError = { msg ->
                            controller.bothStep = 0
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                } else if (controller.bothStep == 2 || controller.selectedScope == BackupScope.VAULT_ONLY) {
                    // Create .cvault
                    val wasStep2 = controller.bothStep == 2
                    controller.bothStep = 0
                    viewModel.exportVaultArchive(
                        targetUri = destinationUri,
                        password = passwordToUse.toCharArray(),
                        onSuccess = { result ->
                            val msg = if (wasStep2) {
                                "هر دو فایل پشتیبان (.enc و .cvault) با موفقیت ذخیره شدند"
                            } else {
                                "بک‌آپ صندوق با موفقیت در حافظه ذخیره شد"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        },
                        onError = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                } else {
                    // Normal APP_DATA_ONLY
                    viewModel.createLocalBackupToUri(
                        uri = destinationUri,
                        password = passwordToUse,
                        onSuccess = {
                            Toast.makeText(context, "فایل پشتیبان با موفقیت در حافظه ذخیره شد", Toast.LENGTH_LONG).show()
                        },
                        onError = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        } ?: run {
            controller.bothStep = 0
        }
    }

    // SAF Open File Launcher (hoisted so it survives collapsible section collapse/expand)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            onEnsureExpanded()
            val isVault = BackupSyncManager.isVaultArchiveUri(context, sourceUri)

            if (controller.bothStep == 1 || controller.selectedScope == BackupScope.APP_DATA_ONLY) {
                if (isVault) {
                    val errMsg = "فرمت فایل انتخاب‌شده .cvault است، نه .enc"
                    Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                    viewModel.setSyncError(errMsg)
                    controller.bothStep = 0
                    return@let
                }
                controller.pendingRestoreUri = sourceUri
                controller.activeTarget = BackupTarget.LOCAL_FILE
                controller.activeAction = ActionType.RESTORE
                controller.isVaultRestorePending = false
                controller.showPasswordDialog = true
            } else if (controller.bothStep == 2 || controller.selectedScope == BackupScope.VAULT_ONLY) {
                if (!isVault) {
                    val errMsg = "فرمت فایل انتخاب‌شده .enc است، نه .cvault"
                    Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                    viewModel.setSyncError(errMsg)
                    controller.bothStep = 0
                    return@let
                }
                controller.pendingRestoreUri = sourceUri
                controller.activeTarget = BackupTarget.LOCAL_FILE
                controller.activeAction = ActionType.RESTORE
                controller.isVaultRestorePending = true
                controller.showPasswordDialog = true
            }
        } ?: run {
            controller.bothStep = 0
        }
    }

    launchCreateRef = { fileName -> createDocumentLauncher.launch(fileName) }
    launchOpenRef = { mimeTypes -> openDocumentLauncher.launch(mimeTypes) }

    return controller
}

@Composable
fun SyncBackupScreen(
    viewModel: CartinoViewModel,
    modifier: Modifier = Modifier
) {
    val controller = rememberSyncBackupController(viewModel = viewModel)

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
        SyncBackupSectionContent(
            viewModel = viewModel,
            controller = controller
        )
    }

    SyncBackupDialogsHost(controller = controller)
}

@Composable
fun SyncBackupSectionContent(
    viewModel: CartinoViewModel,
    controller: SyncBackupController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val syncState by viewModel.syncState.collectAsState()
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val masterPassword by viewModel.masterEncryptionPassword.collectAsState()
    val logs by SyncLogger.logs.collectAsState()

    // WebDAV Input States - auto sync with viewModel webDavConfig
    var serverUrlInput by remember(webDavConfig) { mutableStateOf(webDavConfig.serverUrl) }
    var usernameInput by remember(webDavConfig) { mutableStateOf(webDavConfig.username) }
    var passwordWebDavInput by remember(webDavConfig) { mutableStateOf(webDavConfig.password) }
    var remoteFileNameInput by remember(webDavConfig) { mutableStateOf(webDavConfig.remotePath) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "تمام داده‌های شما با الگوریتم AES-256 و کلید اختصاصی شما رمزنگاری می‌شوند.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Inline lock / missing master password banner (never blocks Settings full-screen)
        if (masterPassword.isBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sync_master_password_inline_notice")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "رمز عبور اصلی همگام‌سازی هنوز تنظیم نشده است",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "برای رمزنگاری خودکار فایل‌های پشتیبان، کلمه عبور همگام‌سازی را در بخش «امنیت و احراز هویت» پایین همین صفحه ذخیره کنید، یا هنگام هر بکاپ و بازیابی به‌صورت دستی رمز وارد نمایید.",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        // Status Indicator
        when (syncState) {
            is SyncUiState.Loading, is SyncUiState.Progress -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        val progressText = if (syncState is SyncUiState.Progress) {
                            val p = syncState as SyncUiState.Progress
                            if (p.total > 0) "${p.message} (${p.current} از ${p.total})" else p.message
                        } else {
                            "در حال پردازش عملیات همگام‌سازی..."
                        }
                        Text(progressText, fontSize = 13.sp)
                    }
                }
            }
            is SyncUiState.Success -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text((syncState as SyncUiState.Success).message, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text((syncState as SyncUiState.Error).message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                    }
                }
            }
            SyncUiState.Idle -> {}
        }

        // Vault unbacked-up warning banner
        val isVaultUnbackedUp by viewModel.isVaultUnbackedUp.collectAsState()
        if (isVaultUnbackedUp) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_backup_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "صندوق از آخرین بک‌آپ تغییر کرده است. توصیه می‌شود بک‌آپ جدید بگیرید.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // Section 1: Local / Cloud File Backup (.enc on phone or cloud storage)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                        onClick = { controller.launchLocalBackup() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("local_backup_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("بکاپ", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { controller.launchLocalRestore() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("local_restore_button")
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
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
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
                                controller.openWebDavDialog(ActionType.BACKUP)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("webdav_backup_button")
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
                                controller.openWebDavDialog(ActionType.RESTORE)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("webdav_restore_button")
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
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "لاگ‌ها (\u2068${logs.size}\u2069 ثبت شده)",
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
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sync_logs_menu_button")
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
                                        ClipboardAutoClearManager.onCopied(context, allLogsText)
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
                        onClick = { controller.showLogsDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("sync_logs_view_button")
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
                                ClipboardAutoClearManager.onCopied(context, allLogsText)
                                Toast.makeText(context, "تمام لاگ‌ها در حافظه کپی شدند", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("sync_logs_copy_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("کپی لاگ‌ها", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
fun SyncBackupDialogsHost(
    controller: SyncBackupController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val viewModel = controller.viewModel
    val masterPassword by viewModel.masterEncryptionPassword.collectAsState()
    val logs by SyncLogger.logs.collectAsState()

    // Scope Selection Dialog
    if (controller.showScopeDialog) {
        BackupScopeDialog(
            mode = controller.scopeDialogMode,
            initialScope = controller.selectedScope,
            onConfirm = { scope ->
                controller.onScopeConfirmed(scope, context)
            },
            onDismiss = {
                controller.showScopeDialog = false
                controller.bothStep = 0
            }
        )
    }

    // Vault Merge Conflict Dialog
    if (controller.showVaultMergeDialog) {
        VaultMergeDialog(
            initialStrategy = MergeStrategy.SKIP_DUPLICATES,
            onConfirm = { strategy ->
                controller.showVaultMergeDialog = false
                controller.pendingMergeCallback?.invoke(strategy)
                controller.pendingMergeCallback = null
            },
            onDismiss = {
                controller.showVaultMergeDialog = false
                controller.pendingMergeCallback = null
                controller.bothStep = 0
            }
        )
    }

    // Password & Confirmation Dialog
    if (controller.showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { controller.showPasswordDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        controller.showPasswordDialog = false
                        val effectivePwd = controller.passwordInput.ifBlank { masterPassword }
                        if (controller.activeAction == ActionType.RESTORE) {
                            controller.pendingRestorePassword = effectivePwd
                            controller.pendingRestoreTarget = controller.activeTarget

                            if (controller.activeTarget == BackupTarget.LOCAL_FILE) {
                                val uri = controller.pendingRestoreUri
                                if (uri != null) {
                                    if (controller.isVaultRestorePending) {
                                        coroutineScope.launch {
                                            val manifest = viewModel.inspectVaultArchiveFromUri(uri, effectivePwd.toCharArray())
                                            if (manifest != null && viewModel.hasVaultArchiveDuplicates(manifest)) {
                                                controller.pendingMergeCallback = { strategy ->
                                                    viewModel.importVaultArchive(
                                                        sourceUri = uri,
                                                        password = effectivePwd.toCharArray(),
                                                        mergeStrategy = strategy,
                                                        onSuccess = {
                                                            val msg = if (controller.bothStep == 2) {
                                                                "بازیابی هر دو بخش (.enc و .cvault) با موفقیت انجام شد"
                                                            } else {
                                                                "بازیابی صندوق با موفقیت انجام شد"
                                                            }
                                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                            controller.bothStep = 0
                                                        },
                                                        onError = { err ->
                                                            controller.bothStep = 0
                                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                                        }
                                                    )
                                                }
                                                controller.showVaultMergeDialog = true
                                            } else {
                                                viewModel.importVaultArchive(
                                                    sourceUri = uri,
                                                    password = effectivePwd.toCharArray(),
                                                    mergeStrategy = MergeStrategy.SKIP_DUPLICATES,
                                                    onSuccess = {
                                                        val msg = if (controller.bothStep == 2) {
                                                            "بازیابی هر دو بخش (.enc و .cvault) با موفقیت انجام شد"
                                                        } else {
                                                            "بازیابی صندوق با موفقیت انجام شد"
                                                        }
                                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                        controller.bothStep = 0
                                                    },
                                                    onError = { err ->
                                                        controller.bothStep = 0
                                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        // Regular .enc restore
                                        viewModel.checkBackupTotpRequirement(
                                            target = controller.activeTarget,
                                            uri = uri,
                                            password = effectivePwd
                                        ) { totpRequired, hasTotpSecret ->
                                            if (totpRequired && hasTotpSecret) {
                                                controller.showTotpVerifyDialog = true
                                            } else {
                                                viewModel.restoreLocalBackupFromUri(uri, effectivePwd)
                                                if (controller.bothStep == 1) {
                                                    controller.bothStep = 2
                                                    Toast.makeText(context, "مرحله ۱ از ۲ انجام شد. مرحله ۲: انتخاب فایل آرشیو صندوق (.cvault)", Toast.LENGTH_LONG).show()
                                                    controller.launchOpenDocument(arrayOf("*/*"))
                                                }
                                            }
                                        }
                                    }
                                }
                            } else { // WEBDAV RESTORE
                                when (controller.selectedScope) {
                                    BackupScope.APP_DATA_ONLY -> {
                                        viewModel.checkBackupTotpRequirement(
                                            target = BackupTarget.WEBDAV,
                                            uri = null,
                                            password = effectivePwd
                                        ) { totpRequired, hasTotpSecret ->
                                            if (totpRequired && hasTotpSecret) {
                                                controller.showTotpVerifyDialog = true
                                            } else {
                                                viewModel.restoreFromWebDav(effectivePwd)
                                            }
                                        }
                                    }
                                    BackupScope.VAULT_ONLY -> {
                                        viewModel.downloadAndRestoreVaultArchiveFromWebDav(
                                            password = effectivePwd.toCharArray(),
                                            mergeStrategy = MergeStrategy.SKIP_DUPLICATES,
                                            onSuccess = {
                                                Toast.makeText(context, "بازیابی آرشیو صندوق از WebDAV با موفقیت انجام شد", Toast.LENGTH_LONG).show()
                                            },
                                            onError = { err ->
                                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    }
                                    BackupScope.BOTH -> {
                                        viewModel.checkBackupTotpRequirement(
                                            target = BackupTarget.WEBDAV,
                                            uri = null,
                                            password = effectivePwd
                                        ) { totpRequired, hasTotpSecret ->
                                            if (totpRequired && hasTotpSecret) {
                                                controller.showTotpVerifyDialog = true
                                            } else {
                                                viewModel.restoreFromWebDav(effectivePwd)
                                                viewModel.downloadAndRestoreVaultArchiveFromWebDav(
                                                    password = effectivePwd.toCharArray(),
                                                    mergeStrategy = MergeStrategy.SKIP_DUPLICATES,
                                                    onSuccess = {
                                                        Toast.makeText(context, "بازیابی داده‌ها و آرشیو صندوق از WebDAV با موفقیت انجام شد", Toast.LENGTH_LONG).show()
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else { // BACKUP
                            if (controller.activeTarget == BackupTarget.LOCAL_FILE) {
                                controller.pendingRestoreUri?.let { uri ->
                                    if (controller.selectedScope == BackupScope.VAULT_ONLY || controller.bothStep == 2) {
                                        viewModel.exportVaultArchive(
                                            targetUri = uri,
                                            password = effectivePwd.toCharArray(),
                                            onSuccess = {
                                                Toast.makeText(context, "فایل آرشیو صندوق با موفقیت ذخیره شد", Toast.LENGTH_LONG).show()
                                            },
                                            onError = { msg ->
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    } else {
                                        viewModel.createLocalBackupToUri(
                                            uri = uri,
                                            password = effectivePwd,
                                            onSuccess = {
                                                Toast.makeText(context, "فایل پشتیبان با موفقیت ذخیره شد", Toast.LENGTH_LONG).show()
                                            },
                                            onError = { msg ->
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    }
                                }
                            } else { // WEBDAV BACKUP
                                when (controller.selectedScope) {
                                    BackupScope.APP_DATA_ONLY -> {
                                        viewModel.backupToWebDav(effectivePwd)
                                    }
                                    BackupScope.VAULT_ONLY -> {
                                        viewModel.uploadVaultArchiveToWebDav(
                                            password = effectivePwd.toCharArray(),
                                            onSuccess = {
                                                Toast.makeText(context, "آرشیو صندوق در سرور WebDAV ذخیره شد", Toast.LENGTH_LONG).show()
                                            },
                                            onError = { msg ->
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    }
                                    BackupScope.BOTH -> {
                                        viewModel.backupToWebDav(effectivePwd)
                                        viewModel.uploadVaultArchiveToWebDav(
                                            password = effectivePwd.toCharArray(),
                                            onSuccess = {
                                                Toast.makeText(context, "داده‌های اپ و آرشیو صندوق در سرور WebDAV ذخیره شدند", Toast.LENGTH_LONG).show()
                                            },
                                            onError = { msg ->
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    enabled = (controller.passwordInput.isBlank() && masterPassword.isNotBlank()) || controller.passwordInput.length >= 8
                ) {
                    Text("تایید و اجرا")
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.showPasswordDialog = false }) { Text("انصراف") }
            },
            title = {
                Text(
                    text = if (controller.activeAction == ActionType.BACKUP) "تعیین کلمه عبور رمزنگاری" else "ورود کلمه عبور رمزنگاری",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("جهت امنیت داده‌های شما، تمام اطلاعات با کلمه عبور زیر رمزنگاری می‌شوند.")

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = controller.passwordInput,
                            onValueChange = { controller.passwordInput = it },
                            label = {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text("کلمه عبور اختصاصی (حداقل ۸ کاراکتر)")
                                }
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(textDirection = TextDirection.Ltr),
                            isError = controller.passwordInput.length in 1..7,
                            supportingText = if (controller.passwordInput.length in 1..7) {
                                {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        Text(
                                            text = "رمز پشتیبان باید حداقل ۸ کاراکتر باشد.",
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        )
    }

    if (controller.showTotpVerifyDialog) {
        com.example.ui.components.TotpVerifyDialog(
            onSuccess = { code ->
                controller.showTotpVerifyDialog = false
                when (controller.pendingRestoreTarget) {
                    BackupTarget.WEBDAV -> {
                        viewModel.restoreFromWebDav(controller.pendingRestorePassword, totpCode = code)
                        if (controller.selectedScope == BackupScope.BOTH) {
                            viewModel.downloadAndRestoreVaultArchiveFromWebDav(
                                password = controller.pendingRestorePassword.toCharArray(),
                                totpCode = code,
                                mergeStrategy = MergeStrategy.SKIP_DUPLICATES,
                                onSuccess = {
                                    Toast.makeText(context, "بازیابی داده‌ها و آرشیو صندوق از WebDAV با موفقیت انجام شد", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                    BackupTarget.LOCAL_FILE -> {
                        controller.pendingRestoreUri?.let { uri ->
                            viewModel.restoreLocalBackupFromUri(uri, controller.pendingRestorePassword, totpCode = code)
                            if (controller.bothStep == 1) {
                                controller.bothStep = 2
                                Toast.makeText(context, "مرحله ۱ از ۲ انجام شد. مرحله ۲: انتخاب فایل آرشیو صندوق (.cvault)", Toast.LENGTH_LONG).show()
                                controller.launchOpenDocument(arrayOf("*/*"))
                            }
                        }
                    }
                }
            },
            onDismiss = {
                controller.showTotpVerifyDialog = false
                controller.bothStep = 0
            }
        )
    }

    // Full Diagnostic Logs Viewer Dialog
    if (controller.showLogsDialog) {
        AlertDialog(
            onDismissRequest = { controller.showLogsDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val allLogsText = SyncLogger.getAllLogsText()
                        if (allLogsText.isNotBlank()) {
                            clipboardManager.setText(AnnotatedString(allLogsText))
                            ClipboardAutoClearManager.onCopied(context, allLogsText)
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
                TextButton(onClick = { controller.showLogsDialog = false }) {
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
                    Text("\u2068${logs.size}\u2069 لاگ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
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
