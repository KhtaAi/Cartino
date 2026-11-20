package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ArchiveResult(
    val success: Boolean,
    val totalGroups: Int = 0,
    val totalFiles: Int = 0,
    val errorMessage: String? = null
)

data class RestoreResult(
    val success: Boolean,
    val manifest: VaultArchiveManifest? = null,
    val restoredStorageNames: Map<String, String> = emptyMap(),
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val errorMessage: String? = null
)

data class VaultArchiveGroupMetadata(
    val id: String,
    val name: String,
    val parentGroupId: String? = null,
    val createdAt: Long
)

data class VaultArchiveFileMetadata(
    val id: String,
    val groupId: String? = null,
    val displayName: String,
    val originalFileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageName: String,
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

data class VaultArchiveManifest(
    val timestamp: Long,
    val totpRequired: Boolean,
    val groups: List<VaultArchiveGroupMetadata>,
    val files: List<VaultArchiveFileMetadata>
)

/**
 * Creates streaming, self-contained encrypted `.cvault` archives of the Vault tab
 * completely independent of the `.enc` backup format.
 *
 * Binary layout:
 *  - Magic (13 bytes, ASCII): "CARTINO_VAULT"
 *  - Format version (4 bytes, big-endian Int): 1
 *  - Salt (16 bytes, random)
 *  - IV (12 bytes, random)
 *  - Single AES-256-GCM (`AES/GCM/NoPadding`, 128-bit tag) encrypted stream containing:
 *      1. Compressed manifest length (4 bytes, big-endian Int)
 *      2. GZIP-compressed JSON manifest bytes
 *      3. Sequential file entries, each:
 *          - storageName UTF-8 byte length (4 bytes, big-endian Int)
 *          - storageName UTF-8 bytes
 *          - decrypted content length (8 bytes, big-endian Long)
 *          - decrypted file content bytes (streamed with 64 KB buffer)
 *  - GCM authentication tag (16 bytes, appended automatically on CipherOutputStream.close)
 */
object VaultArchiveManager {

    const val MAGIC_HEADER = "CARTINO_VAULT"
    const val MAGIC_LENGTH_BYTES = 13
    const val FORMAT_VERSION = 1
    const val SALT_LENGTH_BYTES = 16
    const val IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128

    // Argon2id parameters aligned with BackupSyncManager specification
    const val ARGON2_ITERATIONS = 4
    const val ARGON2_MEMORY_KB = 65536
    const val ARGON2_PARALLELISM = 1
    const val DERIVED_KEY_LENGTH_BYTES = 32

    private const val STREAM_BUFFER_BYTES = 64 * 1024

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    internal val manifestAdapter = moshi.adapter(VaultArchiveManifest::class.java)

    suspend fun createVaultArchive(
        context: Context,
        targetUri: Uri,
        password: CharArray,
        totpCode: String? = null
    ): ArchiveResult = withContext(Dispatchers.IO) {
        if (password.isEmpty()) {
            return@withContext ArchiveResult(
                success = false,
                errorMessage = "رمز عبور برای ساخت آرشیو صندوق نمی‌تواند خالی باشد"
            )
        }

        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        val (totpEnabledInPrefs, totpSecretInPrefs) = readTotpConfig(appContext)
        val cleanTotpCode = totpCode?.trim()?.takeIf { it.isNotEmpty() }

        if (totpEnabledInPrefs) {
            if (cleanTotpCode == null) {
                return@withContext ArchiveResult(
                    success = false,
                    errorMessage = "احراز هویت دو مرحله‌ای فعال است؛ وارد کردن کد TOTP الزامی است"
                )
            }
            if (totpSecretInPrefs.isNotBlank() && !TotpManager.verifyTotp(totpSecretInPrefs, cleanTotpCode)) {
                return@withContext ArchiveResult(
                    success = false,
                    errorMessage = "کد احراز هویت دو مرحله‌ای (TOTP) نادرست است"
                )
            }
        }

        val totpRequired = totpEnabledInPrefs || cleanTotpCode != null
        val vaultDao = CartinoDatabase.getDatabase(appContext).vaultDao()
        val groups: List<VaultGroup> = vaultDao.getAllGroupsList()
        val files: List<VaultFile> = vaultDao.getAllFilesList()
            .sortedWith(compareBy<VaultFile> { it.createdAt }.thenBy { it.id })

        // Pre-check usable space when writing directly to a file:// URI
        if (targetUri.scheme == "file") {
            val targetPath = targetUri.path
            if (!targetPath.isNullOrBlank()) {
                val targetFile = File(targetPath)
                val parentUsableSpace = (targetFile.parentFile ?: targetFile).usableSpace
                val estimatedPlainBytes = files.sumOf { it.sizeBytes.coerceAtLeast(0L) } + 64L * 1024L
                if (parentUsableSpace in 1L until estimatedPlainBytes) {
                    SyncLogger.log("VAULT_ARCHIVE", "فضای خالی حافظه برای ساخت آرشیو صندوق کافی نیست")
                    return@withContext ArchiveResult(
                        success = false,
                        errorMessage = "فضای خالی حافظه دستگاه برای ساخت آرشیو صندوق کافی نیست"
                    )
                }
            }
        }

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }

        val derivedKeyBytes = deriveArchiveKey(
            password = password,
            salt = salt,
            totpCode = if (totpRequired) cleanTotpCode else null
        )

        var wroteToTarget = false
        try {
            val manifest = VaultArchiveManifest(
                timestamp = System.currentTimeMillis(),
                totpRequired = totpRequired,
                groups = groups.map { grp ->
                    VaultArchiveGroupMetadata(
                        id = grp.id,
                        name = grp.name,
                        parentGroupId = grp.parentGroupId,
                        createdAt = grp.createdAt
                    )
                },
                files = files.map { file ->
                    VaultArchiveFileMetadata(
                        id = file.id,
                        groupId = file.groupId,
                        displayName = file.displayName,
                        originalFileName = file.originalFileName,
                        mimeType = file.mimeType,
                        sizeBytes = file.sizeBytes,
                        sha256 = file.sha256,
                        storageName = file.storageName,
                        notes = file.notes,
                        createdAt = file.createdAt,
                        updatedAt = file.updatedAt
                    )
                }
            )

            val manifestJsonBytes = manifestAdapter.toJson(manifest).toByteArray(Charsets.UTF_8)
            val compressedManifestOut = ByteArrayOutputStream()
            GZIPOutputStream(compressedManifestOut).use { gzOut ->
                gzOut.write(manifestJsonBytes)
            }
            val gzippedManifestBytes = compressedManifestOut.toByteArray()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKeySpec = SecretKeySpec(derivedKeyBytes, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val rawOut = try {
                resolver.openOutputStream(targetUri, "w")
            } catch (_: Throwable) {
                resolver.openOutputStream(targetUri)
            } ?: throw IOException("امکان باز کردن مسیر خروجی برای نوشتن آرشیو وجود ندارد")

            wroteToTarget = true
            SyncLogger.log(
                "VAULT_ARCHIVE",
                "شروع ساخت آرشیو رمزنگاری‌شده صندوق (${groups.size} گروه، ${files.size} فایل)"
            )

            rawOut.use { outputStream ->
                val bufferedOut = BufferedOutputStream(outputStream, STREAM_BUFFER_BYTES)

                // Unencrypted header prefix: magic (13B) + version (4B) + salt (16B) + iv (12B)
                val magicBytes = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
                require(magicBytes.size == MAGIC_LENGTH_BYTES)
                bufferedOut.write(magicBytes)
                writeIntBigEndian(bufferedOut, FORMAT_VERSION)
                bufferedOut.write(salt)
                bufferedOut.write(iv)
                bufferedOut.flush()

                // Encrypted streaming payload via javax.crypto.CipherOutputStream (writes GCM tag on close)
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                CipherOutputStream(bufferedOut, cipher).use { cipherOut ->
                    // 1. Compressed manifest length (4B BE) + GZIP manifest bytes
                    writeIntBigEndian(cipherOut, gzippedManifestBytes.size)
                    cipherOut.write(gzippedManifestBytes)

                    // 2. Stream every vault file sequentially using fixed 64 KB buffer
                    for (file in files) {
                        val storageNameBytes = file.storageName.toByteArray(Charsets.UTF_8)
                        writeIntBigEndian(cipherOut, storageNameBytes.size)
                        cipherOut.write(storageNameBytes)
                        writeLongBigEndian(cipherOut, file.sizeBytes)

                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytesWrittenForFile = 0L

                        VaultStorageManager.openDecryptedInputStream(appContext, file.storageName)
                            .use { decryptedIn ->
                                while (true) {
                                    val read = decryptedIn.read(buffer)
                                    if (read == -1) break
                                    digest.update(buffer, 0, read)
                                    cipherOut.write(buffer, 0, read)
                                    bytesWrittenForFile += read
                                }
                            }

                        if (bytesWrittenForFile != file.sizeBytes) {
                            throw IOException(
                                "عدم تطابق حجم واقعی فایل «${file.displayName}» با متادیتای ثبت‌شده"
                            )
                        }

                        if (file.sha256.isNotBlank()) {
                            val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
                            if (!computedSha.equals(file.sha256, ignoreCase = true)) {
                                throw IOException(
                                    "یکپارچگی فایل «${file.displayName}» حین ساخت آرشیو تأیید نشد"
                                )
                            }
                        }
                    }

                    cipherOut.flush()
                }
            }

            SyncLogger.log(
                "VAULT_ARCHIVE",
                "آرشیو صندوق با موفقیت ساخته شد (${groups.size} گروه، ${files.size} فایل)"
            )
            ArchiveResult(
                success = true,
                totalGroups = groups.size,
                totalFiles = files.size,
                errorMessage = null
            )
        } catch (t: Throwable) {
            if (wroteToTarget) {
                cleanupIncompleteTarget(appContext, targetUri)
            }
            val reason = t.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "خطا در ساخت فایل آرشیو صندوق"
            SyncLogger.log("VAULT_ARCHIVE", "خطا در ساخت آرشیو صندوق: ${t.javaClass.simpleName} - $reason")
            ArchiveResult(
                success = false,
                totalGroups = 0,
                totalFiles = 0,
                errorMessage = reason
            )
        } finally {
            derivedKeyBytes.fill(0)
        }
    }

    private class VaultArchiveInvalidFormatException(message: String) : IOException(message)
    private class VaultArchiveIntegrityException(message: String) : IOException(message)

    suspend fun restoreVaultArchive(
        context: Context,
        sourceUri: Uri,
        password: CharArray,
        totpCode: String? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (password.isEmpty()) {
            return@withContext RestoreResult(
                success = false,
                errorMessage = "رمز عبور برای بازیابی آرشیو نمی‌تواند خالی باشد"
            )
        }

        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val stagedStorageNames = mutableListOf<String>()
        val restoredStorageMap = linkedMapOf<String, String>()
        var parsedManifest: VaultArchiveManifest? = null
        var derivedKeyBytes: ByteArray? = null

        try {
            val rawIn = resolver.openInputStream(sourceUri)
                ?: throw IOException("امکان باز کردن فایل آرشیو انتخاب‌شده وجود ندارد")

            rawIn.use { inputStream ->
                val bufferedIn = BufferedInputStream(inputStream, STREAM_BUFFER_BYTES)

                // 1. Validate magic header (13 ASCII bytes) and format version (4B BE Int = 1)
                val expectedMagic = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
                val magicBytes = readExactBytesOrNull(bufferedIn, MAGIC_LENGTH_BYTES)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                if (!magicBytes.contentEquals(expectedMagic)) {
                    throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                }

                val version = readIntBigEndianOrNull(bufferedIn)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                if (version != FORMAT_VERSION) {
                    throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                }

                val salt = readExactBytesOrNull(bufferedIn, SALT_LENGTH_BYTES)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                val iv = readExactBytesOrNull(bufferedIn, IV_LENGTH_BYTES)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")

                // 2. Derive 32-byte key via Argon2id (iterations=4, memory=65536, parallelism=1)
                derivedKeyBytes = deriveArchiveKey(
                    password = password,
                    salt = salt,
                    totpCode = totpCode
                )

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val secretKeySpec = SecretKeySpec(derivedKeyBytes, "AES")
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

                CipherInputStream(bufferedIn, cipher).use { cipherIn ->
                    fun drainToVerifyGcmTag() {
                        val drainBuf = ByteArray(8192)
                        while (cipherIn.read(drainBuf) != -1) {
                            // Exhaust stream so CipherInputStream runs cipher.doFinal()
                        }
                    }

                    // 3. Read length-prefixed GZIP manifest JSON
                    val manifestLen = readIntBigEndianOrNull(cipherIn)
                        ?: run {
                            drainToVerifyGcmTag()
                            throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                        }
                    if (manifestLen !in 1..(32 * 1024 * 1024)) {
                        drainToVerifyGcmTag()
                        throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                    }

                    val gzippedManifest = try {
                        readExactBytesOrThrow(cipherIn, manifestLen)
                    } catch (e: Throwable) {
                        drainToVerifyGcmTag()
                        throw e
                    }

                    if (gzippedManifest.size < 2 ||
                        gzippedManifest[0] != 0x1f.toByte() ||
                        gzippedManifest[1] != 0x8b.toByte()
                    ) {
                        drainToVerifyGcmTag()
                        throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                    }

                    val manifestJson = try {
                        GZIPInputStream(ByteArrayInputStream(gzippedManifest))
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    } catch (e: Throwable) {
                        drainToVerifyGcmTag()
                        throw e
                    }

                    val manifest = manifestAdapter.fromJson(manifestJson) ?: run {
                        drainToVerifyGcmTag()
                        throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                    }
                    parsedManifest = manifest

                    val manifestByStorageName = manifest.files.associateBy { it.storageName }
                    val totalFiles = manifest.files.size
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)

                    // 4. Stream each file one-by-one, verify SHA-256, immediately hand off to importFromBytes, then release
                    for ((index, manifestEntry) in manifest.files.withIndex()) {
                        val nameLen = readIntBigEndianOrNull(cipherIn)
                            ?: run {
                                drainToVerifyGcmTag()
                                throw EOFException("ساختار فایل‌های آرشیو ناقص است")
                            }
                        if (nameLen !in 1..4096) {
                            drainToVerifyGcmTag()
                            throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                        }
                        val nameBytes = try {
                            readExactBytesOrThrow(cipherIn, nameLen)
                        } catch (e: Throwable) {
                            drainToVerifyGcmTag()
                            throw e
                        }
                        val entryStorageName = String(nameBytes, Charsets.UTF_8)

                        val contentLen = readLongBigEndianOrNull(cipherIn)
                            ?: run {
                                drainToVerifyGcmTag()
                                throw EOFException("ساختار طول فایل در آرشیو ناقص است")
                            }
                        if (contentLen < 0L || contentLen > VaultStorageManager.DEFAULT_MAX_FILE_SIZE_BYTES) {
                            drainToVerifyGcmTag()
                            throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                        }

                        val expectedFile = manifestByStorageName[entryStorageName] ?: manifestEntry
                        val fileLenInt = contentLen.toInt()
                        val fileBytes = ByteArray(fileLenInt)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var offset = 0

                        while (offset < fileLenInt) {
                            val toRead = minOf(buffer.size, fileLenInt - offset)
                            val read = cipherIn.read(buffer, 0, toRead)
                            if (read == -1) {
                                throw EOFException("پایان غیرمنتظره جریان هنگام خواندن فایل «${expectedFile.displayName}»")
                            }
                            System.arraycopy(buffer, 0, fileBytes, offset, read)
                            digest.update(buffer, 0, read)
                            offset += read
                        }

                        if (expectedFile.sha256.isNotBlank()) {
                            val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
                            if (!computedSha.equals(expectedFile.sha256, ignoreCase = true)) {
                                throw VaultArchiveIntegrityException(
                                    "یکپارچگی فایل ${expectedFile.displayName} تأیید نشد"
                                )
                            }
                        }

                        // Immediately encrypt and stage into VaultStorageManager using a fresh unique storageName,
                        // allowing fileBytes to be garbage collected as soon as this iteration finishes.
                        val stagedName = VaultStorageManager.importFromBytes(
                            context = appContext,
                            storageName = "${UUID.randomUUID()}.vault",
                            bytes = fileBytes
                        )
                        stagedStorageNames.add(stagedName)
                        restoredStorageMap[entryStorageName] = stagedName
                        onProgress?.invoke(index + 1, totalFiles)
                    }

                    // 5. Exhaust CipherInputStream to EOF so cipher.doFinal() strictly verifies the 16-byte GCM tag
                    val trailingBuf = ByteArray(1024)
                    while (cipherIn.read(trailingBuf) != -1) {
                        // Drain remaining bytes for GCM tag authentication
                    }
                }
            }

            val manifestFinal = requireNotNull(parsedManifest)
            SyncLogger.log(
                "VAULT_ARCHIVE",
                "رمزگشایی و تأیید آرشیو صندوق با موفقیت انجام شد (${manifestFinal.groups.size} گروه، ${restoredStorageMap.size} فایل)"
            )
            RestoreResult(
                success = true,
                manifest = manifestFinal,
                restoredStorageNames = restoredStorageMap,
                successCount = restoredStorageMap.size,
                failedCount = 0,
                errorMessage = null
            )
        } catch (t: Throwable) {
            // Roll back any staged encrypted files so partial restores never remain on disk
            for (staged in stagedStorageNames) {
                VaultStorageManager.deleteEncryptedFile(appContext, staged)
            }

            val errorMsg = when {
                t is VaultArchiveInvalidFormatException -> "فرمت فایل نامعتبر است"
                t is VaultArchiveIntegrityException -> t.message ?: "یکپارچگی فایل تأیید نشد"
                hasThrowableCause<AEADBadTagException>(t) ->
                    "رمز عبور اشتباه است یا یکپارچگی فایل آرشیو تأیید نشد (GCM tag verification failed)"
                else -> t.localizedMessage?.takeIf { it.isNotBlank() } ?: "خطا در بازیابی فایل آرشیو صندوق"
            }

            SyncLogger.log("VAULT_ARCHIVE", "خطا در بازیابی آرشیو صندوق: $errorMsg")
            RestoreResult(
                success = false,
                manifest = null,
                restoredStorageNames = emptyMap(),
                successCount = 0,
                failedCount = parsedManifest?.files?.size ?: 0,
                errorMessage = errorMsg
            )
        } finally {
            derivedKeyBytes?.fill(0)
        }
    }

    suspend fun inspectVaultArchive(
        context: Context,
        sourceUri: Uri,
        password: CharArray,
        totpCode: String? = null
    ): VaultArchiveManifest = withContext(Dispatchers.IO) {
        if (password.isEmpty()) {
            throw IllegalArgumentException("رمز عبور برای بررسی آرشیو نمی‌تواند خالی باشد")
        }

        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        val rawIn = resolver.openInputStream(sourceUri)
            ?: throw IOException("امکان باز کردن فایل آرشیو وجود ندارد")

        var derivedKeyBytes: ByteArray? = null
        try {
            rawIn.use { inputStream ->
                val bufferedIn = BufferedInputStream(inputStream, STREAM_BUFFER_BYTES)

                val expectedMagic = MAGIC_HEADER.toByteArray(Charsets.US_ASCII)
                val magicBytes = readExactBytesOrNull(bufferedIn, MAGIC_LENGTH_BYTES)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                if (!magicBytes.contentEquals(expectedMagic)) {
                    throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                }

                val version = readIntBigEndianOrNull(bufferedIn)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                if (version != FORMAT_VERSION) {
                    throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                }

                val salt = readExactBytesOrNull(bufferedIn, SALT_LENGTH_BYTES)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                val iv = readExactBytesOrNull(bufferedIn, IV_LENGTH_BYTES)
                    ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")

                derivedKeyBytes = deriveArchiveKey(
                    password = password,
                    salt = salt,
                    totpCode = totpCode
                )

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val secretKeySpec = SecretKeySpec(derivedKeyBytes, "AES")
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

                CipherInputStream(bufferedIn, cipher).use { cipherIn ->
                    val manifestLen = readIntBigEndianOrNull(cipherIn)
                        ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                    if (manifestLen !in 1..(32 * 1024 * 1024)) {
                        throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                    }

                    val gzippedManifest = readExactBytesOrThrow(cipherIn, manifestLen)
                    if (gzippedManifest.size < 2 ||
                        gzippedManifest[0] != 0x1f.toByte() ||
                        gzippedManifest[1] != 0x8b.toByte()
                    ) {
                        throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                    }

                    val manifestJson = GZIPInputStream(ByteArrayInputStream(gzippedManifest))
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }

                    manifestAdapter.fromJson(manifestJson)
                        ?: throw VaultArchiveInvalidFormatException("فرمت فایل نامعتبر است")
                }
            }
        } finally {
            derivedKeyBytes?.fill(0)
        }
    }

    internal fun deriveArchiveKey(
        password: CharArray,
        salt: ByteArray,
        totpCode: String? = null
    ): ByteArray {
        val cleanTotp = totpCode?.trim()?.takeIf { it.isNotEmpty() }
        val combinedChars = if (cleanTotp != null) {
            val totpChars = cleanTotp.toCharArray()
            CharArray(password.size + 1 + totpChars.size).also { out ->
                System.arraycopy(password, 0, out, 0, password.size)
                out[password.size] = ':'
                System.arraycopy(totpChars, 0, out, password.size + 1, totpChars.size)
                totpChars.fill('\u0000')
            }
        } else {
            password.copyOf()
        }

        val charBuffer = CharBuffer.wrap(combinedChars)
        val byteBuffer = Charsets.UTF_8.encode(charBuffer)
        val keyInputBytes = ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }

        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON2_ITERATIONS)
                .withMemoryAsKB(ARGON2_MEMORY_KB)
                .withParallelism(ARGON2_PARALLELISM)
                .withSalt(salt)
                .build()

            val generator = Argon2BytesGenerator()
            generator.init(params)
            val derived = ByteArray(DERIVED_KEY_LENGTH_BYTES)
            generator.generateBytes(keyInputBytes, derived, 0, derived.size)
            derived
        } finally {
            combinedChars.fill('\u0000')
            if (byteBuffer.hasArray()) {
                byteBuffer.array().fill(0)
            }
            keyInputBytes.fill(0)
        }
    }

    private fun readTotpConfig(context: Context): Pair<Boolean, String> {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "cartino_encrypted_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val enabled = prefs.getString("totp_enabled", "false") == "true"
            val secret = prefs.getString("totp_secret", "") ?: ""
            enabled to secret
        } catch (_: Throwable) {
            false to ""
        }
    }

    private fun cleanupIncompleteTarget(context: Context, targetUri: Uri) {
        runCatching {
            when (targetUri.scheme) {
                "file" -> {
                    val file = targetUri.path?.let { File(it) }
                    if (file != null && file.exists()) {
                        file.delete()
                    }
                }
                "content" -> {
                    val deletedViaDoc = runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, targetUri)
                    }.getOrDefault(false)
                    if (!deletedViaDoc) {
                        runCatching { context.contentResolver.delete(targetUri, null, null) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun writeIntBigEndian(out: OutputStream, value: Int) {
        val bytes = ByteBuffer.allocate(4).putInt(value).array()
        out.write(bytes)
    }

    private fun writeLongBigEndian(out: OutputStream, value: Long) {
        val bytes = ByteBuffer.allocate(8).putLong(value).array()
        out.write(bytes)
    }

    private fun readExactBytesOrNull(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    private fun readExactBytesOrThrow(input: InputStream, count: Int): ByteArray {
        return readExactBytesOrNull(input, count)
            ?: throw EOFException("پایان غیرمنتظره جریان هنگام خواندن $count بایت")
    }

    private fun readIntBigEndianOrNull(input: InputStream): Int? {
        val bytes = readExactBytesOrNull(input, 4) ?: return null
        return ByteBuffer.wrap(bytes).int
    }

    private fun readLongBigEndianOrNull(input: InputStream): Long? {
        val bytes = readExactBytesOrNull(input, 8) ?: return null
        return ByteBuffer.wrap(bytes).long
    }

    private inline fun <reified T : Throwable> hasThrowableCause(throwable: Throwable): Boolean {
        return generateSequence<Throwable>(throwable) { it.cause }.any { it is T }
    }
}
