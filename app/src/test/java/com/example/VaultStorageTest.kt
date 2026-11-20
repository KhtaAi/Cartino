package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.util.VaultFileTooLargeException
import com.example.util.VaultStorageManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultStorageTest {

    private lateinit var context: Context
    private lateinit var sourceTempDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sourceTempDir = File(context.cacheDir, "vault_test_sources").apply {
            deleteRecursively()
            mkdirs()
        }
        VaultStorageManager.getVaultDirectory(context).deleteRecursively()
        VaultStorageManager.getVaultDirectory(context)

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testSecretKey = keyGen.generateKey()
        VaultStorageManager.setTestKeyProviderForTesting { testSecretKey }
    }

    @After
    fun tearDown() {
        VaultStorageManager.setTestKeyProviderForTesting(null)
        VaultStorageManager.getVaultDirectory(context).deleteRecursively()
        sourceTempDir.deleteRecursively()
    }

    @Test
    fun roundTrip_smallBinaryWithAllByteValues_isByteForByteIdentical() {
        val allBytes = ByteArray(256) { it.toByte() }
        val inputFile = File(sourceTempDir, "all_bytes.bin").apply { writeBytes(allBytes) }
        val expectedSha = sha256Hex(allBytes)

        val imported = VaultStorageManager.importFromUri(context, Uri.fromFile(inputFile))

        assertEquals(allBytes.size.toLong(), imported.sizeBytes)
        assertEquals(expectedSha, imported.sha256)
        assertEquals(VaultStorageManager.ENCRYPTION_VERSION, imported.encryptionVersion)
        assertTrue(VaultStorageManager.encryptedFileExists(context, imported.storageName))

        val decrypted = VaultStorageManager.openDecryptedInputStream(context, imported.storageName).use {
            it.readBytes()
        }

        assertArrayEquals(allBytes, decrypted)
        assertEquals(expectedSha, sha256Hex(decrypted))
    }

    @Test
    fun roundTrip_multiMegabyteRandomBinary_isByteForByteIdentical() {
        // 3.5 MB (> 50x of 64 KB buffer) guarantees multiple streaming chunks
        val totalBytes = (3 * 1024 * 1024) + (512 * 1024) + 137
        val randomBytes = Random(42_2026).nextBytes(totalBytes)
        val inputFile = File(sourceTempDir, "large_random.bin").apply { writeBytes(randomBytes) }
        val expectedSha = sha256Hex(randomBytes)

        val imported = VaultStorageManager.importFromUri(context, Uri.fromFile(inputFile))

        assertEquals(totalBytes.toLong(), imported.sizeBytes)
        assertEquals(expectedSha, imported.sha256)

        val decryptedDigest = MessageDigest.getInstance("SHA-256")
        val decryptedOut = java.io.ByteArrayOutputStream(totalBytes)
        VaultStorageManager.openDecryptedInputStream(context, imported.storageName).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                decryptedDigest.update(buf, 0, read)
                decryptedOut.write(buf, 0, read)
            }
        }
        val decryptedBytes = decryptedOut.toByteArray()
        val decryptedSha = decryptedDigest.digest().joinToString("") { "%02x".format(it) }

        assertEquals(expectedSha, decryptedSha)
        assertArrayEquals(randomBytes, decryptedBytes)
    }

    @Test
    fun tamperDetection_flippingOneByteInCiphertextBody_failsAuthentication() {
        val original = Random(99).nextBytes(128 * 1024)
        val inputFile = File(sourceTempDir, "tamper_target.bin").apply { writeBytes(original) }
        val imported = VaultStorageManager.importFromUri(context, Uri.fromFile(inputFile))

        val encryptedFile = File(VaultStorageManager.getVaultDirectory(context), imported.storageName)
        val diskBytes = encryptedFile.readBytes()
        // Format: [12 bytes IV][ciphertext + 16 bytes tag]; flip a byte inside ciphertext body
        val bodyIndex = 12 + 500
        diskBytes[bodyIndex] = (diskBytes[bodyIndex].toInt() xor 0x01).toByte()
        encryptedFile.writeBytes(diskBytes)

        try {
            VaultStorageManager.openDecryptedInputStream(context, imported.storageName).use { input ->
                input.readBytes()
            }
            fail("Expected AEAD integrity verification failure when encrypted body is tampered")
        } catch (expected: IOException) {
            // Expected fail-loud behavior
            assertTrue(expected.message?.isNotBlank() == true)
        }
    }

    @Test
    fun maxFileSizeLimit_exceededDuringStreaming_failsAndCleansTempFiles() {
        val smallLimit = 1024L
        val oversized = Random(7).nextBytes(2048)
        val inputFile = File(sourceTempDir, "oversized.bin").apply { writeBytes(oversized) }

        try {
            VaultStorageManager.importFromUri(
                context = context,
                uri = Uri.fromFile(inputFile),
                maxFileSizeBytes = smallLimit
            )
            fail("Expected VaultFileTooLargeException when file exceeds maxFileSizeBytes")
        } catch (expected: VaultFileTooLargeException) {
            assertTrue(expected.message?.contains("سقف مجاز") == true)
        }

        val leftoverFiles = VaultStorageManager.getVaultDirectory(context).listFiles().orEmpty()
        assertEquals(0, leftoverFiles.size)
        assertFalse(leftoverFiles.any { it.name.endsWith(".part") })
    }

    @Test
    fun storageHelpers_availableSpaceAndDeleteAndTotalSizeWork() {
        assertTrue(VaultStorageManager.getAvailableSpaceBytes(context) > 0L)

        val payload = ByteArray(4096) { 0x5A }
        val inputFile = File(sourceTempDir, "sample.bin").apply { writeBytes(payload) }
        val imported = VaultStorageManager.importFromUri(context, Uri.fromFile(inputFile))

        val totalSizeBeforeDelete = VaultStorageManager.getTotalStorageSizeBytes(context)
        assertTrue(totalSizeBeforeDelete >= payload.size.toLong() + 28L)

        assertTrue(VaultStorageManager.deleteEncryptedFile(context, imported.storageName))
        assertFalse(VaultStorageManager.encryptedFileExists(context, imported.storageName))
        assertEquals(0L, VaultStorageManager.getTotalStorageSizeBytes(context))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
