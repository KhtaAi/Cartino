package com.example

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.ui.screens.VaultScreenContent
import com.example.ui.theme.CartinoTheme
import com.example.util.VaultStorageManager
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultZipExportTest {

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
            tempDir = File(application.cacheDir, "vault_zip_export_test").apply {
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

    @Test
    fun exportNestedGroupTree_createsAes256EncryptedZipWithRelativePathsAndDisplayNames() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val rootGroup = VaultGroup(id = "grp-root", name = "اسناد مهم", parentGroupId = null)
            val childGroup = VaultGroup(id = "grp-child", name = "بانکی", parentGroupId = "grp-root")
            val grandChildGroup = VaultGroup(id = "grp-grand", name = "کلیدها", parentGroupId = "grp-child")
            dao.insertGroup(rootGroup)
            dao.insertGroup(childGroup)
            dao.insertGroup(grandChildGroup)

            val rootBytes = "Root document bytes".toByteArray(Charsets.UTF_8)
            val childBytes = Random(4201).nextBytes(140 * 1024 + 19)
            val grandBytes = "Grandchild key payload".toByteArray(Charsets.UTF_8)

            val fRoot = File(tempDir, "raw_root_1.txt").apply { writeBytes(rootBytes) }
            val fChild = File(tempDir, "raw_child_2.bin").apply { writeBytes(childBytes) }
            val fGrand = File(tempDir, "raw_grand_3.key").apply { writeBytes(grandBytes) }

            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(fRoot)), currentGroupId = rootGroup.id)
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(fChild)), currentGroupId = childGroup.id)
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(fGrand)), currentGroupId = grandChildGroup.id)

            // Rename displayName cleanly to verify ZIP entries use displayName rather than originalFileName
            val storedFiles = dao.getAllFilesList()
            val rootRecord = storedFiles.single { it.groupId == rootGroup.id }
            val childRecord = storedFiles.single { it.groupId == childGroup.id }
            val grandRecord = storedFiles.single { it.groupId == grandChildGroup.id }
            dao.updateFile(rootRecord.copy(displayName = "summary.txt"))
            dao.updateFile(childRecord.copy(displayName = "statement.bin"))
            dao.updateFile(grandRecord.copy(displayName = "master.key"))

            val outZip = File(tempDir, "exported_tree.zip")
            val zipPassword = "Strong!VaultZip#2026".toCharArray()

            val result = viewModel.exportVaultGroupAsZipInternal(
                groupId = rootGroup.id,
                targetUri = Uri.fromFile(outZip),
                zipPassword = zipPassword
            )

            assertTrue(result.success)
            assertEquals(3, result.totalFiles)
            assertTrue(outZip.exists())

            val zipReader = ZipFile(outZip, zipPassword)
            assertTrue(zipReader.isValidZipFile)
            assertTrue(zipReader.isEncrypted)

            val headers = zipReader.fileHeaders.filter { !it.isDirectory }
            assertEquals(3, headers.size)
            val headerNames = headers.map { it.fileName }.toSet()
            assertEquals(
                setOf(
                    "summary.txt",
                    "بانکی/statement.bin",
                    "بانکی/کلیدها/master.key"
                ),
                headerNames
            )
            headers.forEach { header ->
                assertTrue(header.isEncrypted)
                assertEquals(EncryptionMethod.AES, header.encryptionMethod)
                assertEquals(AesKeyStrength.KEY_STRENGTH_256, header.aesExtraDataRecord.aesKeyStrength)
            }

            val extractDir = File(tempDir, "extracted_tree").apply { mkdirs() }
            zipReader.extractAll(extractDir.absolutePath)

            assertArrayEquals(rootBytes, File(extractDir, "summary.txt").readBytes())
            assertArrayEquals(childBytes, File(extractDir, "بانکی/statement.bin").readBytes())
            assertArrayEquals(grandBytes, File(extractDir, "بانکی/کلیدها/master.key").readBytes())
        }
    }

    @Test
    fun exportGroupWithDuplicateDisplayNames_disambiguatesPerFolderIndependently() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val rootGroup = VaultGroup(id = "grp-dup-root", name = "KeysRoot", parentGroupId = null)
            val subGroup = VaultGroup(id = "grp-dup-sub", name = "SubKeys", parentGroupId = "grp-dup-root")
            dao.insertGroup(rootGroup)
            dao.insertGroup(subGroup)

            val payload1 = "First root keyfile".toByteArray(Charsets.UTF_8)
            val payload2 = "Second root keyfile".toByteArray(Charsets.UTF_8)
            val payload3 = "Third root keyfile".toByteArray(Charsets.UTF_8)
            val payloadSub = "Child folder keyfile".toByteArray(Charsets.UTF_8)

            val src1 = File(tempDir, "a1.key").apply { writeBytes(payload1) }
            val src2 = File(tempDir, "a2.key").apply { writeBytes(payload2) }
            val src3 = File(tempDir, "a3.key").apply { writeBytes(payload3) }
            val srcSub = File(tempDir, "sub.key").apply { writeBytes(payloadSub) }

            viewModel.importVaultFilesInternal(
                listOf(Uri.fromFile(src1), Uri.fromFile(src2), Uri.fromFile(src3)),
                currentGroupId = rootGroup.id
            )
            viewModel.importVaultFilesInternal(
                listOf(Uri.fromFile(srcSub)),
                currentGroupId = subGroup.id
            )

            // Assign identical displayName across root files (and in child group)
            val allFiles = dao.getAllFilesList()
            allFiles.forEach { file ->
                dao.updateFile(file.copy(displayName = "keyfile.key"))
            }

            val outZip = File(tempDir, "collisions.zip")
            val password = "CollisionTest#Password99".toCharArray()
            val result = viewModel.exportVaultGroupAsZipInternal(
                groupId = rootGroup.id,
                targetUri = Uri.fromFile(outZip),
                zipPassword = password
            )

            assertTrue(result.success)
            assertEquals(4, result.totalFiles)

            val zipReader = ZipFile(outZip, password)
            val entryNames = zipReader.fileHeaders.filter { !it.isDirectory }.map { it.fileName }.toSet()
            assertEquals(
                setOf(
                    "keyfile.key",
                    "keyfile (1).key",
                    "keyfile (2).key",
                    "SubKeys/keyfile.key"
                ),
                entryNames
            )

            val extractDir = File(tempDir, "extracted_collisions").apply { mkdirs() }
            zipReader.extractAll(extractDir.absolutePath)
            val extractedRootPayloads = setOf(
                File(extractDir, "keyfile.key").readBytes().decodeToString(),
                File(extractDir, "keyfile (1).key").readBytes().decodeToString(),
                File(extractDir, "keyfile (2).key").readBytes().decodeToString()
            )
            assertEquals(
                setOf("First root keyfile", "Second root keyfile", "Third root keyfile"),
                extractedRootPayloads
            )
            assertArrayEquals(payloadSub, File(extractDir, "SubKeys/keyfile.key").readBytes())
        }
    }

    @Test
    fun extractingEncryptedZipWithWrongPassword_failsWithZipException() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val rootGroup = VaultGroup(id = "grp-wrong-pwd", name = "Private", parentGroupId = null)
            dao.insertGroup(rootGroup)

            val src = File(tempDir, "secret.txt").apply {
                writeBytes("Top secret vault content".toByteArray(Charsets.UTF_8))
            }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = rootGroup.id)

            val outZip = File(tempDir, "locked.zip")
            val exportResult = viewModel.exportVaultGroupAsZipInternal(
                groupId = rootGroup.id,
                targetUri = Uri.fromFile(outZip),
                zipPassword = "Correct!Password#1234".toCharArray()
            )
            assertTrue(exportResult.success)

            val wrongZipReader = ZipFile(outZip, "Wrong!Password#9999".toCharArray())
            val failDir = File(tempDir, "wrong_pwd_extract").apply { mkdirs() }
            assertThrows(ZipException::class.java) {
                wrongZipReader.extractAll(failDir.absolutePath)
            }
            assertFalse(File(failDir, "secret.txt").exists())
        }
    }

    @Test
    fun zipExportDialog_validatesPasswordLengthAndMatchAndLaunchesCreateDocument() {
        val group = VaultGroup(id = "grp-ui-zip", name = "قراردادها", parentGroupId = null)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = listOf(group),
                        files = emptyList(),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        // Open group 3-dot menu and click 'خروجی ZIP گروه'
        composeTestRule.onNodeWithTag("vault_group_menu_grp-ui-zip").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_group_export_zip_grp-ui-zip").performClick()
        composeTestRule.waitForIdle()

        // Verify dialog title & explicit red PBKDF2-SHA1 warning
        composeTestRule.onNodeWithText("رمز فایل ZIP").assertExists()
        composeTestRule.onNodeWithText("PBKDF2-SHA1", substring = true).assertExists()
        composeTestRule.onNodeWithText("۱۰۰۰", substring = true).assertExists()
        composeTestRule.onNodeWithText("Argon2id", substring = true).assertExists()
        composeTestRule.onNodeWithText("۷-Zip", substring = true).assertExists()

        // Initially disabled
        composeTestRule.onNodeWithTag("confirm_vault_zip_export").assertIsNotEnabled()

        // Short password (< 12 chars) keeps button disabled
        composeTestRule.onNodeWithTag("vault_zip_password_input").performScrollTo().performTextInput("short123")
        composeTestRule.onNodeWithTag("vault_zip_confirm_password_input").performScrollTo().performTextInput("short123")
        composeTestRule.onNodeWithTag("confirm_vault_zip_export").assertIsNotEnabled()

        // >= 12 chars but mismatched confirm keeps button disabled
        composeTestRule.onNodeWithTag("vault_zip_password_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("vault_zip_password_input").performTextInput("LongSecurePass#2026")
        composeTestRule.onNodeWithTag("confirm_vault_zip_export").assertIsNotEnabled()

        // Matching >= 12 chars enables Confirm button
        composeTestRule.onNodeWithTag("vault_zip_confirm_password_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("vault_zip_confirm_password_input").performTextInput("LongSecurePass#2026")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_vault_zip_export").assertIsEnabled()

        // Click Continue & Export -> launches SAF CreateDocument for application/zip
        composeTestRule.onNodeWithTag("confirm_vault_zip_export").performClick()
        composeTestRule.waitForIdle()

        val started = Shadows.shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals("application/zip", started.intent.type)
        assertEquals("قراردادها.zip", started.intent.getStringExtra(Intent.EXTRA_TITLE))
    }
}
