package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VaultImportResult(
    val storageName: String,
    val originalFileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val encryptionVersion: Int = VaultStorageManager.ENCRYPTION_VERSION
)

data class OrphanCleanupResult(
    val deletedCount: Int,
    val freedBytes: Long
)

data class ExportResult(
    val success: Boolean,
    val sha256Before: String = "",
    val sha256After: String = "",
    val errorMessage: String? = null,
    val totalFiles: Int = 0
)

class VaultFileTooLargeException(message: String) : IOException(message)

/**
 * Manages streaming AES-256-GCM encrypted binary storage for Vault files inside the app's
 * private internal storage directory (`filesDir/vault`).
 *
 * Disk format (encryptionVersion = 1):
 *   [12-byte random GCM IV][AES/GCM/NoPadding ciphertext + 16-byte GCM authentication tag]
 *
 * Keystore policy:
 *   Uses dedicated alias [VAULT_KEY_ALIAS] in AndroidKeyStore, completely independent from
 *   field encryption keys. Crucially, the key is NOT bound to user authentication or biometrics
 *   so enrolling a new fingerprint never permanently invalidates stored user files.
 */
object VaultStorageManager {

    const val VAULT_DIR_NAME = "vault"
    const val VAULT_KEY_ALIAS = "cartino_vault_file_key"
    const val DEFAULT_MAX_FILE_SIZE_BYTES = 100L * 1024L * 1024L
    const val ENCRYPTION_VERSION = 1

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val TEMP_FILE_PREFIX = "tmp_"
    private const val TEMP_FILE_SUFFIX = ".part"
    private const val VAULT_FILE_SUFFIX = ".vault"
    private const val MIN_FREE_SPACE_MARGIN_BYTES = 64L * 1024L

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    @Volatile
    private var testKeyProvider: (() -> SecretKey)? = null

    internal fun setTestKeyProviderForTesting(provider: (() -> SecretKey)?) {
        testKeyProvider = provider
        cachedSecretKey = null
    }

