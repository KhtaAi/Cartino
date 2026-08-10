package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CartinoDatabase
import com.example.data.model.BankCard
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.util.BackupSyncManager
import com.example.util.IranianBankHelper
import com.example.util.ParsedCardData
import com.example.util.SyncLogger
import com.example.util.TotpManager
import com.example.util.WebDavConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import kotlinx.coroutines.flow.first

sealed class SyncUiState {
    object Idle : SyncUiState()
    object Loading : SyncUiState()
    data class Success(val message: String) : SyncUiState()
    data class Error(val message: String) : SyncUiState()
}

class CartinoViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CartinoDatabase.getDatabase(application)
    private val cardDao = db.bankCardDao()
    private val docDao = db.identityDocumentDao()

    private val plainPrefs = application.getSharedPreferences("cartino_prefs", android.content.Context.MODE_PRIVATE)

    private val encryptedPrefs: android.content.SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(application)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                application,
                "cartino_encrypted_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            SyncLogger.log("SECURITY", "EncryptedSharedPreferences در دسترس نیست: ${e.message}")
            null
        }
    }

    private fun safeGetEncryptedString(key: String, defaultValue: String): String {
        val prefs = encryptedPrefs ?: return defaultValue
        return try {
            prefs.getString(key, defaultValue) ?: defaultValue
        } catch (t: Throwable) {
            defaultValue
        }
    }

    private fun safePutEncryptedString(key: String, value: String) {
        val prefs = encryptedPrefs ?: run {
            _syncState.value = SyncUiState.Error("سیستم رمزنگاری امن دستگاه در دسترس نیست و امکان ذخیره‌سازی اطلاعات محرمانه وجود ندارد")
            return
        }
        try {
            prefs.edit().putString(key, value).apply()
        } catch (t: Throwable) {
            _syncState.value = SyncUiState.Error("خطا در ذخیره‌سازی امن: ${t.localizedMessage}")
        }
    }

    private val _masterEncryptionPassword = MutableStateFlow(safeGetEncryptedString("master_sync_password", ""))
    val masterEncryptionPassword: StateFlow<String> = _masterEncryptionPassword.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private fun normalizeForSearch(text: String): String {
        if (text.isBlank()) return ""
        val converted = text
            .map { char ->
                when (char) {
                    '۰', '٠' -> '0'
                    '۱', '١' -> '1'
                    '۲', '٢' -> '2'
                    '۳', '٣' -> '3'
                    '۴', '٤' -> '4'
                    '۵', '٥' -> '5'
                    '۶', '٦' -> '6'
                    '۷', '٧' -> '7'
                    '۸', '٨' -> '8'
                    '۹', '٩' -> '9'
                    else -> char
                }
            }
            .joinToString("")
        return converted
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ۀ', 'ه')
            .replace('ة', 'ه')
            .replace("\u200c", "")
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "")
            .replace("-", "")
            .lowercase()
    }

    val allCards: StateFlow<List<BankCard>> = combine(cardDao.getAllCards(), _searchQuery) { cards, query ->
        val decryptedCards = cards.map { it.decrypted() }
        if (query.isBlank()) {
            decryptedCards
        } else {
            val q = normalizeForSearch(query)
            decryptedCards.filter { card ->
                normalizeForSearch(card.bankName).contains(q) ||
                normalizeForSearch(card.cardNumber).contains(q) ||
                normalizeForSearch(card.cardHolderName).contains(q) ||
                normalizeForSearch(card.iban).contains(q) ||
                normalizeForSearch(card.accountNumber).contains(q) ||
                normalizeForSearch(card.cvv2).contains(q) ||
                normalizeForSearch(card.expiryYear).contains(q) ||
                normalizeForSearch(card.expiryMonth).contains(q) ||
                normalizeForSearch(card.notes).contains(q) ||
                card.getCustomFields().any { (label, value) ->
                    normalizeForSearch(label).contains(q) || normalizeForSearch(value).contains(q)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDocuments: StateFlow<List<IdentityDocument>> = combine(docDao.getAllDocuments(), _searchQuery) { docs, query ->
        val decryptedDocs = docs.map { it.decrypted() }
        if (query.isBlank()) {
            decryptedDocs
        } else {
            val q = normalizeForSearch(query)
            decryptedDocs.filter { doc ->
                normalizeForSearch(doc.title).contains(q) ||
                normalizeForSearch(doc.docType.titleFa).contains(q) ||
                normalizeForSearch(doc.nationalCode).contains(q) ||
                normalizeForSearch(doc.documentNumber).contains(q) ||
                normalizeForSearch(doc.issueDate).contains(q) ||
                normalizeForSearch(doc.expiryDate).contains(q) ||
                normalizeForSearch(doc.notes).contains(q) ||
                doc.getCustomFields().any { custom ->
                    normalizeForSearch(custom.label).contains(q) ||
                    normalizeForSearch(custom.value).contains(q)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scannedCardData = MutableStateFlow<ParsedCardData?>(null)
    val scannedCardData: StateFlow<ParsedCardData?> = _scannedCardData.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(true)
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled.asStateFlow()

    private val _isFlagSecureEnabled = MutableStateFlow(true)
    val isFlagSecureEnabled: StateFlow<Boolean> = _isFlagSecureEnabled.asStateFlow()

    private val _isTotpEnabled = MutableStateFlow(
        safeGetEncryptedString("totp_enabled", "false") == "true"
    )
    val isTotpEnabled: StateFlow<Boolean> = _isTotpEnabled.asStateFlow()

    private val _totpSecret = MutableStateFlow(
        safeGetEncryptedString("totp_secret", "")
    )
    val totpSecret: StateFlow<String> = _totpSecret.asStateFlow()

    private val _webDavConfig = MutableStateFlow(
        WebDavConfig(
            serverUrl = safeGetEncryptedString("webdav_server_url", ""),
            username = safeGetEncryptedString("webdav_username", ""),
            password = safeGetEncryptedString("webdav_password", ""),
            remotePath = safeGetEncryptedString("webdav_remote_path", "cartino_backup.enc")
        )
    )
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    fun verifyTotpCode(code: String): Boolean {
        val secret = _totpSecret.value
        if (secret.isBlank()) return false
        return TotpManager.verifyTotp(secret, code)
    }

    fun enableTotp(secret: String) {
        safePutEncryptedString("totp_secret", secret)
        safePutEncryptedString("totp_enabled", "true")
        _totpSecret.value = secret
        _isTotpEnabled.value = true
        SyncLogger.log("TOTP", "احراز هویت دو مرحله‌ای (TOTP) فعال شد")
    }

    fun disableTotp() {
        safePutEncryptedString("totp_secret", "")
        safePutEncryptedString("totp_enabled", "false")
        _totpSecret.value = ""
        _isTotpEnabled.value = false
        SyncLogger.log("TOTP", "احراز هویت دو مرحله‌ای (TOTP) غیرفعال شد")
    }

    init {
        // Clean up legacy passkey preference keys
        safePutEncryptedString("passkey_enabled", "")
        safePutEncryptedString("passkey_secret", "")
        safePutEncryptedString("passkey_credential_id", "")
        safePutEncryptedString("passkey_public_key", "")
        safePutEncryptedString("passkey_last_challenge", "")
        safePutEncryptedString("passkey_last_signature", "")
        try {
            plainPrefs.edit()
                .remove("passkey_enabled")
                .remove("passkey_secret")
                .remove("passkey_credential_id")
                .remove("passkey_public_key")
                .remove("passkey_last_challenge")
                .remove("passkey_last_signature")
                .apply()
        } catch (e: Throwable) {}

        // Migrate legacy plain shared preferences passwords if present
        val oldMasterPass = try { plainPrefs.getString("master_sync_password", null) } catch (e: Throwable) { null }
        val oldWebdavPass = try { plainPrefs.getString("webdav_password", null) } catch (e: Throwable) { null }
        if (!oldMasterPass.isNullOrBlank()) {
            safePutEncryptedString("master_sync_password", oldMasterPass)
            try { plainPrefs.edit().remove("master_sync_password").apply() } catch (e: Throwable) {}
            _masterEncryptionPassword.value = oldMasterPass
        }
        if (!oldWebdavPass.isNullOrBlank()) {
            safePutEncryptedString("webdav_password", oldWebdavPass)
            try { plainPrefs.edit().remove("webdav_password").apply() } catch (e: Throwable) {}
            _webDavConfig.value = _webDavConfig.value.copy(password = oldWebdavPass)
        }

        performFieldEncryptionMigrationIfNeeded()
    }

    private fun performFieldEncryptionMigrationIfNeeded() {
        val isMigrated = plainPrefs.getBoolean("field_encryption_migrated", false)
        if (!isMigrated) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                SyncLogger.log("MIGRATION", "شروع مهاجرت یک‌باره رمزنگاری فیلدها...")
                try {
                    val cards = cardDao.getAllCards().first()
                    var cardsCount = 0
                    cards.forEach { card ->
                        val reEncrypted = card.encrypted()
                        cardDao.insertCard(reEncrypted)
                        cardsCount++
                    }

                    val docs = docDao.getAllDocuments().first()
                    var docsCount = 0
                    docs.forEach { doc ->
                        val reEncrypted = doc.encrypted()
                        docDao.insertDocument(reEncrypted)
                        docsCount++
                    }

                    plainPrefs.edit().putBoolean("field_encryption_migrated", true).apply()
                    SyncLogger.log("MIGRATION", "مهاجرت رمزنگاری فیلدها با موفقیت انجام شد ($cardsCount کارت و $docsCount مدرک).")
                } catch (e: Throwable) {
                    SyncLogger.log("MIGRATION", "خطا در مهاجرت رمزنگاری فیلدها: ${e.message}. در اجرای بعدی مجددا تلاش خواهد شد.")
                }
            }
        }
    }

    private val _appThemeMode = MutableStateFlow(plainPrefs.getString("app_theme_mode", "DARK") ?: "DARK")
    val appThemeMode: StateFlow<String> = _appThemeMode.asStateFlow()

    private val _appAccentPalette = MutableStateFlow(plainPrefs.getString("app_accent_palette", "GREEN") ?: "GREEN")
    val appAccentPalette: StateFlow<String> = _appAccentPalette.asStateFlow()

    private val _clipboardAutoClearEnabled = MutableStateFlow(
        plainPrefs.getBoolean("clipboard_auto_clear_enabled", true)
    )
    val clipboardAutoClearEnabled: StateFlow<Boolean> = _clipboardAutoClearEnabled.asStateFlow()

    private val _clipboardAutoClearSeconds = MutableStateFlow(
        plainPrefs.getInt("clipboard_auto_clear_seconds", 15)
    )
    val clipboardAutoClearSeconds: StateFlow<Int> = _clipboardAutoClearSeconds.asStateFlow()

    fun setAppThemeMode(mode: String) {
        _appThemeMode.value = mode
        plainPrefs.edit().putString("app_theme_mode", mode).apply()
    }

    fun setAppAccentPalette(palette: String) {
        _appAccentPalette.value = palette
        plainPrefs.edit().putString("app_accent_palette", palette).apply()
    }

    fun setClipboardAutoClearEnabled(enabled: Boolean) {
        _clipboardAutoClearEnabled.value = enabled
        plainPrefs.edit().putBoolean("clipboard_auto_clear_enabled", enabled).apply()
    }

    fun setClipboardAutoClearSeconds(seconds: Int) {
        val validSeconds = seconds.coerceIn(1, 3600)
        _clipboardAutoClearSeconds.value = validSeconds
        plainPrefs.edit().putInt("clipboard_auto_clear_seconds", validSeconds).apply()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setScannedCardData(data: ParsedCardData?) {
        _scannedCardData.value = data
    }

    fun toggleBiometricEnabled(enabled: Boolean) {
        _isBiometricEnabled.value = enabled
    }

    fun toggleFlagSecureEnabled(enabled: Boolean) {
        _isFlagSecureEnabled.value = enabled
    }

    fun updateWebDavConfig(config: WebDavConfig) {
        val rawName = config.remotePath.trim().removePrefix("/")
        val cleanName = if (rawName.isBlank()) "cartino_backup.enc" else if (rawName.endsWith(".enc", ignoreCase = true)) rawName else "$rawName.enc"
        val updated = config.copy(remotePath = cleanName)
        _webDavConfig.value = updated
        safePutEncryptedString("webdav_server_url", updated.serverUrl)
        safePutEncryptedString("webdav_username", updated.username)
        safePutEncryptedString("webdav_password", updated.password)
        safePutEncryptedString("webdav_remote_path", updated.remotePath)
        val host = runCatching {
            val clean = updated.serverUrl.trim()
            val uri = java.net.URI(if (clean.contains("://")) clean else "http://$clean")
            uri.host ?: clean
        }.getOrDefault(updated.serverUrl)
        SyncLogger.log("WEBDAV", "تنظیمات WebDAV ذخیره شد ($host)")
    }

    fun addOrUpdateCard(card: BankCard) {
        viewModelScope.launch {
            try {
                cardDao.insertCard(card.encrypted())
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در رمزنگاری و ذخیره‌سازی کارت: ${e.localizedMessage}")
            }
        }
    }

    fun deleteCard(card: BankCard) {
        viewModelScope.launch {
            cardDao.deleteCardById(card.id)
        }
    }

    fun toggleFavoriteCard(card: BankCard) {
        viewModelScope.launch {
            try {
                val newFav = !card.isFavorite
                cardDao.updateCard(card.copy(isFavorite = newFav).encrypted())
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در رمزنگاری کارت: ${e.localizedMessage}")
            }
        }
    }

    fun addOrUpdateDocument(doc: IdentityDocument) {
        viewModelScope.launch {
            try {
                docDao.insertDocument(doc.encrypted())
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در رمزنگاری و ذخیره‌سازی مدرک: ${e.localizedMessage}")
            }
        }
    }

    fun deleteDocument(doc: IdentityDocument) {
        viewModelScope.launch {
            docDao.deleteDocumentById(doc.id)
        }
    }

    fun checkBackupTotpRequirement(
        target: com.example.ui.screens.BackupTarget,
        uri: Uri?,
        password: String,
        onResult: (totpRequired: Boolean, hasTotpSecret: Boolean) -> Unit
    ) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفا کلمه عبور رمزنگاری را وارد کنید یا در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                when (target) {
                    com.example.ui.screens.BackupTarget.LOCAL_FILE -> {
                        if (uri == null) throw IllegalArgumentException("فایل انتخاب‌نشده است")
                        BackupSyncManager.inspectBackupMetadataFromUri(getApplication(), uri, effectivePassword)
                    }
                    com.example.ui.screens.BackupTarget.WEBDAV -> {
                        val config = _webDavConfig.value
                        if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                            throw IllegalArgumentException("اطلاعات سرور، نام کاربری و کلمه عبور WebDAV کامل نیست")
                        }
                        val payload = BackupSyncManager.downloadFromWebDav(config)
                        BackupSyncManager.inspectBackupMetadata(payload, effectivePassword)
                    }
                }
            }.onSuccess { (totpRequired, hasTotpSecret) ->
                _syncState.value = SyncUiState.Idle
                onResult(totpRequired, hasTotpSecret)
            }.onFailure { err ->
                val errorMsg = err.localizedMessage ?: "کلمه عبور اشتباه است یا فایل انتخاب‌شده معتبر نیست"
                _syncState.value = SyncUiState.Error(errorMsg)
            }
        }
    }

    fun updateMasterEncryptionPassword(password: String) {
        val trimmed = password.take(70)
        _masterEncryptionPassword.value = trimmed
        safePutEncryptedString("master_sync_password", trimmed)
    }

    fun createLocalBackupToUri(uri: Uri, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفا کلمه عبور رمزنگاری را وارد کنید یا در تنظیمات ثبت کنید")
                onError("کلمه عبور ثبت نشده است")
                return@launch
            }
            runCatching {
                BackupSyncManager.createEncryptedBackupToUri(getApplication(), uri, effectivePassword)
            }.onSuccess {
                _syncState.value = SyncUiState.Success("فایل پشتیبان با موفقیت در مسیر انتخاب‌شده ذخیره شد")
                onSuccess()
            }.onFailure { err ->
                val errorMsg = err.localizedMessage ?: "خطا در ساخت فایل پشتیبان"
                _syncState.value = SyncUiState.Error(errorMsg)
                onError(errorMsg)
            }
        }
    }

    fun restoreLocalBackupFromUri(uri: Uri, password: String, totpCode: String? = null) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفا کلمه عبور رمزنگاری را وارد کنید یا در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                BackupSyncManager.restoreFromEncryptedUri(
                    context = getApplication(),
                    uri = uri,
                    password = effectivePassword,
                    totpCode = totpCode
                )
            }.onSuccess { (cardsCount, docsCount) ->
                _totpSecret.value = safeGetEncryptedString("totp_secret", "")
                _isTotpEnabled.value = (safeGetEncryptedString("totp_enabled", "false") == "true")
                _syncState.value = SyncUiState.Success("بازیابی اطلاعات با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک)")
            }.onFailure { err ->
                _syncState.value = SyncUiState.Error(err.localizedMessage ?: "کلمه عبور اشتباه است یا فایل انتخاب‌شده معتبر نیست")
            }
        }
    }

    fun backupToWebDav(password: String) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val config = _webDavConfig.value
            if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                _syncState.value = SyncUiState.Error("آدرس سرور، نام کاربری و کلمه عبور WebDAV را وارد کنید")
                return@launch
            }
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفا کلمه عبور رمزنگاری را در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                val payload = BackupSyncManager.createEncryptedBackupPayload(getApplication(), effectivePassword)
                BackupSyncManager.uploadToWebDav(config, payload)
            }.onSuccess {
                _syncState.value = SyncUiState.Success("پشتیبان‌گیری روی سرور WebDAV (${config.serverUrl}) با موفقیت انجام شد")
            }.onFailure { err ->
                _syncState.value = SyncUiState.Error(err.localizedMessage ?: "خطا در ارسال فایل به سرور WebDAV")
            }
        }
    }

    fun restoreFromWebDav(password: String, totpCode: String? = null) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val config = _webDavConfig.value
            if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                _syncState.value = SyncUiState.Error("اطلاعات سرور، نام کاربری و کلمه عبور WebDAV کامل نیست")
                return@launch
            }
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفا کلمه عبور رمزنگاری را در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                val payload = BackupSyncManager.downloadFromWebDav(config)
                BackupSyncManager.restoreFromEncryptedPayload(
                    context = getApplication(),
                    encryptedBase64 = payload,
                    password = effectivePassword,
                    totpCode = totpCode
                )
            }.onSuccess { (cardsCount, docsCount) ->
                _totpSecret.value = safeGetEncryptedString("totp_secret", "")
                _isTotpEnabled.value = (safeGetEncryptedString("totp_enabled", "false") == "true")
                _syncState.value = SyncUiState.Success("بازیابی از سرور WebDAV با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک)")
            }.onFailure { err ->
                _syncState.value = SyncUiState.Error(err.localizedMessage ?: "خطا در دریافت فایل از WebDAV")
            }
        }
    }

    fun resetSyncState() {
        _syncState.value = SyncUiState.Idle
    }
}

