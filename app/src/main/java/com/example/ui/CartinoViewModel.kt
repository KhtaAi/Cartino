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

data class CloudDriveConfig(
    val folderUri: String = "",
    val folderName: String = "",
    val lastBackupTime: String = ""
)

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

    private val encryptedPrefs: android.content.SharedPreferences by lazy {
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
            try {
                application.deleteSharedPreferences("cartino_encrypted_prefs")
                plainPrefs
            } catch (t: Throwable) {
                plainPrefs
            }
        }
    }

    private fun safeGetEncryptedString(key: String, defaultValue: String): String {
        return try {
            encryptedPrefs.getString(key, defaultValue) ?: defaultValue
        } catch (t: Throwable) {
            try {
                plainPrefs.getString(key, defaultValue) ?: defaultValue
            } catch (e: Throwable) {
                defaultValue
            }
        }
    }

    private fun safePutEncryptedString(key: String, value: String) {
        try {
            encryptedPrefs.edit().putString(key, value).apply()
        } catch (t: Throwable) {
            try {
                plainPrefs.edit().putString(key, value).apply()
            } catch (e: Throwable) {}
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

    private val _webDavConfig = MutableStateFlow(
        WebDavConfig(
            serverUrl = safeGetEncryptedString("webdav_server_url", ""),
            username = safeGetEncryptedString("webdav_username", ""),
            password = safeGetEncryptedString("webdav_password", ""),
            remotePath = safeGetEncryptedString("webdav_remote_path", "cartino_backup.enc")
        )
    )
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    private val _cloudDriveConfig = MutableStateFlow(
        CloudDriveConfig(
            folderUri = safeGetEncryptedString("cloud_drive_folder_uri", ""),
            folderName = safeGetEncryptedString("cloud_drive_folder_name", ""),
            lastBackupTime = safeGetEncryptedString("cloud_drive_last_backup", "")
        )
    )
    val cloudDriveConfig: StateFlow<CloudDriveConfig> = _cloudDriveConfig.asStateFlow()

    init {
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
    }

    private val _appThemeMode = MutableStateFlow(plainPrefs.getString("app_theme_mode", "DARK") ?: "DARK")
    val appThemeMode: StateFlow<String> = _appThemeMode.asStateFlow()

    private val _appAccentPalette = MutableStateFlow(plainPrefs.getString("app_accent_palette", "GREEN") ?: "GREEN")
    val appAccentPalette: StateFlow<String> = _appAccentPalette.asStateFlow()

    fun setAppThemeMode(mode: String) {
        _appThemeMode.value = mode
        plainPrefs.edit().putString("app_theme_mode", mode).apply()
    }

    fun setAppAccentPalette(palette: String) {
        _appAccentPalette.value = palette
        plainPrefs.edit().putString("app_accent_palette", palette).apply()
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
        SyncLogger.log("WEBDAV", "تنظیمات WebDAV ذخیره شد (${updated.serverUrl})")
    }

    fun addOrUpdateCard(card: BankCard) {
        viewModelScope.launch {
            cardDao.insertCard(card.encrypted())
        }
    }

    fun deleteCard(card: BankCard) {
        viewModelScope.launch {
            cardDao.deleteCardById(card.id)
        }
    }

    fun toggleFavoriteCard(card: BankCard) {
        viewModelScope.launch {
            val newFav = !card.isFavorite
            cardDao.updateCard(card.copy(isFavorite = newFav).encrypted())
        }
    }

    fun addOrUpdateDocument(doc: IdentityDocument) {
        viewModelScope.launch {
            docDao.insertDocument(doc.encrypted())
        }
    }

    fun deleteDocument(doc: IdentityDocument) {
        viewModelScope.launch {
            docDao.deleteDocumentById(doc.id)
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

    fun restoreLocalBackupFromUri(uri: Uri, password: String) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفا کلمه عبور رمزنگاری را وارد کنید یا در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                BackupSyncManager.restoreFromEncryptedUri(getApplication(), uri, effectivePassword)
            }.onSuccess { (cardsCount, docsCount) ->
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

    fun restoreFromWebDav(password: String) {
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
                BackupSyncManager.restoreFromEncryptedPayload(getApplication(), payload, effectivePassword)
            }.onSuccess { (cardsCount, docsCount) ->
                _syncState.value = SyncUiState.Success("بازیابی از سرور WebDAV با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک)")
            }.onFailure { err ->
                _syncState.value = SyncUiState.Error(err.localizedMessage ?: "خطا در دریافت فایل از WebDAV")
            }
        }
    }

    fun setCloudDriveFolder(uri: Uri, folderName: String) {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            SyncLogger.log("CLOUD_DRIVE", "هشدار در دریافت مجوز پایدار: ${e.message}")
        } catch (e: Exception) {
            SyncLogger.log("CLOUD_DRIVE", "خطا در ثبت مجوز: ${e.message}")
        }
        val nameToUse = folderName.ifBlank { uri.lastPathSegment ?: "پوشه ابری" }
        val updated = _cloudDriveConfig.value.copy(
            folderUri = uri.toString(),
            folderName = nameToUse
        )
        _cloudDriveConfig.value = updated
        safePutEncryptedString("cloud_drive_folder_uri", updated.folderUri)
        safePutEncryptedString("cloud_drive_folder_name", updated.folderName)
        SyncLogger.log("CLOUD_DRIVE", "پوشه ابری تنظیم شد: ${updated.folderName}")
    }

    fun backupToCloudDrive(password: String) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val config = _cloudDriveConfig.value
            if (config.folderUri.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفاً ابتدا پوشه مورد نظر را در گوگل‌درایو / فضای ابری انتخاب کنید")
                return@launch
            }
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفاً کلمه عبور رمزنگاری را در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                val uri = Uri.parse(config.folderUri)
                BackupSyncManager.createEncryptedBackupToCloudTreeUri(getApplication(), uri, effectivePassword)
            }.onSuccess { timeStr ->
                val updatedConfig = _cloudDriveConfig.value.copy(lastBackupTime = timeStr)
                _cloudDriveConfig.value = updatedConfig
                safePutEncryptedString("cloud_drive_last_backup", timeStr)
                _syncState.value = SyncUiState.Success("پشتیبان‌گیری ابری با موفقیت در پوشه ${config.folderName} ذخیره شد")
            }.onFailure { err ->
                val msg = if (err is SecurityException) {
                    "دسترسی به پوشه ابری لغو شده یا نامعتبر است. لطفاً دوباره پوشه را انتخاب کنید."
                } else {
                    err.localizedMessage ?: "خطا در پشتیبان‌گیری ابری"
                }
                _syncState.value = SyncUiState.Error(msg)
            }
        }
    }

    fun restoreFromCloudDrive(password: String) {
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val config = _cloudDriveConfig.value
            if (config.folderUri.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفاً ابتدا پوشه مورد نظر را در گوگل‌درایو / فضای ابری انتخاب کنید")
                return@launch
            }
            val effectivePassword = password.ifBlank { _masterEncryptionPassword.value }
            if (effectivePassword.isBlank()) {
                _syncState.value = SyncUiState.Error("لطفاً کلمه عبور رمزنگاری را در تنظیمات ثبت کنید")
                return@launch
            }
            runCatching {
                val uri = Uri.parse(config.folderUri)
                BackupSyncManager.restoreFromCloudTreeUri(getApplication(), uri, effectivePassword)
            }.onSuccess { (cardsCount, docsCount) ->
                _syncState.value = SyncUiState.Success("بازیابی از گوگل‌درایو / فضای ابری با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک)")
            }.onFailure { err ->
                val msg = if (err is SecurityException) {
                    "دسترسی به پوشه ابری لغو شده یا نامعتبر است. لطفاً دوباره پوشه را انتخاب کنید."
                } else {
                    err.localizedMessage ?: "خطا در بازیابی از پوشه ابری"
                }
                _syncState.value = SyncUiState.Error(msg)
            }
        }
    }

    fun resetSyncState() {
        _syncState.value = SyncUiState.Idle
    }
}

