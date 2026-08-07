package com.example.ui

import android.app.Application
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
        } catch (e: Exception) {
            plainPrefs
        }
    }

    private val _masterEncryptionPassword = MutableStateFlow(encryptedPrefs.getString("master_sync_password", "") ?: "")
    val masterEncryptionPassword: StateFlow<String> = _masterEncryptionPassword.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val allCards: StateFlow<List<BankCard>> = combine(cardDao.getAllCards(), _searchQuery) { cards, query ->
        val decryptedCards = cards.map { it.decrypted() }
        if (query.isBlank()) {
            decryptedCards
        } else {
            decryptedCards.filter {
                it.bankName.contains(query, ignoreCase = true) ||
                it.cardNumber.contains(query) ||
                it.cardHolderName.contains(query, ignoreCase = true) ||
                it.iban.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDocuments: StateFlow<List<IdentityDocument>> = combine(docDao.getAllDocuments(), _searchQuery) { docs, query ->
        val decryptedDocs = docs.map { it.decrypted() }
        if (query.isBlank()) {
            decryptedDocs
        } else {
            val q = query.trim()
            decryptedDocs.filter { doc ->
                doc.title.contains(q, ignoreCase = true) ||
                doc.docType.titleFa.contains(q, ignoreCase = true) ||
                doc.nationalCode.contains(q, ignoreCase = true) ||
                doc.documentNumber.contains(q, ignoreCase = true) ||
                doc.issueDate.contains(q, ignoreCase = true) ||
                doc.expiryDate.contains(q, ignoreCase = true) ||
                doc.notes.contains(q, ignoreCase = true) ||
                doc.getCustomFields().any { custom ->
                    custom.label.contains(q, ignoreCase = true) ||
                    custom.value.contains(q, ignoreCase = true)
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
            serverUrl = encryptedPrefs.getString("webdav_server_url", "") ?: "",
            username = encryptedPrefs.getString("webdav_username", "") ?: "",
            password = encryptedPrefs.getString("webdav_password", "") ?: "",
            remotePath = encryptedPrefs.getString("webdav_remote_path", "cartino_backup.enc") ?: "cartino_backup.enc"
        )
    )
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    init {
        // Migrate legacy plain shared preferences passwords if present
        val oldMasterPass = plainPrefs.getString("master_sync_password", null)
        val oldWebdavPass = plainPrefs.getString("webdav_password", null)
        if (!oldMasterPass.isNullOrBlank()) {
            encryptedPrefs.edit().putString("master_sync_password", oldMasterPass).apply()
            plainPrefs.edit().remove("master_sync_password").apply()
            _masterEncryptionPassword.value = oldMasterPass
        }
        if (!oldWebdavPass.isNullOrBlank()) {
            encryptedPrefs.edit().putString("webdav_password", oldWebdavPass).apply()
            plainPrefs.edit().remove("webdav_password").apply()
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
        encryptedPrefs.edit()
            .putString("webdav_server_url", updated.serverUrl)
            .putString("webdav_username", updated.username)
            .putString("webdav_password", updated.password)
            .putString("webdav_remote_path", updated.remotePath)
            .apply()
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
        encryptedPrefs.edit().putString("master_sync_password", trimmed).apply()
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

    fun resetSyncState() {
        _syncState.value = SyncUiState.Idle
    }
}