    private fun getOrCreateVaultKey(): SecretKey {
        testKeyProvider?.let { return it.invoke() }

        cachedSecretKey?.let { return it }
        return synchronized(this) {
            cachedSecretKey?.let { return it }
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(VAULT_KEY_ALIAS)) {
                    val entry = keyStore.getEntry(VAULT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                    if (entry != null) {
                        cachedSecretKey = entry.secretKey
                        return entry.secretKey
                    }
                }

                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                // Do NOT bind to user authentication or biometrics so new biometric
                // enrollments never invalidate encrypted vault files.
                val spec = KeyGenParameterSpec.Builder(
                    VAULT_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
                keyGenerator.init(spec)
                val generatedKey = keyGenerator.generateKey()
                cachedSecretKey = generatedKey
                generatedKey
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "کلید امنیتی صندوق در Android Keystore در دسترس نیست: ${t.localizedMessage}",
                    t
                )
            }
        }
    }

    fun getVaultDirectory(context: Context): File {
        val dir = File(context.applicationContext.filesDir, VAULT_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("امکان ایجاد پوشه خصوصی صندوق وجود ندارد")
        }
        return dir
    }

    fun getAvailableSpaceBytes(context: Context): Long {
        return runCatching { getVaultDirectory(context).usableSpace }.getOrDefault(0L)
    }

    /**
     * Imports content from [uri] into encrypted streaming vault storage.
     * Enforces [maxFileSizeBytes] on the fly while streaming with a 64 KB buffer, computes the
     * SHA-256 hex digest of the original unencrypted bytes simultaneously, writes to a temporary
     * `.part` file, and renames atomically only after `CipherOutputStream` finishes cleanly.
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES
    ): VaultImportResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val vaultDir = getVaultDirectory(appContext)

        var originalFileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "vault_file"
        var reportedSizeBytes = -1L

        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let {
                                originalFileName = it
                            }
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            reportedSizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }
        }

        val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        if (reportedSizeBytes > maxFileSizeBytes) {
            throw VaultFileTooLargeException(formatSizeLimitExceededMessage(maxFileSizeBytes))
        }

        val availableSpace = getAvailableSpaceBytes(appContext)
        val requiredMinimum = if (reportedSizeBytes > 0L) {
            reportedSizeBytes + MIN_FREE_SPACE_MARGIN_BYTES
        } else {
            MIN_FREE_SPACE_MARGIN_BYTES
        }
        if (availableSpace < requiredMinimum) {
            throw IOException("فضای خالی حافظه دستگاه برای ذخیره فایل کافی نیست")
        }

        val storageName = "${UUID.randomUUID()}$VAULT_FILE_SUFFIX"
        val tempFile = File(vaultDir, "$TEMP_FILE_PREFIX${UUID.randomUUID()}$TEMP_FILE_SUFFIX")
        val finalFile = File(vaultDir, storageName)

        val secretKey = getOrCreateVaultKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        if (iv == null || iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("طول IV تولیدشده برای رمزنگاری معتبر نیست")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytesRead = 0L

        try {
            val inputStream = resolver.openInputStream(uri)
                ?: throw IOException("امکان باز کردن فایل انتخاب‌شده وجود ندارد")

            inputStream.use { source ->
                FileOutputStream(tempFile).use { fileOut ->
                    fileOut.write(iv)
                    CipherOutputStream(fileOut, cipher).use { cipherOut ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            totalBytesRead += read
                            if (totalBytesRead > maxFileSizeBytes) {
                                throw VaultFileTooLargeException(formatSizeLimitExceededMessage(maxFileSizeBytes))
                            }
                            digest.update(buffer, 0, read)
                            cipherOut.write(buffer, 0, read)
                        }
                    }
                }
            }

            if (!tempFile.exists() || !tempFile.renameTo(finalFile)) {
                throw IOException("خطا در نهایی‌سازی فایل رمزنگاری‌شده صندوق")
            }

            val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
            SyncLogger.log("VAULT", "فایل صندوق ذخیره شد (شناسه: ${storageName.take(8)}، حجم: $totalBytesRead بایت)")

            return VaultImportResult(
                storageName = storageName,
                originalFileName = originalFileName,
                mimeType = mimeType,
                sizeBytes = totalBytesRead,
                sha256 = sha256Hex,
                encryptionVersion = ENCRYPTION_VERSION
            )
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            runCatching { if (finalFile.exists()) finalFile.delete() }
            SyncLogger.log("VAULT", "خطا در ذخیره‌سازی فایل صندوق: ${t.javaClass.simpleName}")
            throw t
        }
    }

    /**
     * Encrypts raw [bytes] using AES-256-GCM (dedicated Keystore vault key), writes to a
     * temporary `.part` file in 64 KB chunks, atomically renames to [storageName], and
     * returns the final [storageName].
     */
    fun importFromBytes(
        context: Context,
        storageName: String = "${UUID.randomUUID()}$VAULT_FILE_SUFFIX",
        bytes: ByteArray
    ): String {
        val appContext = context.applicationContext
        val vaultDir = getVaultDirectory(appContext)
        val cleanStorageName = storageName.trim().ifBlank {
            "${UUID.randomUUID()}$VAULT_FILE_SUFFIX"
        }

        if (bytes.size.toLong() > DEFAULT_MAX_FILE_SIZE_BYTES) {
            throw VaultFileTooLargeException(formatSizeLimitExceededMessage(DEFAULT_MAX_FILE_SIZE_BYTES))
        }

        val availableSpace = getAvailableSpaceBytes(appContext)
        val requiredMinimum = bytes.size.toLong() + MIN_FREE_SPACE_MARGIN_BYTES
        if (availableSpace < requiredMinimum) {
            throw IOException("فضای خالی حافظه دستگاه برای ذخیره فایل کافی نیست")
        }

        val finalFile = resolveSafeVaultFile(appContext, cleanStorageName)
            ?: throw IllegalArgumentException("نام فایل ذخیره‌سازی نامعتبر است")
        val tempFile = File(vaultDir, "$TEMP_FILE_PREFIX${UUID.randomUUID()}$TEMP_FILE_SUFFIX")

        val secretKey = getOrCreateVaultKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        if (iv == null || iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("طول IV تولیدشده برای رمزنگاری معتبر نیست")
        }

        try {
            FileOutputStream(tempFile).use { fileOut ->
                fileOut.write(iv)
                CipherOutputStream(fileOut, cipher).use { cipherOut ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val chunkLen = minOf(STREAM_BUFFER_BYTES, bytes.size - offset)
                        cipherOut.write(bytes, offset, chunkLen)
                        offset += chunkLen
                    }
                }
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("خطا در جایگزینی فایل رمزنگاری‌شده صندوق")
            }
            if (!tempFile.exists() || !tempFile.renameTo(finalFile)) {
                throw IOException("خطا در نهایی‌سازی فایل رمزنگاری‌شده صندوق")
            }

            SyncLogger.log(
                "VAULT",
                "فایل صندوق از بایت‌ها ذخیره شد (شناسه: ${cleanStorageName.take(8)}، حجم: ${bytes.size} بایت)"
            )
            return cleanStorageName
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            runCatching { if (finalFile.exists()) finalFile.delete() }
            SyncLogger.log("VAULT", "خطا در ذخیره‌سازی بایتی فایل صندوق: ${t.javaClass.simpleName}")
            throw t
        }
    }

    /**
     * Opens a streaming decrypted [InputStream] for the encrypted file identified by [storageName].
     *
     * IMPORTANT SECURITY CONTRACT:
     * AES-GCM verifies its 128-bit authentication tag only when the stream finishes reading the
     * ciphertext through EOF (`cipher.doFinal()`). Therefore:
     * 1. Any byte tampering in the IV, ciphertext, or tag throws an [IOException] (wrapping
     *    `AEADBadTagException`) when EOF is reached—never silent corrupt plaintext.
     * 2. Callers MUST consume the stream completely to EOF and verify the SHA-256 hash of the
     *    plaintext against the stored `VaultFile.sha256` BEFORE trusting or exporting the bytes.
     */
    fun openDecryptedInputStream(context: Context, storageName: String): InputStream {
        val targetFile = resolveSafeVaultFile(context, storageName)
            ?: throw IllegalArgumentException("نام فایل ذخیره‌سازی نامعتبر است")
        if (!targetFile.exists() || !targetFile.isFile) {
            throw IOException("فایل رمزنگاری‌شده در حافظه صندوق یافت نشد")
        }

        val secretKey = getOrCreateVaultKey()
        val fileIn = FileInputStream(targetFile)
        try {
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            var ivOffset = 0
            while (ivOffset < GCM_IV_LENGTH_BYTES) {
                val read = fileIn.read(iv, ivOffset, GCM_IV_LENGTH_BYTES - ivOffset)
                if (read == -1) {
                    throw IOException("ساختار فایل رمزنگاری‌شده صندوق معتبر نیست")
                }
                ivOffset += read
            }

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            return GcmStreamingDecryptInputStream(fileIn, cipher)
        } catch (t: Throwable) {
            runCatching { fileIn.close() }
            throw t
        }
    }

    /**
     * Streams and verifies decrypted Vault file integrity ([expectedSha256] + AES-GCM tag),
     * writes plaintext bytes to [targetUri], and re-reads [targetUri] to confirm [sha256After].
     */
    fun exportToUri(
        context: Context,
        storageName: String,
        targetUri: Uri,
        expectedSha256: String? = null
    ): ExportResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val sourceFile = resolveSafeVaultFile(appContext, storageName)
            ?: return ExportResult(
                success = false,
                sha256Before = "",
                sha256After = "",
                errorMessage = "نام فایل ذخیره‌سازی نامعتبر است"
            )

        if (!sourceFile.exists() || !sourceFile.isFile) {
            return ExportResult(
                success = false,
                sha256Before = "",
                sha256After = "",
                errorMessage = "فایل رمزنگاری‌شده در حافظه صندوق یافت نشد"
            )
        }

        // Pass 1: Decrypt stream completely in 64 KB chunks to verify AES-GCM tag and compute SHA-256 before writing
        val sha256Before = try {
            val digestBefore = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            openDecryptedInputStream(appContext, storageName).use { decryptedIn ->
                while (true) {
                    val read = decryptedIn.read(buffer)
                    if (read == -1) break
                    digestBefore.update(buffer, 0, read)
                }
            }
            digestBefore.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            SyncLogger.log("VAULT", "خطای یکپارچگی در رمزگشایی فایل برای خروجی: ${t.javaClass.simpleName}")
            return ExportResult(
                success = false,
                sha256Before = "",
                sha256After = "",
                errorMessage = "یکپارچگی فایل تأیید نشد"
            )
        }

        if (!expectedSha256.isNullOrBlank() && !sha256Before.equals(expectedSha256, ignoreCase = true)) {
            SyncLogger.log("VAULT", "عدم تطابق SHA-256 پیش از خروجی فایل صندوق")
            return ExportResult(
                success = false,
                sha256Before = sha256Before,
                sha256After = "",
                errorMessage = "یکپارچگی فایل تأیید نشد"
            )
        }

        // Pass 2: Stream decrypted bytes to targetUri
        try {
            val outputStream = try {
                resolver.openOutputStream(targetUri, "w")
            } catch (_: Throwable) {
                resolver.openOutputStream(targetUri)
            } ?: throw IOException("امکان باز کردن مسیر خروجی برای نوشتن وجود ندارد")

            outputStream.use { out ->
                openDecryptedInputStream(appContext, storageName).use { decryptedIn ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = decryptedIn.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            val msg = t.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "خطا در نوشتن فایل در مسیر خروجی"
            SyncLogger.log("VAULT", "خطا در نوشتن فایل خروجی صندوق: ${t.javaClass.simpleName}")
            return ExportResult(
                success = false,
                sha256Before = sha256Before,
                sha256After = "",
                errorMessage = msg
            )
        }

        // Pass 3: Re-read written targetUri to compute SHA-256 after writing and confirm byte-for-byte match
        val sha256After = try {
            val inputAfter = resolver.openInputStream(targetUri)
                ?: throw IOException("امکان خواندن فایل خروجی برای تأیید یکپارچگی وجود ندارد")
            val digestAfter = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            inputAfter.use { writtenIn ->
                while (true) {
                    val read = writtenIn.read(buffer)
                    if (read == -1) break
                    digestAfter.update(buffer, 0, read)
                }
            }
            digestAfter.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            SyncLogger.log("VAULT", "خطا در بررسی SHA-256 فایل خروجی: ${t.javaClass.simpleName}")
            return ExportResult(
                success = false,
                sha256Before = sha256Before,
                sha256After = "",
                errorMessage = t.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "یکپارچگی فایل خروجی تأیید نشد"
            )
        }

        val referenceHash = expectedSha256?.takeIf { it.isNotBlank() } ?: sha256Before
        if (!sha256After.equals(referenceHash, ignoreCase = true)) {
            SyncLogger.log("VAULT", "عدم تطابق SHA-256 پس از نوشتن فایل خروجی صندوق")
            return ExportResult(
                success = false,
                sha256Before = sha256Before,
                sha256After = sha256After,
                errorMessage = "یکپارچگی فایل تأیید نشد"
            )
        }

        SyncLogger.log("VAULT", "خروجی فایل صندوق با تأیید SHA-256 تکمیل شد (شناسه: ${storageName.take(8)})")
        return ExportResult(
            success = true,
            sha256Before = sha256Before,
            sha256After = sha256After,
            errorMessage = null,
            totalFiles = 1
        )
    }

    fun deleteEncryptedFile(context: Context, storageName: String): Boolean {
        val targetFile = resolveSafeVaultFile(context, storageName) ?: return false
        if (!targetFile.exists()) return false
        val deleted = targetFile.delete()
        if (deleted) {
            SyncLogger.log("VAULT", "فایل صندوق حذف شد (شناسه: ${storageName.take(8)})")
        }
        return deleted
    }

    fun encryptedFileExists(context: Context, storageName: String): Boolean {
        val targetFile = resolveSafeVaultFile(context, storageName) ?: return false
        return targetFile.exists() && targetFile.isFile
    }

    fun getEncryptedFileSizeBytes(context: Context, storageName: String): Long {
        val targetFile = resolveSafeVaultFile(context, storageName) ?: return 0L
        return if (targetFile.exists() && targetFile.isFile) targetFile.length() else 0L
    }

    fun getTotalStorageSizeBytes(context: Context): Long {
        val vaultDir = getVaultDirectory(context)
        val files = vaultDir.listFiles() ?: return 0L
        return files.filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Removes any file inside `filesDir/vault` whose filename is not present in [validStorageNames],
     * including leftover temporary `.part` files from interrupted imports.
     */
    fun cleanupOrphanFiles(context: Context, validStorageNames: Set<String>): OrphanCleanupResult {
        val vaultDir = getVaultDirectory(context)
        val entries = vaultDir.listFiles() ?: return OrphanCleanupResult(0, 0L)

        var deletedCount = 0
        var freedBytes = 0L

        for (file in entries) {
            if (!file.isFile) continue
            if (file.name !in validStorageNames) {
                val fileSize = file.length()
                if (file.delete()) {
                    deletedCount++
                    freedBytes += fileSize
                }
            }
        }

        if (deletedCount > 0) {
            SyncLogger.log("VAULT", "پاکسازی فایل‌های یتیم صندوق: $deletedCount فایل حذف و $freedBytes بایت آزاد شد")
        }

        return OrphanCleanupResult(deletedCount = deletedCount, freedBytes = freedBytes)
    }

    private fun resolveSafeVaultFile(context: Context, storageName: String): File? {
        if (storageName.isBlank() ||
            storageName.contains('/') ||
            storageName.contains('\\') ||
            storageName.contains("..")
        ) {
            return null
        }
        val vaultDir = getVaultDirectory(context)
        val candidate = File(vaultDir, storageName)
        return if (candidate.parentFile?.canonicalPath == vaultDir.canonicalPath) candidate else null
    }

    private fun formatSizeLimitExceededMessage(maxFileSizeBytes: Long): String {
        val mb = maxFileSizeBytes / (1024L * 1024L)
        return if (mb >= 1L && maxFileSizeBytes % (1024L * 1024L) == 0L) {
            "حجم فایل بیشتر از سقف مجاز ($mb مگابایت) است"
        } else {
            "حجم فایل بیشتر از سقف مجاز ($maxFileSizeBytes بایت) است"
        }
    }

    /**
     * Streaming AES-GCM decryptor with a fixed 64 KB read buffer.
     * Guarantees `cipher.doFinal()` is invoked explicitly at EOF so GCM tag mismatches
     * always fail loud with an [IOException] rather than being swallowed.
     */
    private class GcmStreamingDecryptInputStream(
        private val rawInput: InputStream,
        private val cipher: Cipher
    ) : InputStream() {

        private val readBuffer = ByteArray(STREAM_BUFFER_BYTES)
        private var outBuffer: ByteArray = ByteArray(0)
        private var outPos: Int = 0
        private var finished: Boolean = false
        private var singleByte = ByteArray(1)

        override fun read(): Int {
            val n = read(singleByte, 0, 1)
            return if (n == -1) -1 else (singleByte[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException()
            }

            while (outPos >= outBuffer.size) {
                if (finished) return -1
                if (!fillOutBuffer()) return -1
            }

            val available = outBuffer.size - outPos
            val toCopy = minOf(len, available)
            System.arraycopy(outBuffer, outPos, b, off, toCopy)
            outPos += toCopy
            return toCopy
        }

        private fun fillOutBuffer(): Boolean {
            while (!finished) {
                val read = rawInput.read(readBuffer)
                if (read == -1) {
                    finished = true
                    val finalBytes = try {
                        cipher.doFinal() ?: ByteArray(0)
                    } catch (t: Throwable) {
                        throw IOException("خطا در تأیید یکپارچگی فایل رمزنگاری‌شده صندوق", t)
                    }
                    outBuffer = finalBytes
                    outPos = 0
                    return outBuffer.isNotEmpty()
                } else if (read > 0) {
                    val decryptedChunk = cipher.update(readBuffer, 0, read)
                    if (decryptedChunk != null && decryptedChunk.isNotEmpty()) {
                        outBuffer = decryptedChunk
                        outPos = 0
                        return true
                    }
                }
            }
            return false
        }

        override fun close() {
            rawInput.close()
        }
    }
}
