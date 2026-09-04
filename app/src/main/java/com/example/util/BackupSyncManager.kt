package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.local.CartinoDatabase
import com.example.data.model.BankCard
import com.example.data.model.IdentityDocument
import com.example.data.model.PasswordEntry
import com.example.data.model.PasswordGroup
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackupRestoreResult(
    val cardsCount: Int,
    val docsCount: Int,
    val passwordsCount: Int,
    val newGroupsCount: Int = 0,
    val reusedGroupsCount: Int = 0,
    val updatedPasswordsCount: Int = 0,
    val skippedPasswordsCount: Int = 0
)

data class BackupPayload(
    val version: Int = 2,
    val totpSecret: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val totpRequired: Boolean = false,
    val cards: List<BankCard> = emptyList(),
    val documents: List<IdentityDocument> = emptyList(),
    val passwordGroups: List<PasswordGroup> = emptyList(),
    val passwordEntries: List<PasswordEntry> = emptyList()
)

data class WebDavConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remotePath: String = "cartino_backup.enc"
)

object BackupSyncManager {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun isTotpEnabledInPrefs(context: Context): Boolean {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "cartino_encrypted_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("totp_enabled", "false") == "true"
        } catch (e: Throwable) {
            false
        }
    }

    private fun getTotpSecretFromPrefs(context: Context): String {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "cartino_encrypted_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("totp_secret", "") ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    private fun saveTotpSecretToPrefs(context: Context, secret: String) {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "cartino_encrypted_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit()
                .putString("totp_secret", secret)
                .putString("totp_enabled", "true")
                .apply()
        } catch (e: Throwable) {
            SyncLogger.log("RESTORE", "خطا در ذخیره‌سازی امن TOTP Secret: ${e.message}")
            throw IllegalStateException("امکان ذخیره‌سازی امن TOTP Secret در سیستم رمزنگاری دستگاه وجود ندارد: ${e.localizedMessage}", e)
        }
    }

    /**
     * Generates an encrypted backup payload string from Room DB using user password.
     */
    suspend fun createEncryptedBackupPayload(context: Context, password: String): String = withContext(Dispatchers.IO) {
        SyncLogger.log("BACKUP", "شروع ساخت بسته رمزنگاری شده...")
        val db = CartinoDatabase.getDatabase(context)
        val cards = db.bankCardDao().getAllCards().first().map { it.decrypted() }
        val docs = db.identityDocumentDao().getAllDocuments().first().map { it.decrypted() }
        val groups = db.passwordDao().getAllGroups().first()
        val entries = db.passwordDao().getAllEntries().first().map { it.decrypted() }

        val totpActive = isTotpEnabledInPrefs(context)
        val secret = if (totpActive) getTotpSecretFromPrefs(context) else ""
        if (totpActive && secret.isNotBlank()) {
            SyncLogger.log("BACKUP", "Secret TOTP با موفقیت در فایل پشتیبان ذخیره شد")
        }
        SyncLogger.log("BACKUP", "تعداد کارت‌ها: ${cards.size} | تعداد مدارک: ${docs.size} | تعداد گروه‌های پسورد: ${groups.size} | تعداد پسوردها: ${entries.size} | TOTP: ${if (totpActive) "فعال" else "غیرفعال"}")

        val payload = BackupPayload(
            version = 2,
            totpSecret = secret,
            totpRequired = totpActive,
            cards = cards,
            documents = docs,
            passwordGroups = groups,
            passwordEntries = entries
        )

        val adapter = moshi.adapter(BackupPayload::class.java)
        val jsonString = adapter.toJson(payload)

        val encrypted = SecurityManager.encryptData(jsonString, password.toCharArray())
        SyncLogger.log("BACKUP", "بسته AES-256 (Argon2id) آماده شد. حجم فایل رمز شده: ${encrypted.length} کاراکتر")
        encrypted
    }

    /**
     * Inspects backup payload metadata (totpRequired, hasTotpSecret) using user password.
     */
    suspend fun inspectBackupMetadata(
        encryptedBase64: String,
        password: String
    ): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val decryptedJson = try {
            SecurityManager.decryptData(encryptedBase64, password.toCharArray())
        } catch (e: Exception) {
            SyncLogger.log("RESTORE", "خطا در رمزگشایی: کلمه عبور اشتباه است یا ساختار فایل آسیب دیده. (${e.message})")
            throw e
        }

        val adapter = moshi.adapter(BackupPayload::class.java)
        val payload = adapter.fromJson(decryptedJson) ?: run {
            SyncLogger.log("RESTORE", "خطا: ساختار JSON بسته بازیابی معتبر نیست")
            throw IllegalArgumentException("فرمت محتوای بک‌آپ خوانا نیست")
        }

        Pair(payload.totpRequired, payload.totpSecret.isNotBlank())
    }

    /**
     * Inspects backup file metadata directly from a SAF Uri.
     */
    suspend fun inspectBackupMetadataFromUri(
        context: Context,
        uri: Uri,
        password: String
    ): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val payloadText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: run {
                SyncLogger.log("LOCAL_FILE", "خطا: عدم امکان خواندن فایل از URI")
                throw IllegalStateException("امکان خواندن فایل انتخاب‌شده وجود ندارد")
            }
        inspectBackupMetadata(payloadText, password)
    }

    /**
     * Restores records into Room DB from an encrypted backup payload.
     */
    suspend fun restoreFromEncryptedPayload(
        context: Context,
        encryptedBase64: String,
        password: String,
        totpCode: String? = null
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        SyncLogger.log("RESTORE", "شروع رمزگشایی بسته داده‌ها...")
        val decryptedJson = try {
            SecurityManager.decryptData(encryptedBase64, password.toCharArray())
        } catch (e: Exception) {
            SyncLogger.log("RESTORE", "خطا در رمزگشایی: کلمه عبور اشتباه است یا ساختار فایل آسیب دیده. (${e.message})")
            throw e
        }

        val adapter = moshi.adapter(BackupPayload::class.java)
        val payload = adapter.fromJson(decryptedJson) ?: run {
            SyncLogger.log("RESTORE", "خطا: ساختار JSON بسته بازیابی معتبر نیست")
            throw IllegalArgumentException("فرمت محتوای بک‌آپ خوانا نیست")
        }

        if (payload.totpRequired) {
            SyncLogger.log("RESTORE", "این فایل پشتیبان به احراز هویت دو مرحله‌ای (TOTP) نیاز دارد")
            val backupSecret = payload.totpSecret
            if (backupSecret.isBlank()) {
                SyncLogger.log("RESTORE", "خطا: فایل پشتیبان قدیمی است و Secret TOTP در آن موجود نیست")
                throw IllegalArgumentException("این فایل پشتیبان قدیمی است و Secret TOTP در آن ذخیره نشده. لطفاً از نسخه جدیدتر بکآپ بگیرید.")
            }

            if (totpCode.isNullOrBlank() || !TotpManager.verifyTotp(backupSecret, totpCode)) {
                SyncLogger.log("RESTORE", "خطا: کد TOTP واردشده اشتباه است یا با Secret بکآپ مطابقت ندارد")
                throw IllegalArgumentException("کد TOTP واردشده با Secret ذخیره‌شده در بکآپ مطابقت ندارد.")
            }

            saveTotpSecretToPrefs(context, backupSecret)
            SyncLogger.log("RESTORE", "TOTP Secret از بکآپ بازیابی و در دستگاه فعال شد.")
        }

        val db = CartinoDatabase.getDatabase(context)

        var addedCards = 0
        var addedDocs = 0
        var addedPasswords = 0
        var updatedPasswords = 0
        var skippedPasswords = 0
        var newGroupsCount = 0
        var reusedGroupsCount = 0

        payload.cards.forEach { card ->
            db.bankCardDao().insertCard(card.encrypted())
            addedCards++
        }

        payload.documents.forEach { doc ->
            db.identityDocumentDao().insertDocument(doc.encrypted())
            addedDocs++
        }

        // Map old groupId to resolved DB groupId
        val groupIdMap = mutableMapOf<String, String>()

        // Resolve password groups hierarchically to preserve tree structure
        val remainingGroups = payload.passwordGroups.toMutableList()
        var maxIterations = remainingGroups.size + 1
        while (remainingGroups.isNotEmpty() && maxIterations > 0) {
            maxIterations--
            val iter = remainingGroups.iterator()
            var progressed = false
            while (iter.hasNext()) {
                val group = iter.next()
                val parentResolved = group.parentGroupId == null || groupIdMap.containsKey(group.parentGroupId)
                if (parentResolved) {
                    val mappedParentId = group.parentGroupId?.let { groupIdMap[it] ?: it }
                    val existing = db.passwordDao().findGroupByNameAndParent(group.name.trim(), mappedParentId)
                    if (existing != null) {
                        groupIdMap[group.id] = existing.id
                        reusedGroupsCount++
                    } else {
                        val newGroup = PasswordGroup(
                            name = group.name.trim(),
                            parentGroupId = mappedParentId,
                            createdAt = group.createdAt
                        )
                        db.passwordDao().insertGroup(newGroup)
                        groupIdMap[group.id] = newGroup.id
                        newGroupsCount++
                    }
                    iter.remove()
                    progressed = true
                }
            }
            if (!progressed && remainingGroups.isNotEmpty()) {
                // If any orphaned groups remain, resolve them under root
                val group = remainingGroups.removeAt(0)
                val existing = db.passwordDao().findGroupByNameAndParent(group.name.trim(), null)
                if (existing != null) {
                    groupIdMap[group.id] = existing.id
                    reusedGroupsCount++
                } else {
                    val newGroup = PasswordGroup(
                        name = group.name.trim(),
                        parentGroupId = null,
                        createdAt = group.createdAt
                    )
                    db.passwordDao().insertGroup(newGroup)
                    groupIdMap[group.id] = newGroup.id
                    newGroupsCount++
                }
            }
        }

        // Load existing entries in DB to perform smart deduplication
        val existingDbEntries = db.passwordDao().getAllEntriesList().mapNotNull { raw ->
            runCatching { raw.decrypted() }.getOrNull()
        }
        val existingHashesByGroup = mutableMapOf<String?, MutableSet<String>>()
        val existingByIdentity = mutableMapOf<String?, MutableMap<Triple<String, String, String>, PasswordEntry>>()

        for (item in existingDbEntries) {
            val h = if (item.contentHash.isNotBlank()) item.contentHash else PasswordEntry.computeHash(item)
            existingHashesByGroup.getOrPut(item.groupId) { mutableSetOf() }.add(h)
            val key = Triple(item.title.trim().lowercase(), item.username.trim().lowercase(), item.website.trim().lowercase())
            existingByIdentity.getOrPut(item.groupId) { mutableMapOf() }[key] = item
        }

        for (rawEntry in payload.passwordEntries) {
            val plain = runCatching { rawEntry.decrypted() }.getOrDefault(rawEntry)
            val targetGroupId = if (plain.groupId == null) {
                // Problem 4: Auto-group by domain for root items without group
                val domainTitle = DomainUtils.extractDomainTitle(plain.website, plain.title)
                val existingDomainGroup = db.passwordDao().findGroupByNameAndParent(domainTitle, null)
                if (existingDomainGroup != null) {
                    existingDomainGroup.id
                } else {
                    val newDomainGroup = PasswordGroup(name = domainTitle, parentGroupId = null)
                    db.passwordDao().insertGroup(newDomainGroup)
                    newGroupsCount++
                    newDomainGroup.id
                }
            } else {
                groupIdMap[plain.groupId] ?: plain.groupId
            }

            val hash = PasswordEntry.computeHash(plain)
            val identityKey = Triple(plain.title.trim().lowercase(), plain.username.trim().lowercase(), plain.website.trim().lowercase())

            val groupHashes = existingHashesByGroup.getOrPut(targetGroupId) { mutableSetOf() }
            val groupIdentities = existingByIdentity.getOrPut(targetGroupId) { mutableMapOf() }

            if (groupHashes.contains(hash)) {
                // Exact duplicate content -> skip
                skippedPasswords++
                continue
            }

            val existingEntry = groupIdentities[identityKey]
            if (existingEntry != null) {
                // Identity matches but content changed -> update
                val updated = existingEntry.copy(
                    groupId = targetGroupId,
                    password = plain.password,
                    notes = plain.notes,
                    customFieldsJson = plain.customFieldsJson,
                    isFavorite = plain.isFavorite,
                    contentHash = hash,
                    updatedAt = System.currentTimeMillis()
                )
                db.passwordDao().insertEntry(updated.encrypted())
                groupHashes.add(hash)
                groupIdentities[identityKey] = updated
                updatedPasswords++
            } else {
                // Brand new entry -> insert
                val newEntry = plain.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    groupId = targetGroupId,
                    contentHash = hash,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                db.passwordDao().insertEntry(newEntry.encrypted())
                groupHashes.add(hash)
                groupIdentities[identityKey] = newEntry
                addedPasswords++
            }
        }

        SyncLogger.log(
            "RESTORE",
            "بازیابی با موفقیت انجام شد: $addedCards کارت، $addedDocs مدرک، $addedPasswords پسورد جدید، $updatedPasswords به‌روزرسانی، $skippedPasswords ردشده ($newGroupsCount گروه جدید، $reusedGroupsCount گروه تطبیق‌یافته)"
        )
        BackupRestoreResult(
            cardsCount = addedCards,
            docsCount = addedDocs,
            passwordsCount = addedPasswords,
            newGroupsCount = newGroupsCount,
            reusedGroupsCount = reusedGroupsCount,
            updatedPasswordsCount = updatedPasswords,
            skippedPasswordsCount = skippedPasswords
        )
    }

    /**
     * Creates an encrypted backup file directly to a user-chosen SAF Uri on local storage.
     */
    suspend fun createEncryptedBackupToUri(context: Context, uri: Uri, password: String) = withContext(Dispatchers.IO) {
        SyncLogger.log("LOCAL_FILE", "نوشتن بک‌آپ محلی در مسیر URI: $uri")
        val payload = createEncryptedBackupPayload(context, password)
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(payload.toByteArray(Charsets.UTF_8))
        } ?: run {
            SyncLogger.log("LOCAL_FILE", "خطا: عدم امکان بازکردن Output Stream در URI")
            throw IllegalStateException("امکان نوشتن در مسیر انتخاب‌شده وجود ندارد")
        }
        SyncLogger.log("LOCAL_FILE", "ذخیره‌سازی فایل محلی با موفقیت به پایان رسید")
    }

    /**
     * Restores encrypted backup file directly from a user-chosen SAF Uri.
     */
    suspend fun restoreFromEncryptedUri(
        context: Context,
        uri: Uri,
        password: String,
        totpCode: String? = null
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        SyncLogger.log("LOCAL_FILE", "خواندن فایل بک‌آپ محلی از URI: $uri")
        val payloadText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: run {
                SyncLogger.log("LOCAL_FILE", "خطا: عدم امکان خواندن فایل از URI")
                throw IllegalStateException("امکان خواندن فایل انتخاب‌شده وجود ندارد")
            }
        restoreFromEncryptedPayload(context, payloadText, password, totpCode)
    }

    private fun extractHost(url: String): String {
        val clean = url.trim()
        if (clean.isBlank()) return ""
        return runCatching {
            val uri = java.net.URI(if (clean.contains("://")) clean else "http://$clean")
            uri.host ?: clean
        }.getOrDefault(clean)
    }

    private fun maskUsername(username: String): String {
        val clean = username.trim()
        return if (clean.length >= 2) {
            clean.take(2) + "***"
        } else {
            "***"
        }
    }

    private fun extractFileName(remotePath: String): String {
        val clean = remotePath.trim().removePrefix("/")
        val lastSlash = clean.lastIndexOf('/')
        val name = if (lastSlash >= 0) clean.substring(lastSlash + 1) else clean
        return if (name.isBlank()) "cartino_backup.enc" else if (name.endsWith(".enc", ignoreCase = true)) name else "$name.enc"
    }

    /**
     * Uploads backup file to WebDAV server (e.g. Koofr, Nextcloud, personal server).
     */
    suspend fun uploadToWebDav(config: WebDavConfig, encryptedPayload: String): Boolean = withContext(Dispatchers.IO) {
        val serverUrlClean = config.serverUrl.trim()
        val usernameClean = config.username.trim()
        val passwordClean = config.password.trim()
        val remotePathClean = config.remotePath.trim().removePrefix("/")

        val serverHost = extractHost(serverUrlClean)
        val maskedUser = maskUsername(usernameClean)
        val fileNameOnly = extractFileName(remotePathClean)

        SyncLogger.log("WEBDAV_PUT", "شروع فرایند آپلود WebDAV...")
        SyncLogger.log("WEBDAV_PUT", "آدرس سرور: '$serverHost'")
        SyncLogger.log("WEBDAV_PUT", "نام کاربری: '$maskedUser'")
        SyncLogger.log("WEBDAV_PUT", "طول کلید/رمزعبور: ${passwordClean.length} کاراکتر")

        if (serverUrlClean.isBlank() || usernameClean.isBlank() || passwordClean.isBlank()) {
            SyncLogger.log("WEBDAV_PUT", "خطا: مشخصات اتصال کامل نیست")
            throw IllegalArgumentException("آدرس سرور، نام کاربری و کلمه عبور WebDAV را وارد و تایید کنید")
        }

        val baseUrl = if (serverUrlClean.endsWith("/")) serverUrlClean else "$serverUrlClean/"
        val fileName = if (remotePathClean.isBlank()) "cartino_backup.enc" else if (remotePathClean.endsWith(".enc", ignoreCase = true)) remotePathClean else "$remotePathClean.enc"
        val targetUrl = "$baseUrl$fileName"

        SyncLogger.log("WEBDAV_PUT", "فایل مقصد آپلود: $fileNameOnly")

        val credential = Credentials.basic(usernameClean, passwordClean, Charsets.UTF_8)
        val requestBody = encryptedPayload.toRequestBody("text/plain; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", credential)
            .header("User-Agent", "Cartino-Android-WebDAV/1.0")
            .header("Accept", "*/*")
            .put(requestBody)
            .build()

        try {
            SyncLogger.log("WEBDAV_PUT", "در حال ارسال درخواست HTTP PUT...")
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val message = response.message
            SyncLogger.log("WEBDAV_PUT", "پاسخ دریافتی از سرور: کد HTTP $code ($message)")

            if (!response.isSuccessful) {
                when (code) {
                    401 -> {
                        SyncLogger.log("WEBDAV_PUT", "خطای 401 Unauthorized: نام کاربری یا App Token سرویس Koofr اشتباه است. لطفا App Token معتبر ایجاد و وارد کنید.")
                        throw IllegalStateException("خطای احراز هویت سرور WebDAV (کد 401): نام کاربری یا App Token اشتباه است.")
                    }
                    403 -> {
                        SyncLogger.log("WEBDAV_PUT", "خطای 403 Forbidden: عدم داشتن دسترسی لازم در سرور.")
                        throw IllegalStateException("خطا در دسترسی سرور (کد 403): عدم مجوز نوشتن در پوشه.")
                    }
                    404 -> {
                        SyncLogger.log("WEBDAV_PUT", "خطای 404 Not Found: مسیر پوشه در سرور یافت نشد.")
                        throw IllegalStateException("مسیر سرور WebDAV یافت نشد (کد 404). پوشه را در سرور بررسی کنید.")
                    }
                    else -> {
                        SyncLogger.log("WEBDAV_PUT", "خطا در آپلود به سرور (کد $code)")
                        throw IllegalStateException("خطا در آپلود به سرور WebDAV (کد: $code)")
                    }
                }
            }
            SyncLogger.log("WEBDAV_PUT", "آپلود فایل با موفقیت کامل انجام شد.")
            true
        } catch (e: IOException) {
            SyncLogger.log("WEBDAV_PUT", "خطای ارتباط شبکه: ${e.localizedMessage}")
            throw e
        }
    }

    /**
     * Downloads backup file from WebDAV server.
     */
    suspend fun downloadFromWebDav(config: WebDavConfig): String = withContext(Dispatchers.IO) {
        val serverUrlClean = config.serverUrl.trim()
        val usernameClean = config.username.trim()
        val passwordClean = config.password.trim()
        val remotePathClean = config.remotePath.trim().removePrefix("/")

        val serverHost = extractHost(serverUrlClean)
        val maskedUser = maskUsername(usernameClean)
        val fileNameOnly = extractFileName(remotePathClean)

        SyncLogger.log("WEBDAV_GET", "شروع فرایند دریافت از سرور WebDAV...")
        SyncLogger.log("WEBDAV_GET", "آدرس سرور: '$serverHost'")
        SyncLogger.log("WEBDAV_GET", "نام کاربری: '$maskedUser'")

        if (serverUrlClean.isBlank() || usernameClean.isBlank() || passwordClean.isBlank()) {
            SyncLogger.log("WEBDAV_GET", "خطا: مشخصات اتصال کامل نیست")
            throw IllegalArgumentException("اطلاعات سرور، نام کاربری و کلمه عبور WebDAV کامل نیست")
        }

        val baseUrl = if (serverUrlClean.endsWith("/")) serverUrlClean else "$serverUrlClean/"
        val fileName = if (remotePathClean.isBlank()) "cartino_backup.enc" else if (remotePathClean.endsWith(".enc", ignoreCase = true)) remotePathClean else "$remotePathClean.enc"
        val targetUrl = "$baseUrl$fileName"

        SyncLogger.log("WEBDAV_GET", "فایل مقصد دریافت: $fileNameOnly")

        val credential = Credentials.basic(usernameClean, passwordClean, Charsets.UTF_8)

        val request = Request.Builder()
            .url(targetUrl)
            .header("Authorization", credential)
            .header("User-Agent", "Cartino-Android-WebDAV/1.0")
            .header("Accept", "*/*")
            .get()
            .build()

        try {
            SyncLogger.log("WEBDAV_GET", "در حال ارسال درخواست HTTP GET...")
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val message = response.message
            SyncLogger.log("WEBDAV_GET", "پاسخ دریافتی: کد HTTP $code ($message)")

            if (!response.isSuccessful) {
                when (code) {
                    401 -> {
                        SyncLogger.log("WEBDAV_GET", "خطای 401 Unauthorized: احراز هویت ناموفق بود. نام کاربری یا App Token را چک کنید.")
                        throw IllegalStateException("خطای احراز هویت WebDAV (کد 401): نام کاربری یا App Token اشتباه است.")
                    }
                    404 -> {
                        SyncLogger.log("WEBDAV_GET", "خطای 404 Not Found: فایل '$fileNameOnly' در سرور یافت نشد.")
                        throw IllegalStateException("فایل بک‌آپ با نام $fileNameOnly در سرور WebDAV یافت نشد (کد: 404)")
                    }
                    else -> {
                        SyncLogger.log("WEBDAV_GET", "خطا در دریافت فایل از سرور (کد $code)")
                        throw IllegalStateException("خطا در دریافت فایل از WebDAV (کد: $code)")
                    }
                }
            }

            val bodyText = response.body?.string() ?: run {
                SyncLogger.log("WEBDAV_GET", "خطا: بدنه پاسخ سرور خالی است")
                throw IllegalStateException("پاسخ دریافتی از سرور خالی است")
            }
            SyncLogger.log("WEBDAV_GET", "دریافت فایل با موفقیت انجام شد (حجم: ${bodyText.length} کاراکتر)")
            bodyText
        } catch (e: Exception) {
            SyncLogger.log("WEBDAV_GET", "خطای ارتباط شبکه: ${e.localizedMessage}")
            throw e
        }
    }
}
