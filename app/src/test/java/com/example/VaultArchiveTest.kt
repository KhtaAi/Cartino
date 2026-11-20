package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.util.ArchiveResult
import com.example.util.VaultArchiveManifest
import com.example.util.VaultArchiveManager
import com.example.util.VaultStorageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.zip.GZIPInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultArchiveTest {

    private lateinit var application: Application
    private lateinit var viewModel: CartinoViewModel
    private lateinit var tempDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup() {
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            tempDir = File(application.cacheDir, "vault_archive_test").apply {
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
            VaultArchiveManager.setTestTotpConfigProviderForTesting(null)
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            tempDir.deleteRecursively()
        }
    }

    private data class DecryptedArchive(
        val magic: String,
        val version: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val manifest: VaultArchiveManifest,
        val filePayloadsByStorageName: Map<String, ByteArray>
    )

    private fun manuallyDecryptArchive(
        archiveFile: File,
        password: CharArray,
        totpCode: String? = null
    ): DecryptedArchive {
        FileInputStream(archiveFile).use { fileIn ->
            val dataHeader = DataInputStream(fileIn)
            val magicBytes = ByteArray(13)
            dataHeader.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            val version = dataHeader.readInt()
            val salt = ByteArray(16).also { dataHeader.readFully(it) }
            val iv = ByteArray(12).also { dataHeader.readFully(it) }

            // Derive key independently using Argon2id with exact parameters (4, 65536, 1)
            // Version 1 bound optional totpCode into KDF; Version 2 derives strictly from password.
            val cleanTotp = if (version == 1) totpCode?.trim()?.takeIf { it.isNotEmpty() } else null
            val combinedChars = if (cleanTotp != null) {
                (String(password) + ":" + cleanTotp).toCharArray()
            } else {
                password.copyOf()
            }
            val keyInputBuf = Charsets.UTF_8.encode(CharBuffer.wrap(combinedChars))
            val keyInputBytes = ByteArray(keyInputBuf.remaining()).also { keyInputBuf.get(it) }
            combinedChars.fill('\u0000')

            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(4)
                .withMemoryAsKB(65536)
                .withParallelism(1)
                .withSalt(salt)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            val derivedKey = ByteArray(32)
            generator.generateBytes(keyInputBytes, derivedKey, 0, derivedKey.size)
            keyInputBytes.fill(0)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derivedKey, "AES"),
                GCMParameterSpec(128, iv)
            )
            derivedKey.fill(0)

            val payloads = linkedMapOf<String, ByteArray>()
            var manifest: VaultArchiveManifest? = null

            CipherInputStream(fileIn, cipher).use { cipherIn ->
                fun drainToVerifyGcmTag() {
                    val drain = ByteArray(8192)
                    while (cipherIn.read(drain) != -1) {
                        // Drain remaining ciphertext so CipherInputStream runs cipher.doFinal()
                    }
                }

                val decData = DataInputStream(cipherIn)
                val manifestLen = decData.readInt()
                if (manifestLen !in 1..(16 * 1024 * 1024)) {
                    drainToVerifyGcmTag()
                    throw IllegalStateException("Invalid manifest length: $manifestLen")
                }
                val gzippedManifest = ByteArray(manifestLen)
                try {
                    decData.readFully(gzippedManifest)
                } catch (e: Throwable) {
                    drainToVerifyGcmTag()
                    throw e
                }

                // Verify manifest was GZIP compressed before encryption (magic 0x1f 0x8b)
                if (gzippedManifest.size < 2 ||
                    gzippedManifest[0] != 0x1f.toByte() ||
                    gzippedManifest[1] != 0x8b.toByte()
                ) {
                    drainToVerifyGcmTag()
                    throw IllegalStateException("Manifest is not GZIP compressed")
                }

                val manifestJson = try {
                    GZIPInputStream(ByteArrayInputStream(gzippedManifest))
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                } catch (e: Throwable) {
                    drainToVerifyGcmTag()
                    throw e
                }
                manifest = VaultArchiveManager.manifestAdapter.fromJson(manifestJson) ?: run {
                    drainToVerifyGcmTag()
                    throw IllegalStateException("Null manifest JSON")
                }

                val fileCount = manifest?.files?.size ?: 0
                val chunkBuf = ByteArray(64 * 1024)
                repeat(fileCount) {
                    val nameLen = decData.readInt()
                    val nameBytes = ByteArray(nameLen)
                    decData.readFully(nameBytes)
                    val storageName = String(nameBytes, Charsets.UTF_8)

                    val contentLen = decData.readLong()
                    val baos = ByteArrayOutputStream(contentLen.toInt().coerceAtLeast(0))
                    var remaining = contentLen
                    while (remaining > 0L) {
                        val toRead = minOf(chunkBuf.size.toLong(), remaining).toInt()
                        val read = cipherIn.read(chunkBuf, 0, toRead)
                        if (read == -1) {
                            throw java.io.EOFException("Unexpected EOF while reading file $storageName")
                        }
                        baos.write(chunkBuf, 0, read)
                        remaining -= read
                    }
                    payloads[storageName] = baos.toByteArray()
                }

                // Drain through EOF so CipherInputStream triggers cipher.doFinal() and verifies GCM tag
                val trailing = ByteArray(64)
                while (cipherIn.read(trailing) != -1) {
                    // Exhaust stream cleanly
                }
            }

            return DecryptedArchive(
                magic = magic,
                version = version,
                salt = salt,
                iv = iv,
                manifest = requireNotNull(manifest),
                filePayloadsByStorageName = payloads
            )
        }
    }

    @Test
    fun createAndManuallyDecryptArchive_roundTripsNestedGroupsManifestAndBytes() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val rootGroup = VaultGroup(id = "grp-a", name = "اسناد شخصی", parentGroupId = null)
            val subGroup = VaultGroup(id = "grp-b", name = "بانکی", parentGroupId = "grp-a")
            val grandGroup = VaultGroup(id = "grp-c", name = "کارت‌ها", parentGroupId = "grp-b")
            dao.insertGroup(rootGroup)
            dao.insertGroup(subGroup)
            dao.insertGroup(grandGroup)

            val bytesRoot = "Root note plaintext".toByteArray(Charsets.UTF_8)
            val bytesSub = Random(7711).nextBytes(180 * 1024 + 43)
            val bytesGrand = "Grandchild key payload".toByteArray(Charsets.UTF_8)

            val srcRoot = File(tempDir, "root_doc.txt").apply { writeBytes(bytesRoot) }
            val srcSub = File(tempDir, "bank_stmt.bin").apply { writeBytes(bytesSub) }
            val srcGrand = File(tempDir, "secret.key").apply { writeBytes(bytesGrand) }

            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(srcRoot)), currentGroupId = rootGroup.id)
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(srcSub)), currentGroupId = subGroup.id)
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(srcGrand)), currentGroupId = grandGroup.id)

            val storedFiles = dao.getAllFilesList()
            val subRecord = storedFiles.single { it.groupId == subGroup.id }
            dao.updateFile(subRecord.copy(displayName = "صورتحساب بانکی", notes = "مهم ۱۴۰۵"))
            val updatedSubRecord = dao.getFileById(subRecord.id)!!
            val rootRecord = storedFiles.single { it.groupId == rootGroup.id }
            val grandRecord = storedFiles.single { it.groupId == grandGroup.id }

            val archiveFile = File(tempDir, "vault_backup.cvault")
            val password = "Archive!Master#Password2026".toCharArray()

            val result = viewModel.exportVaultArchiveInternal(
                targetUri = Uri.fromFile(archiveFile),
                password = password
            )

            assertTrue(result.success)
            assertNull(result.errorMessage)
            assertEquals(3, result.totalGroups)
            assertEquals(3, result.totalFiles)
            assertTrue(archiveFile.exists())

            val prefs = application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
            assertTrue(prefs.getLong("last_vault_backup_timestamp", 0L) > 0L)

            val parsed = manuallyDecryptArchive(
                archiveFile = archiveFile,
                password = "Archive!Master#Password2026".toCharArray()
            )
            assertEquals("CARTINO_VAULT", parsed.magic)
            assertEquals(2, parsed.version)
            assertEquals(16, parsed.salt.size)
            assertEquals(12, parsed.iv.size)
            assertFalse(parsed.manifest.totpRequired)
            assertEquals("", parsed.manifest.totpSecret)
            assertTrue(parsed.manifest.timestamp > 0L)
            assertEquals(3, parsed.manifest.groups.size)
            assertEquals(3, parsed.manifest.files.size)

            val restoredGroupNames = parsed.manifest.groups.associate { it.id to it.name }
            assertEquals("اسناد شخصی", restoredGroupNames["grp-a"])
            assertEquals("بانکی", restoredGroupNames["grp-b"])
            assertEquals("کارت‌ها", restoredGroupNames["grp-c"])

            val manifestSubMeta = parsed.manifest.files.single { it.id == updatedSubRecord.id }
            assertEquals("صورتحساب بانکی", manifestSubMeta.displayName)
            assertEquals("مهم ۱۴۰۵", manifestSubMeta.notes)
            assertEquals(updatedSubRecord.sha256, manifestSubMeta.sha256)

            assertArrayEquals(bytesRoot, parsed.filePayloadsByStorageName[rootRecord.storageName])
            assertArrayEquals(bytesSub, parsed.filePayloadsByStorageName[updatedSubRecord.storageName])
            assertArrayEquals(bytesGrand, parsed.filePayloadsByStorageName[grandRecord.storageName])
        }
    }

    @Test
    fun createArchiveV2WithTotpActive_requiresNoTotpCode_usesPasswordOnlyKdf_andStoresSecretInManifest() {
        runBlocking {
            val secret = "JBSWY3DPEHPK3PXP"
            VaultArchiveManager.setTestTotpConfigProviderForTesting { true to secret }

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val grp = VaultGroup(id = "grp-totp", name = "محرمانه", parentGroupId = null)
            dao.insertGroup(grp)

            val payload = "2FA secret-in-manifest payload".toByteArray(Charsets.UTF_8)
            val src = File(tempDir, "2fa.txt").apply { writeBytes(payload) }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = grp.id)
            val stored = dao.getAllFilesList().single()

            val archiveFile = File(tempDir, "vault_totp_v2.cvault")
            val result = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "VaultPassword#9876".toCharArray()
            )
            assertTrue(result.success)
            assertNull(result.errorMessage)

            // v2 archive key derives strictly from password even when TOTP is enabled
            val parsed = manuallyDecryptArchive(
                archiveFile = archiveFile,
                password = "VaultPassword#9876".toCharArray(),
                totpCode = null
            )
            assertEquals(2, parsed.version)
            assertTrue(parsed.manifest.totpRequired)
            assertEquals(secret, parsed.manifest.totpSecret)
            assertArrayEquals(payload, parsed.filePayloadsByStorageName[stored.storageName])

            // Disabling device TOTP produces manifest with totpRequired=false and empty secret
            VaultArchiveManager.setTestTotpConfigProviderForTesting { false to "" }
            val archiveNoTotp = File(tempDir, "vault_no_totp_v2.cvault")
            val noTotpResult = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveNoTotp),
                password = "VaultPassword#9876".toCharArray()
            )
            assertTrue(noTotpResult.success)
            val parsedNoTotp = manuallyDecryptArchive(
                archiveFile = archiveNoTotp,
                password = "VaultPassword#9876".toCharArray()
            )
            assertEquals(2, parsedNoTotp.version)
            assertFalse(parsedNoTotp.manifest.totpRequired)
            assertEquals("", parsedNoTotp.manifest.totpSecret)
        }
    }

    @Test
    fun decryptArchiveWithWrongPassword_failsWithGcmTagVerificationError() {
        runBlocking {
            val src = File(tempDir, "key.bin").apply {
                writeBytes("Sensitive master key material".toByteArray(Charsets.UTF_8))
            }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = null)

            val archiveFile = File(tempDir, "wrong_pwd_test.cvault")
            val result = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "CorrectArchivePassword!1".toCharArray()
            )
            assertTrue(result.success)

            val failure = runCatching {
                manuallyDecryptArchive(
                    archiveFile = archiveFile,
                    password = "WrongArchivePassword!2".toCharArray(),
                    totpCode = null
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(
                "Expected AEADBadTagException in cause chain, got $failure",
                hasCause<AEADBadTagException>(failure!!)
            )
        }
    }

    @Test
    fun createArchiveWithMultiMegabyteFiles_streamsWithoutHoldingEntireFilesInHeap() {
        runBlocking {
            // Create 3 files x 4 MB = 12 MB total plaintext (> 64 KB buffer x 192 chunks)
            val fileSize = 4 * 1024 * 1024
            val sampleUris = (1..3).map { idx ->
                val chunk = ByteArray(64 * 1024)
                Random(5000 + idx).nextBytes(chunk)
                val f = File(tempDir, "large_$idx.bin")
                f.outputStream().use { out ->
                    repeat(fileSize / chunk.size) {
                        out.write(chunk)
                    }
                }
                Uri.fromFile(f)
            }

            val importReport = viewModel.importVaultFilesInternal(sampleUris, currentGroupId = null)
            assertEquals(3, importReport.importedCount)

            val archiveFile = File(tempDir, "large_archive.cvault")
            System.gc()
            val runtime = Runtime.getRuntime()
            val heapBeforeBytes = runtime.totalMemory() - runtime.freeMemory()

            val archiveResult = VaultArchiveManager.createVaultArchive(
                context = application,
                targetUri = Uri.fromFile(archiveFile),
                password = "StreamingMemoryTest!2026".toCharArray()
            )

            System.gc()
            val heapAfterBytes = runtime.totalMemory() - runtime.freeMemory()
            val retainedDeltaBytes = (heapAfterBytes - heapBeforeBytes).coerceAtLeast(0L)

            assertTrue(archiveResult.success)
            assertEquals(3, archiveResult.totalFiles)
            assertTrue(archiveFile.length() > fileSize * 2L)
            // After streaming completes and transient Argon2 buffers are collected, no 12 MB payload is retained on heap
            assertTrue(
                "Retained heap delta ($retainedDeltaBytes bytes) should stay small after streaming 12 MB",
                retainedDeltaBytes < 4L * 1024L * 1024L
            )
        }
    }

    @Test
    fun interruptedArchiveWrite_returnsFailureAndDeletesIncompleteFileFromDisk() {
        runBlocking {
            val file1 = File(tempDir, "first_ok.txt").apply {
                writeBytes("First file valid payload".toByteArray(Charsets.UTF_8))
            }
            val file2 = File(tempDir, "second_fail.txt").apply {
                writeBytes("Second file payload that will fail mid-archive".toByteArray(Charsets.UTF_8))
            }
            viewModel.importVaultFilesInternal(
                listOf(Uri.fromFile(file1), Uri.fromFile(file2)),
                currentGroupId = null
            )

            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            val secondRecord = dao.getAllFilesList().last()
            // Remove encrypted storage for second file so archive creation fails mid-stream after file1 started
            val secondDiskFile = File(VaultStorageManager.getVaultDirectory(application), secondRecord.storageName)
            assertTrue(secondDiskFile.delete())

            val incompleteArchive = File(tempDir, "incomplete.cvault")
            val failResult: ArchiveResult = viewModel.exportVaultArchiveInternal(
                targetUri = Uri.fromFile(incompleteArchive),
                password = "MidWriteFailure!Test123".toCharArray()
            )

            assertFalse(failResult.success)
            assertNotNull(failResult.errorMessage)
            assertFalse(
                "Incomplete .cvault file must be deleted from disk on failure",
                incompleteArchive.exists()
            )
            val prefs = application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
            assertEquals(0L, prefs.getLong("last_vault_backup_timestamp", 0L))
        }
    }

    @Test
    fun viewModelExportVaultArchive_showsToastAndSavesLastBackupTimestamp() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            dao.insertGroup(VaultGroup(id = "grp-vm", name = "گروه آرشیو", parentGroupId = null))
            val src = File(tempDir, "vm_file.txt").apply {
                writeBytes("ViewModel archive payload".toByteArray(Charsets.UTF_8))
            }
            viewModel.importVaultFilesInternal(listOf(Uri.fromFile(src)), currentGroupId = "grp-vm")

            val outArchive = File(tempDir, "vm_archive.cvault")
            val deferred = CompletableDeferred<ArchiveResult>()

            viewModel.exportVaultArchive(
                targetUri = Uri.fromFile(outArchive),
                password = "VmArchivePassword!2026".toCharArray(),
                onSuccess = { res -> deferred.complete(res) },
                onError = { err -> deferred.complete(ArchiveResult(success = false, errorMessage = err)) }
            )

            val deadline = System.currentTimeMillis() + 15_000L
            while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val result = withTimeout(1000L) { deferred.await() }
            assertTrue(result.success)
            assertEquals(1, result.totalGroups)
            assertEquals(1, result.totalFiles)

            val toastText = ShadowToast.getTextOfLatestToast()
            assertNotNull(toastText)
            assertTrue(toastText.contains("1"))

            val prefs = application.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
            assertNotEquals(0L, prefs.getLong("last_vault_backup_timestamp", 0L))
        }
    }

    private inline fun <reified T : Throwable> hasCause(throwable: Throwable): Boolean {
        return generateSequence<Throwable>(throwable) { it.cause }.any { it is T }
    }
}
