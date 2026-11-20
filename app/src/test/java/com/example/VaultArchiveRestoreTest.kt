package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.ui.MergeStrategy
import com.example.util.TotpManager
import com.example.util.VaultArchiveManager
import com.example.util.VaultStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultArchiveRestoreTest {

    private lateinit var application: Application
    private lateinit var viewModel: CartinoViewModel
    private lateinit var tempDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup() {
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            tempDir = File(application.cacheDir, "vault_archive_restore_test").apply {
                deleteRecursively()
                mkdirs()
            }
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            VaultStorageManager.getVaultDirectory(application)

            application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("last_vault_backup_timestamp")
                .commit()

            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            testSecretKey = keyGen.generateKey()
            VaultStorageManager.setTestKeyProviderForTesting { testSecretKey }

            clearVaultDbAndDisk()
            viewModel = CartinoViewModel(application)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            clearVaultDbAndDisk()
            VaultStorageManager.setTestKeyProviderForTesting(null)
            VaultArchiveManager.setTestTotpConfigProviderForTesting(null)
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            tempDir.deleteRecursively()
        }
    }

    private suspend fun clearVaultDbAndDisk() {
        val dao = CartinoDatabase.getDatabase(application).vaultDao()
        dao.getAllFilesList().forEach { dao.deleteFileById(it.id) }
        dao.getAllGroupsList().forEach { dao.deleteGroupById(it.id) }
        VaultStorageManager.getVaultDirectory(application).listFiles()?.forEach { it.delete() }
    }

    private fun readDecryptedBytes(storageName: String): ByteArray {
        return VaultStorageManager.openDecryptedInputStream(application, storageName).use { it.readBytes() }
    }

    @Test
    fun roundTripRestoreIntoEmptyDatabase_reconstructsNestedGroupsAndExactBytes() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val gRoot = VaultGroup(id = "orig-root", name = "اسناد", parentGroupId = null, createdAt = 100L)
            val gChild = VaultGroup(id = "orig-child", name = "بانکی", parentGroupId = "orig-root", createdAt = 200L)
            val gGrand = VaultGroup(id = "orig-grand", name = "رسیدها", parentGroupId = "orig-child", createdAt = 300L)
            dao.insertGroup(gRoot)
            dao.insertGroup(gChild)
            dao.insertGroup(gGrand)

            val payloadRoot = "Root plain text".toByteArray(Charsets.UTF_8)
            val payloadChild = Random(12345).nextBytes(175 * 1024 + 13)
            val payloadGrand = "Receipt #991".toByteArray(Charsets.UTF_8)

            val f1 = File(tempDir, "root_note.txt").apply { writeBytes(payloadRoot) }
            val f2 = File(tempDir, "stmt.bin").apply { writeBytes(payloadChild) }
            val f3 = File(tempDir, "receipt.pdf").apply { writeBytes(payloadGrand) }

            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(f1)), currentGroupId = gRoot.id)
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(f2)), currentGroupId = gChild.id)
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(f3)), currentGroupId = gGrand.id)

            val allOrigFiles = dao.getAllFilesList()
            val childOrig = allOrigFiles.single { it.groupId == gChild.id }
            dao.updateFile(childOrig.copy(displayName = "گزارش حساب", notes = "یادداشت ویژه"))

            val archiveFile = File(tempDir, "roundtrip.cvault")
            val password = "RestoreTest!StrongPass2026".toCharArray()
            val archiveResult = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = password
            )
            assertTrue(archiveResult.success)

            // Wipe DB & encrypted disk files completely
            clearVaultDbAndDisk()
            assertEquals(0, dao.getAllGroupsList().size)
            assertEquals(0, dao.getAllFilesList().size)

            // Restore into empty DB
            val progressSteps = mutableListOf<Pair<Int, Int>>()
            val report = viewModel.importVaultArchiveInternal(
                sourceUri = Uri.fromFile(archiveFile),
                password = "RestoreTest!StrongPass2026".toCharArray(),
                totpCode = null,
                mergeStrategy = MergeStrategy.ASK_USER,
                onProgress = { cur, tot -> progressSteps.add(cur to tot) }
            )

            assertEquals(3, report.totalGroups)
            assertEquals(3, report.newGroups)
            assertEquals(0, report.reusedGroups)
            assertEquals(3, report.totalFiles)
            assertEquals(3, report.newFiles)
            assertEquals(0, report.skippedFiles)
            assertEquals(0, report.replacedFiles)
            assertEquals(0, report.renamedFiles)
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progressSteps)

            val restoredGroups = dao.getAllGroupsList()
            assertEquals(3, restoredGroups.size)
            val restoredRoot = restoredGroups.single { it.name == "اسناد" }
            val restoredChild = restoredGroups.single { it.name == "بانکی" }
            val restoredGrand = restoredGroups.single { it.name == "رسیدها" }

            assertNull(restoredRoot.parentGroupId)
            assertEquals(restoredRoot.id, restoredChild.parentGroupId)
            assertEquals(restoredChild.id, restoredGrand.parentGroupId)
            assertNotEquals("orig-root", restoredRoot.id)

            val restoredFiles = dao.getAllFilesList()
            assertEquals(3, restoredFiles.size)

            val restoredRootFile = restoredFiles.single { it.groupId == restoredRoot.id }
            val restoredChildFile = restoredFiles.single { it.groupId == restoredChild.id }
            val restoredGrandFile = restoredFiles.single { it.groupId == restoredGrand.id }

            assertEquals("گزارش حساب", restoredChildFile.displayName)
            assertEquals("یادداشت ویژه", restoredChildFile.notes)
            assertArrayEquals(payloadRoot, readDecryptedBytes(restoredRootFile.storageName))
            assertArrayEquals(payloadChild, readDecryptedBytes(restoredChildFile.storageName))
            assertArrayEquals(payloadGrand, readDecryptedBytes(restoredGrandFile.storageName))

            val prefs = application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
            assertTrue(prefs.getLong("last_vault_backup_timestamp", 0L) > 0L)
        }
    }

    private suspend fun prepareArchiveForMergeStrategies(): Pair<File, ByteArray> {
        val dao = CartinoDatabase.getDatabase(application).vaultDao()
        val grp = VaultGroup(id = "arc-grp-1", name = "قراردادها", parentGroupId = null)
        dao.insertGroup(grp)

        val newContractBytes = "ARCHIVE_CONTRACT_NEW_BYTES".toByteArray(Charsets.UTF_8)
        val newInvoiceBytes = "ARCHIVE_INVOICE_BYTES".toByteArray(Charsets.UTF_8)
        val fContract = File(tempDir, "contract.pdf").apply { writeBytes(newContractBytes) }
        val fInvoice = File(tempDir, "invoice.pdf").apply { writeBytes(newInvoiceBytes) }

        viewModel.importVaultFilesInternal(
            listOf(Uri.fromFile(fContract), Uri.fromFile(fInvoice)),
            currentGroupId = grp.id
        )

        val archiveFile = File(tempDir, "merge_test.cvault")
        val res = VaultArchiveManager.createVaultArchive(
            context = application,
            targetUri = Uri.fromFile(archiveFile),
            password = "MergeStrategy#Pass2026".toCharArray()
        )
        assertTrue(res.success)

        // Reset DB to have same group + conflicting "contract.pdf" with DIFFERENT old content
        clearVaultDbAndDisk()
        val existingGrp = VaultGroup(id = "db-grp-existing", name = "قراردادها", parentGroupId = null)
        dao.insertGroup(existingGrp)
        val oldContractBytes = "EXISTING_DB_CONTRACT_OLD_BYTES".toByteArray(Charsets.UTF_8)
        val fOld = File(tempDir, "contract.pdf").apply { writeBytes(oldContractBytes) }
        viewModel.importVaultFilesInternal(listOf(Uri.fromFile(fOld)), currentGroupId = existingGrp.id)

        return archiveFile to newContractBytes
    }

    @Test
    fun mergeSkipDuplicates_keepsExistingFileDeletesUnusedStagedAndAddsNewFile() {
        runBlocking {
            val (archiveFile, _) = prepareArchiveForMergeStrategies()
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val oldExistingFile = dao.getAllFilesList().single()

            val report = viewModel.importVaultArchiveInternal(
                sourceUri = Uri.fromFile(archiveFile),
                password = "MergeStrategy#Pass2026".toCharArray(),
                totpCode = null,
                mergeStrategy = MergeStrategy.SKIP_DUPLICATES
            )

            assertEquals(1, report.reusedGroups)
            assertEquals(0, report.newGroups)
            assertEquals(1, report.newFiles)
            assertEquals(1, report.skippedFiles)
            assertEquals(0, report.replacedFiles)
            assertEquals(0, report.renamedFiles)

            val files = dao.getAllFilesList()
            assertEquals(2, files.size)
            val keptContract = files.single { it.displayName == "contract.pdf" }
            assertEquals(oldExistingFile.id, keptContract.id)
            assertEquals("EXISTING_DB_CONTRACT_OLD_BYTES", readDecryptedBytes(keptContract.storageName).decodeToString())
            assertTrue(files.any { it.displayName == "invoice.pdf" })

            // Verify zero orphan files on disk
            val diskFiles = VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty()
                .filter { it.isFile }
            assertEquals(2, diskFiles.size)
        }
    }

    @Test
    fun mergeReplaceDuplicates_deletesOldFileAndStoresArchiveVersion() {
        runBlocking {
            val (archiveFile, newContractBytes) = prepareArchiveForMergeStrategies()
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val oldStorageName = dao.getAllFilesList().single().storageName

            val report = viewModel.importVaultArchiveInternal(
                sourceUri = Uri.fromFile(archiveFile),
                password = "MergeStrategy#Pass2026".toCharArray(),
                totpCode = null,
                mergeStrategy = MergeStrategy.REPLACE_DUPLICATES
            )

            assertEquals(1, report.reusedGroups)
            assertEquals(1, report.newFiles)
            assertEquals(1, report.replacedFiles)
            assertEquals(0, report.skippedFiles)
            assertEquals(0, report.renamedFiles)

            val files = dao.getAllFilesList()
            assertEquals(2, files.size)
            val replaced = files.single { it.displayName == "contract.pdf" }
            assertArrayEquals(newContractBytes, readDecryptedBytes(replaced.storageName))
            assertFalse(VaultStorageManager.encryptedFileExists(application, oldStorageName))

            val diskFiles = VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty()
                .filter { it.isFile }
            assertEquals(2, diskFiles.size)
        }
    }

    @Test
    fun mergeRenameDuplicates_keepsOldAndImportsDuplicateWithNumberSuffix() {
        runBlocking {
            val (archiveFile, newContractBytes) = prepareArchiveForMergeStrategies()
            val dao = CartinoDatabase.getDatabase(application).vaultDao()

            val report = viewModel.importVaultArchiveInternal(
                sourceUri = Uri.fromFile(archiveFile),
                password = "MergeStrategy#Pass2026".toCharArray(),
                totpCode = null,
                mergeStrategy = MergeStrategy.RENAME_DUPLICATES
            )

            assertEquals(1, report.reusedGroups)
            assertEquals(1, report.newFiles)
            assertEquals(1, report.renamedFiles)
            assertEquals(0, report.skippedFiles)
            assertEquals(0, report.replacedFiles)

            val files = dao.getAllFilesList()
            assertEquals(3, files.size)

            val original = files.single { it.displayName == "contract.pdf" }
            val renamed = files.single { it.displayName == "contract (1).pdf" }
            val invoice = files.single { it.displayName == "invoice.pdf" }

            assertEquals("EXISTING_DB_CONTRACT_OLD_BYTES", readDecryptedBytes(original.storageName).decodeToString())
            assertArrayEquals(newContractBytes, readDecryptedBytes(renamed.storageName))
            assertEquals("ARCHIVE_INVOICE_BYTES", readDecryptedBytes(invoice.storageName).decodeToString())
        }
    }

    @Test
    fun mergeAskUserWithDuplicatesAndNoCallback_throwsAndLeavesDatabaseUntouched() {
        runBlocking {
            val (archiveFile, _) = prepareArchiveForMergeStrategies()
            val dao = CartinoDatabase.getDatabase(application).vaultDao()

            val err = runCatching {
                viewModel.importVaultArchiveInternal(
                    sourceUri = Uri.fromFile(archiveFile),
                    password = "MergeStrategy#Pass2026".toCharArray(),
                    totpCode = null,
                    mergeStrategy = MergeStrategy.ASK_USER
                )
            }.exceptionOrNull()

            assertNotNull(err)
            assertEquals(1, dao.getAllFilesList().size)
            val diskFiles = VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty()
                .filter { it.isFile }
            assertEquals(1, diskFiles.size)
        }
    }

    @Test
    fun restoreTamperedArchiveFile_failsSha256IntegrityAndRollsBackStagedFiles() {
        runBlocking {
            val src1 = File(tempDir, "first.txt").apply { writeBytes("Valid first file".toByteArray()) }
            val src2 = File(tempDir, "second.txt").apply {
                writeBytes(ByteArray(32 * 1024) { (it % 251).toByte() })
            }
            viewModel.importVaultFilesInternal(
                listOf(Uri.fromFile(src1), Uri.fromFile(src2)),
                currentGroupId = null
            )

            val archiveFile = File(tempDir, "tamper.cvault")
            val archiveRes = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "Integrity#CheckPass2026".toCharArray()
            )
            assertTrue(archiveRes.success)

            clearVaultDbAndDisk()

            // Flip 1 ciphertext byte inside the file payload area (well before the last 16-byte GCM tag)
            val bytes = archiveFile.readBytes()
            val tamperOffset = bytes.size - 1024
            assertTrue(tamperOffset > 45)
            bytes[tamperOffset] = (bytes[tamperOffset].toInt() xor 0x01).toByte()
            archiveFile.writeBytes(bytes)

            val restoreResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "Integrity#CheckPass2026".toCharArray(),
                totpCode = null
            )

            assertFalse(restoreResult.success)
            assertNotNull(restoreResult.errorMessage)
            assertTrue(restoreResult.errorMessage!!.contains("یکپارچگی"))
            assertTrue(restoreResult.errorMessage!!.contains("تأیید نشد"))

            // First staged file must have been rolled back
            val diskFiles = VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty()
                .filter { it.isFile }
            assertEquals(0, diskFiles.size)
        }
    }

    @Test
    fun restoreArchiveWithMismatchedManifestSha256_stopsAndRollsBackStagedFiles() {
        runBlocking {
            val file1Bytes = "File one OK".toByteArray(Charsets.UTF_8)
            val file2Bytes = "File two corrupted digest".toByteArray(Charsets.UTF_8)
            val sha1 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file1Bytes).joinToString("") { "%02x".format(it) }

            val now = System.currentTimeMillis()
            val manifest = com.example.util.VaultArchiveManifest(
                timestamp = now,
                totpRequired = false,
                groups = emptyList(),
                files = listOf(
                    com.example.util.VaultArchiveFileMetadata(
                        id = "m-1",
                        groupId = null,
                        displayName = "first_valid.txt",
                        originalFileName = "first_valid.txt",
                        mimeType = "text/plain",
                        sizeBytes = file1Bytes.size.toLong(),
                        sha256 = sha1,
                        storageName = "s1.vault",
                        createdAt = now,
                        updatedAt = now
                    ),
                    com.example.util.VaultArchiveFileMetadata(
                        id = "m-2",
                        groupId = null,
                        displayName = "second_bad_hash.txt",
                        originalFileName = "second_bad_hash.txt",
                        mimeType = "text/plain",
                        sizeBytes = file2Bytes.size.toLong(),
                        sha256 = "f".repeat(64),
                        storageName = "s2.vault",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )

            val salt = ByteArray(16) { 0x11 }
            val iv = ByteArray(12) { 0x22 }
            val password = "ShaMismatch!Test2026".toCharArray()
            val key = VaultArchiveManager.deriveArchiveKey(password, salt, null)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, iv)
                )
            }
            val manifestBytes = VaultArchiveManager.manifestAdapter.toJson(manifest).toByteArray(Charsets.UTF_8)
            val gzOut = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(gzOut).use { it.write(manifestBytes) }
            val gzManifest = gzOut.toByteArray()

            val archiveFile = File(tempDir, "sha_mismatch.cvault")
            archiveFile.outputStream().use { rawOut ->
                rawOut.write("CARTINO_VAULT".toByteArray(Charsets.US_ASCII))
                rawOut.write(ByteBuffer.allocate(4).putInt(1).array())
                rawOut.write(salt)
                rawOut.write(iv)
                javax.crypto.CipherOutputStream(rawOut, cipher).use { cOut ->
                    cOut.write(ByteBuffer.allocate(4).putInt(gzManifest.size).array())
                    cOut.write(gzManifest)

                    val s1Name = "s1.vault".toByteArray(Charsets.UTF_8)
                    cOut.write(ByteBuffer.allocate(4).putInt(s1Name.size).array())
                    cOut.write(s1Name)
                    cOut.write(ByteBuffer.allocate(8).putLong(file1Bytes.size.toLong()).array())
                    cOut.write(file1Bytes)

                    val s2Name = "s2.vault".toByteArray(Charsets.UTF_8)
                    cOut.write(ByteBuffer.allocate(4).putInt(s2Name.size).array())
                    cOut.write(s2Name)
                    cOut.write(ByteBuffer.allocate(8).putLong(file2Bytes.size.toLong()).array())
                    cOut.write(file2Bytes)
                }
            }

            val restoreResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "ShaMismatch!Test2026".toCharArray(),
                totpCode = null
            )

            assertFalse(restoreResult.success)
            assertEquals("یکپارچگی فایل second_bad_hash.txt تأیید نشد", restoreResult.errorMessage)
            val diskFiles = VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty()
                .filter { it.isFile }
            assertEquals(0, diskFiles.size)
        }
    }

    @Test
    fun restoreWithWrongPassword_failsWithGcmTagErrorAndLeavesVaultClean() {
        runBlocking {
            val src = File(tempDir, "secret.txt").apply { writeBytes("Classified data".toByteArray()) }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = null)

            val archiveFile = File(tempDir, "wrong_pwd.cvault")
            val archiveRes = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "RightPassword!2026".toCharArray()
            )
            assertTrue(archiveRes.success)

            clearVaultDbAndDisk()

            val restoreResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "WrongPassword!9999".toCharArray(),
                totpCode = null
            )

            assertFalse(restoreResult.success)
            assertNotNull(restoreResult.errorMessage)
            assertTrue(restoreResult.errorMessage!!.contains("GCM tag", ignoreCase = true))
            val diskFiles = VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty()
                .filter { it.isFile }
            assertEquals(0, diskFiles.size)
        }
    }

    @Test
    fun restoreInvalidMagicOrVersion_failsWithInvalidFormatError() {
        runBlocking {
            val badMagicFile = File(tempDir, "bad_magic.cvault").apply {
                writeBytes("NOT_CARTINO!!".toByteArray(Charsets.US_ASCII) + ByteArray(64))
            }
            val badMagicResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(badMagicFile),
                password = "AnyPassword!1234".toCharArray(),
                totpCode = null
            )
            assertFalse(badMagicResult.success)
            assertEquals("فرمت فایل نامعتبر است", badMagicResult.errorMessage)

            val badVersionHeader = ByteBuffer.allocate(45).apply {
                put("CARTINO_VAULT".toByteArray(Charsets.US_ASCII))
                putInt(99) // unsupported version
                put(ByteArray(16))
                put(ByteArray(12))
            }.array()
            val badVersionFile = File(tempDir, "bad_version.cvault").apply {
                writeBytes(badVersionHeader)
            }
            val badVersionResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(badVersionFile),
                password = "AnyPassword!1234".toCharArray(),
                totpCode = null
            )
            assertFalse(badVersionResult.success)
            assertEquals("فرمت فایل نامعتبر است", badVersionResult.errorMessage)
        }
    }

    @Test
    fun restoreMultiMegabyteArchive_processesStreamingWithBoundedMemory() {
        runBlocking {
            val fileSize = 4 * 1024 * 1024
            val uris = (1..3).map { idx ->
                val chunk = ByteArray(64 * 1024)
                Random(8800 + idx).nextBytes(chunk)
                val f = File(tempDir, "mb_file_$idx.bin")
                f.outputStream().use { out ->
                    repeat(fileSize / chunk.size) { out.write(chunk) }
                }
                Uri.fromFile(f)
            }
            viewModel.importVaultFilesInternal(uris, currentGroupId = null)

            val archiveFile = File(tempDir, "multi_mb.cvault")
            val archiveRes = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "LargeStreamTest!2026".toCharArray()
            )
            assertTrue(archiveRes.success)

            clearVaultDbAndDisk()

            System.gc()
            val runtime = Runtime.getRuntime()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()

            val restoreResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "LargeStreamTest!2026".toCharArray(),
                totpCode = null
            )

            System.gc()
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            val retainedDelta = (heapAfter - heapBefore).coerceAtLeast(0L)

            assertTrue(restoreResult.success)
            assertEquals(3, restoreResult.successCount)
            assertEquals(0, restoreResult.failedCount)
            assertEquals(3, restoreResult.restoredStorageNames.size)
            // After streaming 12 MB through restoreVaultArchive -> importFromBytes, no multi-MB buffers remain retained
            assertTrue(
                "Retained heap delta ($retainedDelta bytes) should stay small after restoring 12 MB",
                retainedDelta < 4L * 1024L * 1024L
            )
        }
    }

    @Test
    fun restoreV2ArchiveWithTotpRequired_acceptsValidAndWindowCodes_ignoresDeviceTotp_andPreservesDeviceState() {
        runBlocking {
            val archiveSecret = "JBSWY3DPEHPK3PXP"
            VaultArchiveManager.setTestTotpConfigProviderForTesting { true to archiveSecret }

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val grp = VaultGroup(id = "grp-v2-totp", name = "اسناد محرمانه", parentGroupId = null, createdAt = 100L)
            dao.insertGroup(grp)

            val secretBytes = "Encrypted V2 TOTP Vault File".toByteArray(Charsets.UTF_8)
            val src = File(tempDir, "v2_secret.txt").apply { writeBytes(secretBytes) }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = grp.id)

            val archiveFile = File(tempDir, "v2_totp.cvault")
            val archiveRes = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "V2TotpPassword!2026".toCharArray()
            )
            assertTrue(archiveRes.success)

            clearVaultDbAndDisk()

            // Device TOTP is now disabled (or could have a completely different secret),
            // yet restoring v2 must verify strictly against manifest.totpSecret and leave device TOTP untouched.
            VaultArchiveManager.setTestTotpConfigProviderForTesting { false to "" }
            val wasTotpEnabledBefore = viewModel.isTotpEnabled.value
            val totpSecretBefore = viewModel.totpSecret.value

            val inspection = VaultArchiveManager.inspectVaultArchiveWithVersion(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "V2TotpPassword!2026".toCharArray()
            )
            assertEquals(2, inspection.version)
            assertTrue(inspection.manifest.totpRequired)
            assertEquals(archiveSecret, inspection.manifest.totpSecret)

            // Verify adjacent time-window (-30s) code is accepted just like standard TOTP window
            val prevWindowCode = TotpManager.generateTotp(
                archiveSecret,
                (System.currentTimeMillis() / 1000L) - 30L
            )
            val report = viewModel.importVaultArchiveInternal(
                sourceUri = Uri.fromFile(archiveFile),
                password = "V2TotpPassword!2026".toCharArray(),
                totpCode = prevWindowCode,
                mergeStrategy = MergeStrategy.SKIP_DUPLICATES
            )

            assertEquals(1, report.newFiles)
            val restoredFile = dao.getAllFilesList().single()
            assertArrayEquals(secretBytes, readDecryptedBytes(restoredFile.storageName))
            assertEquals(wasTotpEnabledBefore, viewModel.isTotpEnabled.value)
            assertEquals(totpSecretBefore, viewModel.totpSecret.value)
        }
    }

    @Test
    fun restoreV2ArchiveWithTotpRequired_wrongOrMissingCodeFailsClearlyWithoutAnyMerge() {
        runBlocking {
            val archiveSecret = "JBSWY3DPEHPK3PXP"
            VaultArchiveManager.setTestTotpConfigProviderForTesting { true to archiveSecret }

            val src = File(tempDir, "protected.txt").apply {
                writeBytes("Must not merge on wrong TOTP".toByteArray(Charsets.UTF_8))
            }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = null)

            val archiveFile = File(tempDir, "v2_wrong_totp.cvault")
            val archiveRes = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "V2WrongTotp!Pass2026".toCharArray()
            )
            assertTrue(archiveRes.success)

            clearVaultDbAndDisk()

            val dao = CartinoDatabase.getDatabase(application).vaultDao()

            // Missing code
            val missingResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "V2WrongTotp!Pass2026".toCharArray(),
                totpCode = null
            )
            assertFalse(missingResult.success)
            assertNotNull(missingResult.errorMessage)
            assertTrue(missingResult.errorMessage!!.contains("TOTP"))
            assertEquals(0, dao.getAllFilesList().size)
            assertTrue(VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty().none { it.isFile })

            // Wrong code (pick one guaranteed outside [-1, +1] time steps)
            val currentSec = System.currentTimeMillis() / 1000L
            val validWindowCodes = (-1..1).map {
                TotpManager.generateTotp(archiveSecret, currentSec + it * 30L)
            }.toSet()
            val invalidCode = listOf("000000", "111111", "222222", "999999").first { it !in validWindowCodes }

            val wrongResult = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(archiveFile),
                password = "V2WrongTotp!Pass2026".toCharArray(),
                totpCode = invalidCode
            )
            assertFalse(wrongResult.success)
            assertNotNull(wrongResult.errorMessage)
            assertTrue(wrongResult.errorMessage!!.contains("TOTP"))
            assertEquals(0, dao.getAllFilesList().size)
            assertTrue(VaultStorageManager.getVaultDirectory(application).listFiles().orEmpty().none { it.isFile })
        }
    }

    @Test
    fun restoreV1ArchiveWithTotpKeyBinding_preservesBackwardCompatibility() {
        runBlocking {
            val payloadBytes = "Legacy V1 Vault File".toByteArray(Charsets.UTF_8)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payloadBytes).joinToString("") { "%02x".format(it) }
            val now = System.currentTimeMillis()
            val manifest = com.example.util.VaultArchiveManifest(
                timestamp = now,
                totpRequired = true,
                totpSecret = "",
                groups = listOf(
                    com.example.util.VaultArchiveGroupMetadata(
                        id = "v1-grp",
                        name = "آرشیو قدیمی",
                        parentGroupId = null,
                        createdAt = now
                    )
                ),
                files = listOf(
                    com.example.util.VaultArchiveFileMetadata(
                        id = "v1-file",
                        groupId = "v1-grp",
                        displayName = "legacy_v1.txt",
                        originalFileName = "legacy_v1.txt",
                        mimeType = "text/plain",
                        sizeBytes = payloadBytes.size.toLong(),
                        sha256 = sha,
                        storageName = "v1.vault",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )

            val salt = ByteArray(16) { 0x33 }
            val iv = ByteArray(12) { 0x44 }
            val password = "LegacyV1!Password2026".toCharArray()
            val v1TotpCode = "482910"
            val key = VaultArchiveManager.deriveArchiveKey(password, salt, v1TotpCode)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, iv)
                )
            }
            val manifestBytes = VaultArchiveManager.manifestAdapter.toJson(manifest).toByteArray(Charsets.UTF_8)
            val gzOut = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(gzOut).use { it.write(manifestBytes) }
            val gzManifest = gzOut.toByteArray()

            val v1File = File(tempDir, "legacy_v1.cvault")
            v1File.outputStream().use { rawOut ->
                rawOut.write("CARTINO_VAULT".toByteArray(Charsets.US_ASCII))
                rawOut.write(ByteBuffer.allocate(4).putInt(1).array())
                rawOut.write(salt)
                rawOut.write(iv)
                javax.crypto.CipherOutputStream(rawOut, cipher).use { cOut ->
                    cOut.write(ByteBuffer.allocate(4).putInt(gzManifest.size).array())
                    cOut.write(gzManifest)

                    val nameBytes = "v1.vault".toByteArray(Charsets.UTF_8)
                    cOut.write(ByteBuffer.allocate(4).putInt(nameBytes.size).array())
                    cOut.write(nameBytes)
                    cOut.write(ByteBuffer.allocate(8).putLong(payloadBytes.size.toLong()).array())
                    cOut.write(payloadBytes)
                }
            }

            // Wrong v1 TOTP must fail GCM verification
            val wrongV1 = VaultArchiveManager.restoreVaultArchive(
                context = application,
                sourceUri = Uri.fromFile(v1File),
                password = "LegacyV1!Password2026".toCharArray(),
                totpCode = "000000"
            )
            assertFalse(wrongV1.success)

            // Valid v1 TOTP restores successfully
            val report = viewModel.importVaultArchiveInternal(
                sourceUri = Uri.fromFile(v1File),
                password = "LegacyV1!Password2026".toCharArray(),
                totpCode = v1TotpCode,
                mergeStrategy = MergeStrategy.SKIP_DUPLICATES
            )
            assertEquals(1, report.newGroups)
            assertEquals(1, report.newFiles)
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val restored = dao.getAllFilesList().single()
            assertEquals("legacy_v1.txt", restored.displayName)
            assertArrayEquals(payloadBytes, readDecryptedBytes(restored.storageName))
        }
    }
}
