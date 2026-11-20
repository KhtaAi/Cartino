package com.example

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultFile
import com.example.ui.CartinoViewModel
import com.example.ui.screens.VaultScreenContent
import com.example.ui.theme.CartinoTheme
import com.example.util.VaultStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultExportTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var application: Application
    private lateinit var viewModel: CartinoViewModel
    private lateinit var tempDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup() {
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            tempDir = File(application.cacheDir, "vault_export_test").apply {
                deleteRecursively()
                mkdirs()
            }
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            VaultStorageManager.getVaultDirectory(application)

            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            testSecretKey = keyGen.generateKey()
            VaultStorageManager.setTestKeyProviderForTesting { testSecretKey }

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            dao.getAllFilesList().forEach { dao.deleteFileById(it.id) }
            dao.getAllGroupsList().forEach { dao.deleteGroupById(it.id) }

            viewModel = CartinoViewModel(application)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            dao.getAllFilesList().forEach { dao.deleteFileById(it.id) }
            dao.getAllGroupsList().forEach { dao.deleteGroupById(it.id) }
            VaultStorageManager.setTestKeyProviderForTesting(null)
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            tempDir.deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun importThenExport_verifiesByteForByteEqualityAndSha256BeforeAndAfter() {
        runBlocking {
            // 150 KB binary payload spanning multiple 64 KB streaming chunks
            val originalBytes = Random(9981).nextBytes(150 * 1024 + 317)
            val expectedSha256 = sha256Hex(originalBytes)
            val sourceFile = File(tempDir, "keepass_keyfile.key").apply {
                writeBytes(originalBytes)
            }

            val importReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(sourceFile)),
                currentGroupId = null
            )
            assertEquals(1, importReport.importedCount)

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val storedRecord = dao.getAllFilesList().single()
            assertEquals(expectedSha256, storedRecord.sha256)

            val exportTargetFile = File(tempDir, "exported_keyfile.key")
            assertFalse(exportTargetFile.exists())

            val exportResult = viewModel.exportVaultFileInternal(
                file = storedRecord,
                targetUri = Uri.fromFile(exportTargetFile)
            )

            assertTrue(exportResult.success)
            assertNull(exportResult.errorMessage)
            assertEquals(expectedSha256, exportResult.sha256Before)
            assertEquals(expectedSha256, exportResult.sha256After)
            assertTrue(exportTargetFile.exists())
            assertArrayEquals(originalBytes, exportTargetFile.readBytes())
        }
    }

    @Test
    fun exportTamperedEncryptedFile_failsWithIntegrityErrorWithoutWritingOutput() {
        runBlocking {
            val originalBytes = "Sensitive secret document bytes for integrity check".toByteArray(Charsets.UTF_8)
            val sourceFile = File(tempDir, "secret.pdf").apply { writeBytes(originalBytes) }

            val importReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(sourceFile)),
                currentGroupId = null
            )
            assertEquals(1, importReport.importedCount)

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val storedRecord = dao.getAllFilesList().single()

            // Tamper 1 byte inside encrypted vault file on disk
            val encryptedDiskFile = File(VaultStorageManager.getVaultDirectory(application), storedRecord.storageName)
            assertTrue(encryptedDiskFile.exists())
            val diskBytes = encryptedDiskFile.readBytes()
            val tamperIndex = diskBytes.size / 2
            diskBytes[tamperIndex] = (diskBytes[tamperIndex].toInt() xor 0xFF).toByte()
            encryptedDiskFile.writeBytes(diskBytes)

            val targetOutput = File(tempDir, "should_not_exist.pdf")
            val result = viewModel.exportVaultFileInternal(
                file = storedRecord,
                targetUri = Uri.fromFile(targetOutput)
            )

            assertFalse(result.success)
            assertNotNull(result.errorMessage)
            assertTrue(result.errorMessage!!.contains("یکپارچگی"))
            assertTrue(result.errorMessage!!.contains("تأیید نشد"))
            assertFalse(targetOutput.exists())
        }
    }

    @Test
    fun exportWithMismatchedStoredSha256_stopsBeforeWritingToTargetUri() {
        runBlocking {
            val originalBytes = "Plaintext content".toByteArray(Charsets.UTF_8)
            val actualSha = sha256Hex(originalBytes)
            val sourceFile = File(tempDir, "note.txt").apply { writeBytes(originalBytes) }

            viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(sourceFile)),
                currentGroupId = null
            )

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val stored = dao.getAllFilesList().single()
            // Corrupt stored hash metadata in DB
            dao.updateFile(stored.copy(sha256 = "a".repeat(64)))

            val targetOutput = File(tempDir, "mismatch_out.txt")
            val result = viewModel.exportVaultFileInternal(
                file = stored.copy(sha256 = "a".repeat(64)),
                targetUri = Uri.fromFile(targetOutput)
            )

            assertFalse(result.success)
            assertEquals(actualSha, result.sha256Before)
            assertEquals("", result.sha256After)
            assertEquals("یکپارچگی فایل تأیید نشد", result.errorMessage)
            assertFalse(targetOutput.exists())
        }
    }

    @Test
    fun clickingExportInDetailsSheet_showsWarningDialogAndConfirmLaunchesCreateDocument() {
        val sampleFile = VaultFile(
            id = "f-exp-1",
            groupId = null,
            displayName = "کلید رمزنگاری",
            originalFileName = "my_master.key",
            mimeType = "application/octet-stream",
            sizeBytes = 2048L,
            sha256 = "1234567890abcdef".repeat(4),
            storageName = "f-exp-1.vault",
            notes = ""
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = emptyList(),
                        files = listOf(sampleFile),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        // Open file details sheet
        composeTestRule.onNodeWithTag("vault_file_f-exp-1").performClick()
        composeTestRule.waitForIdle()

        // Click Export button in sheet header
        composeTestRule.onNodeWithTag("vault_file_export_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Verify red security warning dialog copy
        composeTestRule.onNodeWithText("هشدار امنیتی خروجی فایل").assertExists()
        composeTestRule.onNodeWithText("رمزنگاری‌شده نیست", substring = true).assertExists()
        composeTestRule.onNodeWithText("keyfile", substring = true).assertExists()
        composeTestRule.onNodeWithText("جای امن نگهداری", substring = true).assertExists()

        // Confirm export -> should close sheet & launch SAF CreateDocument intent
        composeTestRule.onNodeWithTag("confirm_export_vault_file").performClick()
        composeTestRule.waitForIdle()

        val started = Shadows.shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals("application/octet-stream", started.intent.type)
        assertEquals("my_master.key", started.intent.getStringExtra(Intent.EXTRA_TITLE))
    }
}
