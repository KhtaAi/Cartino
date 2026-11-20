package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.ui.VaultDuplicateAction
import com.example.ui.VaultDuplicateCandidate
import com.example.ui.screens.VaultScreenContent
import com.example.ui.theme.CartinoTheme
import com.example.util.VaultStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultImportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: CartinoViewModel
    private lateinit var sourceTempDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup() {
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            sourceTempDir = File(application.cacheDir, "vault_import_sources").apply {
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
            sourceTempDir.deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun importVaultFiles_multipleUris_insertsWithCorrectSha256GroupIdAndOriginalName() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val targetGroup = VaultGroup(id = "grp-contracts", name = "قراردادها", parentGroupId = null)
            dao.insertGroup(targetGroup)

            val bytes1 = "Contract PDF content #1".toByteArray(Charsets.UTF_8)
            val bytes2 = ByteArray(4096) { idx -> (idx % 251).toByte() }
            val file1 = File(sourceTempDir, "contract_1404.pdf").apply { writeBytes(bytes1) }
            val file2 = File(sourceTempDir, "scan_photo.bin").apply { writeBytes(bytes2) }

            val report = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(file1), Uri.fromFile(file2)),
                currentGroupId = targetGroup.id
            )

            assertEquals(2, report.importedCount)
            assertEquals(0, report.duplicateSkippedCount)
            assertEquals(0, report.rejectedCount)
            assertEquals((bytes1.size + bytes2.size).toLong(), report.totalImportedBytes)

            val storedFiles = dao.getFilesListByGroupId(targetGroup.id)
            assertEquals(2, storedFiles.size)

            val stored1 = storedFiles.first { it.originalFileName == "contract_1404.pdf" }
            assertEquals(targetGroup.id, stored1.groupId)
            assertEquals("contract_1404.pdf", stored1.displayName)
            assertEquals(sha256Hex(bytes1), stored1.sha256)
            assertEquals(bytes1.size.toLong(), stored1.sizeBytes)

            val stored2 = storedFiles.first { it.originalFileName == "scan_photo.bin" }
            assertEquals(targetGroup.id, stored2.groupId)
            assertEquals(sha256Hex(bytes2), stored2.sha256)

            val decrypted1 = VaultStorageManager.openDecryptedInputStream(application, stored1.storageName)
                .use { it.readBytes() }
            assertArrayEquals(bytes1, decrypted1)
        }
    }

    @Test
    fun importVaultFiles_duplicateInSameGroup_skippedByDefaultAndKeptWhenUserChoosesKeep() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val groupA = VaultGroup(id = "grp-a", name = "گروه الف", parentGroupId = null)
            val groupB = VaultGroup(id = "grp-b", name = "گروه ب", parentGroupId = null)
            dao.insertGroup(groupA)
            dao.insertGroup(groupB)

            val payload = "Identical secret document payload".toByteArray(Charsets.UTF_8)
            val source1 = File(sourceTempDir, "doc_first.txt").apply { writeBytes(payload) }
            val source2 = File(sourceTempDir, "doc_copy.txt").apply { writeBytes(payload) }

            // First import into groupA succeeds
            val firstReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(source1)),
                currentGroupId = groupA.id
            )
            assertEquals(1, firstReport.importedCount)
            val vaultDir = VaultStorageManager.getVaultDirectory(application)
            assertEquals(1, vaultDir.listFiles()?.count { it.isFile } ?: 0)

            // Second import into same groupA without prompt defaults to SKIP and cleans up temp encrypted copy
            val skippedReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(source2)),
                currentGroupId = groupA.id,
                onDuplicatePrompt = null
            )
            assertEquals(0, skippedReport.importedCount)
            assertEquals(1, skippedReport.duplicateSkippedCount)
            assertEquals(1, dao.getFilesListByGroupId(groupA.id).size)
            assertEquals(1, vaultDir.listFiles()?.count { it.isFile } ?: 0)

            // Third import into same groupA where user explicitly selects KEEP
            var promptCount = 0
            val keptReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(source2)),
                currentGroupId = groupA.id,
                onDuplicatePrompt = { candidate ->
                    promptCount++
                    assertEquals(sha256Hex(payload), candidate.sha256)
                    VaultDuplicateAction.KEEP
                }
            )
            assertEquals(1, promptCount)
            assertEquals(1, keptReport.importedCount)
            assertEquals(1, keptReport.duplicateKeptCount)
            val filesInA = dao.getFilesListByGroupId(groupA.id)
            assertEquals(2, filesInA.size)
            assertNotEquals(filesInA[0].storageName, filesInA[1].storageName)

            // Importing the same bytes into groupB does NOT collide with groupA
            val otherGroupReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(source2)),
                currentGroupId = groupB.id
            )
            assertEquals(1, otherGroupReport.importedCount)
            assertEquals(0, otherGroupReport.duplicateSkippedCount)
        }
    }

    @Test
    fun importVaultFiles_fileLargerThan100MbReportedByContentResolver_isRejected() {
        runBlocking {
            val authority = "com.example.test.oversize.provider"
            val controller = Robolectric.buildContentProvider(OversizeFakeContentProvider::class.java)
                .create(authority)
            assertNotNull(controller.get())

            val fakeUri = Uri.parse("content://$authority/files/oversize")
            val report = viewModel.importVaultFilesInternal(
                uris = listOf(fakeUri),
                currentGroupId = null
            )

            assertEquals(0, report.importedCount)
            assertEquals(1, report.rejectedCount)
            assertTrue(report.messages.any { it.contains("100") })
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            assertEquals(0, dao.getTotalFileCount())
        }
    }

    @Test
    fun updateVaultFileMetadata_updatesNameNotesAndGroupPreservingStorageNameAndSha256() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val groupA = VaultGroup(id = "grp-src", name = "مبدا", parentGroupId = null)
            val groupB = VaultGroup(id = "grp-dst", name = "مقصد", parentGroupId = null)
            dao.insertGroup(groupA)
            dao.insertGroup(groupB)

            val rawBytes = "Encrypted note attachment".toByteArray(Charsets.UTF_8)
            val sourceFile = File(sourceTempDir, "initial.pdf").apply { writeBytes(rawBytes) }

            val importReport = viewModel.importVaultFilesInternal(
                uris = listOf(Uri.fromFile(sourceFile)),
                currentGroupId = groupA.id
            )
            assertEquals(1, importReport.importedCount)

            val originalRecord = dao.getFilesListByGroupId(groupA.id).single()
            val originalStorageName = originalRecord.storageName
            val originalSha256 = originalRecord.sha256

            // Move to groupB and update display name + notes
            val updated = viewModel.updateVaultFileMetadataInternal(
                fileId = originalRecord.id,
                newDisplayName = "نام جدید سند",
                newNotes = "یادداشت مهم صندوق",
                newGroupId = groupB.id
            )
            assertNotNull(updated)

            val fromDb = dao.getFileById(originalRecord.id)
            assertNotNull(fromDb)
            assertEquals("نام جدید سند", fromDb?.displayName)
            assertEquals("یادداشت مهم صندوق", fromDb?.notes)
            assertEquals(groupB.id, fromDb?.groupId)
            assertEquals(originalStorageName, fromDb?.storageName)
            assertEquals(originalSha256, fromDb?.sha256)
            assertEquals("initial.pdf", fromDb?.originalFileName)
            assertTrue(dao.getFilesListByGroupId(groupA.id).isEmpty())
            assertEquals(1, dao.getFilesListByGroupId(groupB.id).size)

            // Move back to root (null) without changing storageName or sha256
            viewModel.updateVaultFileMetadataInternal(
                fileId = originalRecord.id,
                newDisplayName = "سند در ریشه",
                newNotes = "",
                newGroupId = null
            )
            val atRoot = dao.getFileById(originalRecord.id)
            assertNull(atRoot?.groupId)
            assertEquals(originalStorageName, atRoot?.storageName)
            assertEquals(originalSha256, atRoot?.sha256)
        }
    }

    @Test
    fun clickingVaultFileOpensDetailsSheetAndSavesUpdatedMetadataOrDeletes() {
        val group1 = VaultGroup(id = "g1", name = "مالی", parentGroupId = null)
        val group2 = VaultGroup(id = "g2", name = "بیمه", parentGroupId = "g1")
        val sampleFile = VaultFile(
            id = "f-ui-1",
            groupId = "g1",
            displayName = "رسید قدیمی",
            originalFileName = "old_receipt.pdf",
            mimeType = "application/pdf",
            sizeBytes = 4096L,
            sha256 = "abcdef0123456789".repeat(4),
            storageName = "sample.vault",
            notes = ""
        )

        var savedId: String? = null
        var savedName: String? = null
        var savedNotes: String? = null
        var savedGroupId: String? = "UNCHANGED"
        var deletedFile: VaultFile? = null

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = listOf(group1, group2),
                        files = listOf(sampleFile),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> },
                        onUpdateFileMetadata = { id, name, notes, grpId ->
                            savedId = id
                            savedName = name
                            savedNotes = notes
                            savedGroupId = grpId
                        },
                        onDeleteFile = { deletedFile = it }
                    )
                }
            }
        }

        // Navigate into g1 and click file card to open details sheet
        composeTestRule.onNodeWithTag("vault_group_g1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_file_f-ui-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("جزئیات فایل").assertExists()
        composeTestRule.onNodeWithTag("copy_vault_file_sha256").assertExists()

        // Edit display name, notes, and move to nested group g2
        composeTestRule.onNodeWithTag("vault_file_display_name_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("vault_file_display_name_input").performTextInput("بیمه‌نامه جدید")
        composeTestRule.onNodeWithTag("vault_file_notes_input").performScrollTo().performTextInput("تمدید سالانه")
        composeTestRule.onNodeWithTag("vault_file_group_selector").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_file_group_option_g2").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_file_save_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("f-ui-1", savedId)
        assertEquals("بیمه‌نامه جدید", savedName)
        assertEquals("تمدید سالانه", savedNotes)
        assertEquals("g2", savedGroupId)

        // Re-open details sheet and delete single file via red confirm dialog
        composeTestRule.onNodeWithTag("vault_file_f-ui-1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_file_delete_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_delete_vault_file").performClick()
        composeTestRule.waitForIdle()
        assertEquals("f-ui-1", deletedFile?.id)
    }

    @Test
    fun duplicatePromptDialogShowsQuestionAndResolvesSkipOrKeep() {
        val candidate = VaultDuplicateCandidate(
            fileName = "new_copy.pdf",
            sha256 = "0".repeat(64),
            sizeBytes = 1024L,
            existingFile = VaultFile(
                id = "existing-1",
                groupId = null,
                displayName = "existing.pdf",
                originalFileName = "existing.pdf",
                sizeBytes = 1024L,
                sha256 = "0".repeat(64),
                storageName = "existing.vault"
            )
        )
        var resolvedAction: VaultDuplicateAction? = null

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = emptyList(),
                        files = emptyList(),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> },
                        pendingDuplicateCandidate = candidate,
                        onResolveDuplicate = { resolvedAction = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("فایل تکراری یافت شد. رد شود یا نگهداری شود؟", substring = true).assertExists()
        composeTestRule.onNodeWithTag("duplicate_vault_file_skip").performClick()
        assertEquals(VaultDuplicateAction.SKIP, resolvedAction)
    }

    class OversizeFakeContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val cursor = MatrixCursor(cols)
            val row = cols.map<String, Any?> { col ->
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> "huge_backup.bin"
                    OpenableColumns.SIZE -> 101L * 1024L * 1024L
                    else -> null
                }
            }.toTypedArray()
            cursor.addRow(row)
            return cursor
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
