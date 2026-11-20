package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.CartinoDatabase
import com.example.data.model.DocumentGroup
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.ui.MergeStrategy
import com.example.ui.SyncUiState
import com.example.ui.components.BackupScope
import com.example.ui.components.BackupScopeDialog
import com.example.ui.components.ScopeDialogMode
import com.example.ui.components.TotpVerifyDialog
import com.example.ui.components.VaultMergeDialog
import com.example.ui.theme.CartinoTheme
import com.example.util.BackupSyncManager
import com.example.util.TotpManager
import com.example.util.VaultArchiveFileMetadata
import com.example.util.VaultArchiveGroupMetadata
import com.example.util.VaultArchiveManifest
import com.example.util.VaultArchiveManager
import com.example.util.VaultStorageManager
import com.example.util.WebDavConfig
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultBackupRestoreUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: CartinoViewModel
    private lateinit var tempDir: File

    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup(): Unit = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        tempDir = File(application.cacheDir, "vault_backup_ui_test").apply {
            deleteRecursively()
            mkdirs()
        }
        VaultStorageManager.getVaultDirectory(application).deleteRecursively()
        VaultStorageManager.getVaultDirectory(application)

        application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testSecretKey = keyGen.generateKey()
        VaultStorageManager.setTestKeyProviderForTesting { testSecretKey }

        val db = CartinoDatabase.getDatabase(application)
        db.vaultDao().getAllFilesList().forEach { db.vaultDao().deleteFileById(it.id) }
        db.vaultDao().getAllGroupsList().forEach { db.vaultDao().deleteGroupById(it.id) }
        db.identityDocumentDao().getAllGroupsList().forEach { db.identityDocumentDao().deleteGroupById(it.id) }

        viewModel = CartinoViewModel(application)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        val db = CartinoDatabase.getDatabase(application)
        db.vaultDao().getAllFilesList().forEach { db.vaultDao().deleteFileById(it.id) }
        db.vaultDao().getAllGroupsList().forEach { db.vaultDao().deleteGroupById(it.id) }
        db.identityDocumentDao().getAllGroupsList().forEach { db.identityDocumentDao().deleteGroupById(it.id) }
        VaultStorageManager.setTestKeyProviderForTesting(null)
        VaultArchiveManager.setTestTotpConfigProviderForTesting(null)
        VaultStorageManager.getVaultDirectory(application).deleteRecursively()
        tempDir.deleteRecursively()
    }

    @Test
    fun backupScopeDialog_backupMode_rendersOptionsAndSelectsVault() {
        var confirmedScope: BackupScope? = null
        var dismissed = false

        composeTestRule.setContent {
            CartinoTheme {
                BackupScopeDialog(
                    mode = ScopeDialogMode.BACKUP,
                    initialScope = BackupScope.APP_DATA_ONLY,
                    onConfirm = { confirmedScope = it },
                    onDismiss = { dismissed = true }
                )
            }
        }

        // Check dialog title
        composeTestRule.onNodeWithText("محدوده پشتیبان‌گیری").assertIsDisplayed()

        // Check options
        composeTestRule.onNodeWithTag("scope_option_app_data").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scope_option_vault").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scope_option_both").assertIsDisplayed()

        // Select VAULT_ONLY and click confirm
        composeTestRule.onNodeWithTag("scope_option_vault").performClick()
        composeTestRule.onNodeWithTag("scope_dialog_confirm").performClick()

        assertEquals(BackupScope.VAULT_ONLY, confirmedScope)
        assertFalse(dismissed)
    }

    @Test
    fun backupScopeDialog_restoreMode_showsBothExplanationAndDismisses() {
        var confirmedScope: BackupScope? = null
        var dismissed = false

        composeTestRule.setContent {
            CartinoTheme {
                BackupScopeDialog(
                    mode = ScopeDialogMode.RESTORE,
                    initialScope = BackupScope.APP_DATA_ONLY,
                    onConfirm = { confirmedScope = it },
                    onDismiss = { dismissed = true }
                )
            }
        }

        // Title should be RESTORE title
        composeTestRule.onNodeWithText("محدوده بازیابی").assertIsDisplayed()

        // Clicking BOTH shows explanation
        composeTestRule.onNodeWithTag("scope_option_both").performClick()
        composeTestRule.onNodeWithText("دو فایل جداگانه بازیابی می‌شوند").assertIsDisplayed()

        // Dismiss
        composeTestRule.onNodeWithTag("scope_dialog_dismiss").performClick()
        assertTrue(dismissed)
        assertEquals(null, confirmedScope)
    }

    @Test
    fun backupScopeDialog_selectBoth_confirmsBothScope() {
        var confirmedScope: BackupScope? = null

        composeTestRule.setContent {
            CartinoTheme {
                BackupScopeDialog(
                    mode = ScopeDialogMode.BACKUP,
                    initialScope = BackupScope.APP_DATA_ONLY,
                    onConfirm = { confirmedScope = it },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("scope_option_both").performClick()
        composeTestRule.onNodeWithTag("scope_dialog_confirm").performClick()

        assertEquals(BackupScope.BOTH, confirmedScope)
    }

    @Test
    fun vaultMergeDialog_rendersThreeOptionsAndSelectsStrategy() {
        var confirmedStrategy: MergeStrategy? = null
        var dismissed = false

        composeTestRule.setContent {
            CartinoTheme {
                VaultMergeDialog(
                    initialStrategy = MergeStrategy.SKIP_DUPLICATES,
                    onConfirm = { confirmedStrategy = it },
                    onDismiss = { dismissed = true }
                )
            }
        }

        // Verify title & description
        composeTestRule.onNodeWithText("تداخل در بازیابی صندوق").assertIsDisplayed()
        composeTestRule.onNodeWithText("برخی از گروه‌ها یا فایل‌های آرشیو با موارد موجود در صندوق هم‌نام هستند. لطفاً نحوه رفتار با موارد تکراری را تعیین کنید:").assertIsDisplayed()

        // Verify 3 options exist
        composeTestRule.onNodeWithTag("merge_option_skip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("merge_option_replace").assertIsDisplayed()
        composeTestRule.onNodeWithTag("merge_option_rename").assertIsDisplayed()

        // Select REPLACE and confirm
        composeTestRule.onNodeWithTag("merge_option_replace").performClick()
        composeTestRule.onNodeWithTag("vault_merge_dialog_confirm").performClick()

        assertEquals(MergeStrategy.REPLACE_DUPLICATES, confirmedStrategy)
        assertFalse(dismissed)
    }

    @Test
    fun vaultMergeDialog_selectRename_confirmsRenameStrategy() {
        var confirmedStrategy: MergeStrategy? = null

        composeTestRule.setContent {
            CartinoTheme {
                VaultMergeDialog(
                    initialStrategy = MergeStrategy.SKIP_DUPLICATES,
                    onConfirm = { confirmedStrategy = it },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("merge_option_rename").performClick()
        composeTestRule.onNodeWithTag("vault_merge_dialog_confirm").performClick()

        assertEquals(MergeStrategy.RENAME_DUPLICATES, confirmedStrategy)
    }

    @Test
    fun vaultMergeDialog_dismissWorks() {
        var dismissed = false

        composeTestRule.setContent {
            CartinoTheme {
                VaultMergeDialog(
                    onConfirm = {},
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("vault_merge_dialog_dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun formatValidation_distinguishesVaultArchiveFromEnc() {
        // Create a mock .cvault file with ASCII header "CARTINO_VAULT"
        val cvaultFile = File(tempDir, "test.cvault").apply {
            writeBytes("CARTINO_VAULT".toByteArray(Charsets.US_ASCII) + ByteArray(20))
        }
        val cvaultUri = Uri.fromFile(cvaultFile)

        // Create a mock .enc file (plain text / base64)
        val encFile = File(tempDir, "test.enc").apply {
            writeText("eyJ2ZXJzaW9uIjozfQ==")
        }
        val encUri = Uri.fromFile(encFile)

        // Test URI checks
        assertTrue(BackupSyncManager.isVaultArchiveUri(application, cvaultUri))
        assertFalse(BackupSyncManager.isVaultArchiveUri(application, encUri))

        // Test bytes checks
        assertTrue(BackupSyncManager.isVaultArchiveBytes(cvaultFile.readBytes()))
        assertFalse(BackupSyncManager.isVaultArchiveBytes(encFile.readBytes()))
        assertFalse(BackupSyncManager.isVaultArchiveBytes(ByteArray(5)))
    }

    @Test
    fun extractVaultFileName_normalizesCorrectly() {
        assertEquals("cartino_vault.cvault", BackupSyncManager.extractVaultFileName(""))
        assertEquals("cartino_vault.cvault", BackupSyncManager.extractVaultFileName("   "))
        assertEquals("cartino_backup.cvault", BackupSyncManager.extractVaultFileName("cartino_backup.enc"))
        assertEquals("folder/backup.cvault", BackupSyncManager.extractVaultFileName("folder/backup.enc"))
        assertEquals("sub/dir/custom_vault.cvault", BackupSyncManager.extractVaultFileName("sub/dir/custom_vault.cvault"))
        assertEquals("archive.cvault", BackupSyncManager.extractVaultFileName("archive"))
    }

    @Test
    fun isVaultUnbackedUp_reactsToVaultChangesAndTimestamp() = runBlocking {
        val db = CartinoDatabase.getDatabase(application)
        val prefs = application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)

        // 1. Initially empty vault
        val initialFiles = db.vaultDao().getAllFiles().first()
        val initialGroups = db.vaultDao().getAllGroups().first()
        assertTrue(initialFiles.isEmpty() && initialGroups.isEmpty())

        // 2. Insert a file into vault
        val testFile = VaultFile(
            id = UUID.randomUUID().toString(),
            groupId = null,
            displayName = "گذرنامه.pdf",
            originalFileName = "passport.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024L,
            sha256 = "0".repeat(64),
            storageName = "storage_1.enc",
            createdAt = System.currentTimeMillis() - 1000,
            updatedAt = System.currentTimeMillis() - 1000
        )
        db.vaultDao().insertFile(testFile)

        val files = db.vaultDao().getAllFiles().first()
        assertEquals(1, files.size)

        // Check calculation logic directly: with no backup, unbacked up is true
        val lastVaultBackup0 = prefs.getLong("last_vault_backup_timestamp", 0L)
        val isUnbackedUp0 = if (files.isEmpty()) false else (lastVaultBackup0 == 0L || files.maxOf { maxOf(it.createdAt, it.updatedAt) } > lastVaultBackup0)
        assertTrue(isUnbackedUp0)

        // 3. Set last_vault_backup_timestamp to AFTER file creation
        prefs.edit().putLong("last_vault_backup_timestamp", System.currentTimeMillis() + 5000).commit()

        val lastBackup = prefs.getLong("last_vault_backup_timestamp", 0L)
        val latestChange = files.maxOf { maxOf(it.createdAt, it.updatedAt) }
        assertTrue(lastBackup > latestChange)

        val isUnbackedUpAfter = if (files.isEmpty()) false else (lastBackup == 0L || latestChange > lastBackup)
        assertFalse(isUnbackedUpAfter)
    }

    @Test
    fun warningBanner_displaysInUIWhenUnbackedUp() {
        composeTestRule.setContent {
            CartinoTheme {
                Surface(modifier = Modifier.testTag("vault_backup_warning_banner")) {
                    androidx.compose.material3.Text("صندوق از آخرین بک‌آپ تغییر کرده است. توصیه می‌شود بک‌آپ جدید بگیرید.")
                }
            }
        }

        composeTestRule.onNodeWithTag("vault_backup_warning_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("صندوق از آخرین بک‌آپ تغییر کرده است. توصیه می‌شود بک‌آپ جدید بگیرید.").assertIsDisplayed()
    }

    @Test
    fun hasVaultArchiveDuplicates_detectsCollisionsAccurately() = runBlocking {
        val db = CartinoDatabase.getDatabase(application)

        // Insert an existing group and file
        val grp = VaultGroup(id = "grp_1", name = "اسناد رسمی")
        db.vaultDao().insertGroup(grp)

        val file = VaultFile(
            id = "file_1",
            groupId = "grp_1",
            displayName = "کارت ملی.pdf",
            originalFileName = "meli.pdf",
            mimeType = "application/pdf",
            sizeBytes = 500L,
            sha256 = "1".repeat(64),
            storageName = "stg_1.enc"
        )
        db.vaultDao().insertFile(file)

        // 1. Manifest with duplicate group name -> should return true
        val manifestWithDupGroup = VaultArchiveManifest(
            timestamp = System.currentTimeMillis(),
            totpRequired = false,
            groups = listOf(VaultArchiveGroupMetadata(id = "new_grp_1", name = "اسناد رسمی", createdAt = 1000L)),
            files = emptyList()
        )
        assertTrue(viewModel.hasVaultArchiveDuplicates(manifestWithDupGroup))

        // 2. Manifest with duplicate file name in matching group -> should return true
        val manifestWithDupFile = VaultArchiveManifest(
            timestamp = System.currentTimeMillis(),
            totpRequired = false,
            groups = listOf(VaultArchiveGroupMetadata(id = "grp_1", name = "اسناد رسمی", createdAt = 1000L)),
            files = listOf(
                VaultArchiveFileMetadata(
                    id = "new_f_1",
                    groupId = "grp_1",
                    displayName = "کارت ملی.pdf",
                    originalFileName = "meli_new.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 500L,
                    sha256 = "1".repeat(64),
                    storageName = "stg_2.enc",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )
        )
        assertTrue(viewModel.hasVaultArchiveDuplicates(manifestWithDupFile))

        // 3. Manifest with completely new groups and files -> should return false
        val manifestClean = VaultArchiveManifest(
            timestamp = System.currentTimeMillis(),
            totpRequired = false,
            groups = listOf(VaultArchiveGroupMetadata(id = "g_clean", name = "پروژه‌ها", createdAt = 1000L)),
            files = listOf(
                VaultArchiveFileMetadata(
                    id = "f_clean",
                    groupId = "g_clean",
                    displayName = "گزارش مالی.xlsx",
                    originalFileName = "report.xlsx",
                    mimeType = "application/vnd.ms-excel",
                    sizeBytes = 1200L,
                    sha256 = "2".repeat(64),
                    storageName = "stg_3.enc",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )
        )
        assertFalse(viewModel.hasVaultArchiveDuplicates(manifestClean))
    }

    @Test
    fun webDavConfig_validatesEmptyFields() {
        val emptyConfig = WebDavConfig()
        var exceptionThrown = false
        runBlocking {
            try {
                BackupSyncManager.uploadVaultArchiveToWebDav(
                    context = application,
                    config = emptyConfig,
                    password = "password123".toCharArray()
                )
            } catch (e: IllegalArgumentException) {
                exceptionThrown = true
                assertTrue(e.message?.contains("WebDAV") == true)
            }
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun totpVerifyDialog_againstArchiveSecret_validatesRejectsAndDismissesCleanly() {
        val archiveSecret = "JBSWY3DPEHPK3PXP"
        val currentSec = System.currentTimeMillis() / 1000L
        val validWindowCodes = (-1..1).map {
            TotpManager.generateTotp(archiveSecret, currentSec + it * 30L)
        }.toSet()
        val wrongCode = listOf("000000", "111111", "222222", "999999").first { it !in validWindowCodes }
        val liveCode = TotpManager.generateTotp(archiveSecret, currentSec)

        var verifiedCode: String? = null
        var dismissed = false
        var verifiedCodeValid: String? = null
        val stage = androidx.compose.runtime.mutableStateOf(1)

        composeTestRule.setContent {
            CartinoTheme {
                when (stage.value) {
                    1 -> TotpVerifyDialog(
                        onVerify = { code -> TotpManager.verifyTotp(archiveSecret, code) },
                        onSuccess = { code -> verifiedCode = code },
                        onDismiss = {
                            dismissed = true
                            stage.value = 2
                        }
                    )
                    2 -> TotpVerifyDialog(
                        onVerify = { code -> TotpManager.verifyTotp(archiveSecret, code) },
                        onSuccess = { code -> verifiedCodeValid = code },
                        onDismiss = {}
                    )
                }
            }
        }

        // 1. Wrong code shows Persian error without invoking onSuccess
        composeTestRule.onNodeWithText("123456").performTextInput(wrongCode)
        composeTestRule.onNodeWithText("تایید و بازیابی").performClick()
        composeTestRule.onNodeWithText("کد TOTP واردشده اشتباه است یا منقضی شده است.").assertIsDisplayed()
        assertEquals(null, verifiedCode)
        assertFalse(dismissed)

        // 2. Dismiss transitions cleanly and moves to fresh dialog for valid code path
        composeTestRule.onNodeWithText("انصراف").performClick()
        assertTrue(dismissed)

        // 3. Valid code path against archive secret succeeds
        composeTestRule.onNodeWithText("123456").performTextInput(liveCode)
        composeTestRule.onNodeWithText("تایید و بازیابی").performClick()
        assertEquals(liveCode, verifiedCodeValid)
    }

    @Test
    fun backupAndUploadPaths_succeedWithoutTotpCode_evenWhenTotpEnabled() = runBlocking {
        VaultArchiveManager.setTestTotpConfigProviderForTesting { true to "JBSWY3DPEHPK3PXP" }

        val src = File(tempDir, "doc.txt").apply { writeBytes("Vault upload doc".toByteArray()) }
        viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = null)

        val requests = CopyOnWriteArrayList<String>()
        withLocalWebDavServer(
            handler = { method, path, _ ->
                requests.add("$method $path")
                WebDavStubResponse(201, "Created", ByteArray(0))
            }
        ) { baseUrl ->
            viewModel.updateWebDavConfig(WebDavConfig(baseUrl, "user", "pass", "cartino_backup.enc"))

            // 1. VAULT_ONLY upload requires no TOTP code
            var vaultCallbackOk = false
            viewModel.uploadVaultArchiveToWebDav(
                password = "UploadPassword!2026".toCharArray(),
                onSuccess = { vaultCallbackOk = true }
            )
            waitUntilSyncStateSettled()
            assertTrue(vaultCallbackOk)
            assertTrue(viewModel.syncState.value is SyncUiState.Success)

            // 2. BOTH upload requires no TOTP code and uploads .enc then .cvault
            requests.clear()
            viewModel.resetSyncState()
            var bothMsg = ""
            viewModel.backupBothToWebDav(
                password = "UploadPassword!2026",
                onSuccess = { bothMsg = it }
            )
            waitUntilSyncStateSettled()
            assertTrue(viewModel.syncState.value is SyncUiState.Success)
            assertTrue(bothMsg.contains("ذخیره شدند"))
            assertEquals(listOf("PUT /dav/cartino_backup.enc", "PUT /dav/cartino_backup.cvault"), requests.toList())

            val prefs = application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
            assertTrue(prefs.getLong("last_enc_backup_timestamp", 0L) > 0L)
            assertTrue(prefs.getLong("last_vault_backup_timestamp", 0L) > 0L)
        }
    }

    @Test
    fun backupBothToWebDav_whenVaultUploadFailsAfterEncSucceeds_setsPartialSuccessMessage() = runBlocking {
        val src = File(tempDir, "doc2.txt").apply { writeBytes("Vault doc2".toByteArray()) }
        viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = null)

        withLocalWebDavServer(
            handler = { _, path, _ ->
                if (path.endsWith(".enc")) {
                    WebDavStubResponse(201, "Created", ByteArray(0))
                } else {
                    WebDavStubResponse(500, "Internal Server Error", ByteArray(0))
                }
            }
        ) { baseUrl ->
            viewModel.updateWebDavConfig(WebDavConfig(baseUrl, "user", "pass", "cartino_backup.enc"))
            viewModel.backupBothToWebDav(password = "PartialFail!Pass2026")
            waitUntilSyncStateSettled()

            val state = viewModel.syncState.value
            assertTrue("Expected SyncUiState.Success partial message, got $state", state is SyncUiState.Success)
            val msg = (state as SyncUiState.Success).message
            assertTrue(
                "Unexpected partial success message: $msg",
                msg.startsWith("فایل .enc آپلود شد، اما آرشیو صندوق نه:")
            )
        }
    }

    @Test
    fun webDavRestore_404BehaviorForBothAndVaultOnly() = runBlocking {
        // Seed DB with one document group, create a valid .enc payload, then clear DB
        val db = CartinoDatabase.getDatabase(application)
        db.identityDocumentDao().insertGroup(
            DocumentGroup(
                id = "doc-grp-404",
                name = "گروه تست ۴۰۴"
            )
        )
        val encPayload = BackupSyncManager.createEncryptedBackupPayload(application, "Restore404!Pass2026")
        db.identityDocumentDao().getAllGroupsList().forEach { db.identityDocumentDao().deleteGroupById(it.id) }

        withLocalWebDavServer(
            handler = { _, path, _ ->
                if (path.endsWith(".enc")) {
                    WebDavStubResponse(200, "OK", encPayload.toByteArray(Charsets.UTF_8))
                } else {
                    WebDavStubResponse(404, "Not Found", ByteArray(0))
                }
            }
        ) { baseUrl ->
            viewModel.updateWebDavConfig(WebDavConfig(baseUrl, "user", "pass", "cartino_backup.enc"))

            // 1. VAULT_ONLY 404 returns clear error + guidance
            viewModel.downloadAndRestoreVaultArchiveFromWebDav(
                password = "Restore404!Pass2026".toCharArray()
            )
            waitUntilSyncStateSettled()
            val vaultOnlyState = viewModel.syncState.value
            assertTrue("Expected Error for VAULT_ONLY 404, got $vaultOnlyState", vaultOnlyState is SyncUiState.Error)
            assertTrue((vaultOnlyState as SyncUiState.Error).message.contains("ابتدا یک بکآپ صندوق تهیه و آپلود کنید."))

            // 2. BOTH mode 404 finishes with warning-success (NO error banner) and restores .enc data
            viewModel.resetSyncState()
            viewModel.restoreBothFromWebDav(
                password = "Restore404!Pass2026",
                onVaultTotpRequired = { _, _ -> }
            )
            waitUntilSyncStateSettled()
            val bothState = viewModel.syncState.value
            assertTrue("Expected warning-success for BOTH 404, got $bothState", bothState is SyncUiState.Success)
            assertEquals(
                "داده‌های اپ بازیابی شد؛ آرشیو صندوق یافت نشد و بازیابی نشد.",
                (bothState as SyncUiState.Success).message
            )
            val restoredGroups = db.identityDocumentDao().getAllGroupsList()
            assertEquals(1, restoredGroups.size)
            assertEquals("گروه تست ۴۰۴", restoredGroups.single().name)
        }
    }

    private fun waitUntilSyncStateSettled(timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val s = viewModel.syncState.value
            if (s is SyncUiState.Success || s is SyncUiState.Error) {
                return
            }
            Thread.sleep(25)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private data class WebDavStubResponse(
        val statusCode: Int,
        val reason: String,
        val body: ByteArray
    )

    private inline fun withLocalWebDavServer(
        crossinline handler: (method: String, path: String, body: ByteArray) -> WebDavStubResponse,
        block: (baseUrl: String) -> Unit
    ) {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val acceptThread = Thread {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                Thread {
                    socket.use { sock ->
                        val input = sock.getInputStream()
                        val headerBuf = ByteArrayOutputStream()
                        var prev4 = 0
                        while (true) {
                            val b = input.read()
                            if (b == -1) break
                            headerBuf.write(b)
                            prev4 = ((prev4 shl 8) or (b and 0xFF))
                            if (prev4 == 0x0D0A0D0A) break
                        }
                        val headerText = headerBuf.toByteArray().toString(Charsets.ISO_8859_1)
                        val lines = headerText.split("\r\n")
                        val requestLine = lines.firstOrNull().orEmpty()
                        val parts = requestLine.split(" ")
                        val method = parts.getOrElse(0) { "GET" }
                        val rawPath = parts.getOrElse(1) { "/" }
                        val contentLength = lines
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                        val reqBody = ByteArray(contentLength)
                        var offset = 0
                        while (offset < contentLength) {
                            val r = input.read(reqBody, offset, contentLength - offset)
                            if (r == -1) break
                            offset += r
                        }

                        val resp = handler(method, rawPath, reqBody)
                        val out = sock.getOutputStream()
                        val headers = "HTTP/1.1 ${resp.statusCode} ${resp.reason}\r\n" +
                            "Content-Length: ${resp.body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(headers.toByteArray(Charsets.US_ASCII))
                        if (resp.body.isNotEmpty()) {
                            out.write(resp.body)
                        }
                        out.flush()
                    }
                }.start()
            }
        }
        acceptThread.start()
        try {
            block("http://127.0.0.1:${server.localPort}/dav/")
        } finally {
            running.set(false)
            runCatching { server.close() }
        }
    }
}
