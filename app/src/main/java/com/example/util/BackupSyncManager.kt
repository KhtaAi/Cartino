package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.local.CartinoDatabase
import com.example.data.model.BankCard
import com.example.data.model.IdentityDocument
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

data class BackupPayload(
    val version: Int = 1,
    val totpSecret: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val totpRequired: Boolean = false,
    val cards: List<BankCard> = emptyList(),
    val documents: List<IdentityDocument> = emptyList()
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
            SyncLogger.log("RESTORE", "خطا در ذخیره‌سازی TOTP Secret: ${e.message}")
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

        val totpActive = isTotpEnabledInPrefs(context)
        val secret = if (totpActive) getTotpSecretFromPrefs(context) else ""
        if (totpActive && secret.isNotBlank()) {
            SyncLogger.log("BACKUP", "Secret TOTP با موفقیت در فایل پشتیبان ذخیره شد")
        }
        SyncLogger.log("BACKUP", "تعداد کارت‌ها: ${cards.size} | تعداد مدارک: ${docs.size} | TOTP: ${if (totpActive) "فعال" else "غیرفعال"}")

        val payload = BackupPayload(
            totpSecret = secret,
            totpRequired = totpActive,
            cards = cards,
            documents = docs
        )

        val adapter = moshi.adapter(BackupPayload::class.java)
        val jsonString = adapter.toJson(payload)

        val encrypted = SecurityManager.encryptData(jsonString, password.toCharArray())
        SyncLogger.log("BACKUP", "بسته AES-256 (Argon2id) آماده شد. حجم فایل رمز شده: ${encrypted.length} کاراکتر")
        encrypted
    }

    /**
     * Restores records into Room DB from an encrypted backup payload.
     */
    suspend fun restoreFromEncryptedPayload(
        context: Context,
        encryptedBase64: String,
        password: String,
        totpCode: String? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
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

        payload.cards.forEach { card ->
            db.bankCardDao().insertCard(card.encrypted())
            addedCards++
        }

        payload.documents.forEach { doc ->
            db.identityDocumentDao().insertDocument(doc.encrypted())
            addedDocs++
        }

        SyncLogger.log("RESTORE", "بازیابی با موفقیت انجام شد: $addedCards کارت و $addedDocs مدرک ذخیره گردید")
        Pair(addedCards, addedDocs)
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
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        SyncLogger.log("LOCAL_FILE", "خواندن فایل بک‌آپ محلی از URI: $uri")
        val payloadText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: run {
                SyncLogger.log("LOCAL_FILE", "خطا: عدم امکان خواندن فایل از URI")
                throw IllegalStateException("امکان خواندن فایل انتخاب‌شده وجود ندارد")
            }
        restoreFromEncryptedPayload(context, payloadText, password, totpCode)
    }

    /**
     * Uploads backup file to WebDAV server (e.g. Koofr, Nextcloud, personal server).
     */
    suspend fun uploadToWebDav(config: WebDavConfig, encryptedPayload: String): Boolean = withContext(Dispatchers.IO) {
        val serverUrlClean = config.serverUrl.trim()
        val usernameClean = config.username.trim()
        val passwordClean = config.password.trim()
        val remotePathClean = config.remotePath.trim().removePrefix("/")

        SyncLogger.log("WEBDAV_PUT", "شروع فرایند آپلود WebDAV...")
        SyncLogger.log("WEBDAV_PUT", "آدرس سرور: '$serverUrlClean'")
        SyncLogger.log("WEBDAV_PUT", "نام کاربری: '$usernameClean'")
        SyncLogger.log("WEBDAV_PUT", "طول کلید/رمزعبور: ${passwordClean.length} کاراکتر")

        if (serverUrlClean.isBlank() || usernameClean.isBlank() || passwordClean.isBlank()) {
            SyncLogger.log("WEBDAV_PUT", "خطا: مشخصات اتصال کامل نیست")
            throw IllegalArgumentException("آدرس سرور، نام کاربری و کلمه عبور WebDAV را وارد و تایید کنید")
        }

        val baseUrl = if (serverUrlClean.endsWith("/")) serverUrlClean else "$serverUrlClean/"
        val fileName = if (remotePathClean.isBlank()) "cartino_backup.enc" else if (remotePathClean.endsWith(".enc", ignoreCase = true)) remotePathClean else "$remotePathClean.enc"
        val targetUrl = "$baseUrl$fileName"

        SyncLogger.log("WEBDAV_PUT", "مسیر نهایی آدرس آپلود: $targetUrl")

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

        SyncLogger.log("WEBDAV_GET", "شروع فرایند دریافت از سرور WebDAV...")
        SyncLogger.log("WEBDAV_GET", "آدرس سرور: '$serverUrlClean'")
        SyncLogger.log("WEBDAV_GET", "نام کاربری: '$usernameClean'")

        if (serverUrlClean.isBlank() || usernameClean.isBlank() || passwordClean.isBlank()) {
            SyncLogger.log("WEBDAV_GET", "خطا: مشخصات اتصال کامل نیست")
            throw IllegalArgumentException("اطلاعات سرور، نام کاربری و کلمه عبور WebDAV کامل نیست")
        }

        val baseUrl = if (serverUrlClean.endsWith("/")) serverUrlClean else "$serverUrlClean/"
        val fileName = if (remotePathClean.isBlank()) "cartino_backup.enc" else if (remotePathClean.endsWith(".enc", ignoreCase = true)) remotePathClean else "$remotePathClean.enc"
        val targetUrl = "$baseUrl$fileName"

        SyncLogger.log("WEBDAV_GET", "مسیر دریافت فایل: $targetUrl")

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
                        SyncLogger.log("WEBDAV_GET", "خطای 404 Not Found: فایل '$fileName' در سرور یافت نشد.")
                        throw IllegalStateException("فایل بک‌آپ با نام $fileName در سرور WebDAV یافت نشد (کد: 404)")
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
