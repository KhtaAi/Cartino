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
import com.example.data.model.PasswordGroup
import com.example.data.model.PasswordEntry
import com.example.data.model.GeneratedPasswordHistory
import com.example.util.BackupSyncManager
import com.example.util.IranianBankHelper
import com.example.util.ParsedCardData
import com.example.util.SyncLogger
import com.example.util.TotpManager
import com.example.util.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import com.example.util.BitwardenFolder
import com.example.util.BitwardenFormat
import com.example.util.BitwardenIntermediateItem
import com.example.util.BitwardenPasswordParser
import com.example.util.PasswordCsvParser

sealed class SyncUiState {
    object Idle : SyncUiState()
    object Loading : SyncUiState()
    data class Progress(val current: Int, val total: Int, val message: String = "") : SyncUiState()
    data class Success(val message: String) : SyncUiState()
    data class Error(val message: String) : SyncUiState()
}

data class PasswordImportReport(
    val addedCount: Int,
    val updatedCount: Int,
    val skippedCount: Int
)

class CartinoViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CartinoDatabase.getDatabase(application)
    private val cardDao = db.bankCardDao()
    private val docDao = db.identityDocumentDao()
    private val passwordDao = db.passwordDao()

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

    val allPasswordGroups: StateFlow<List<PasswordGroup>> = passwordDao.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPasswordEntries: StateFlow<List<PasswordEntry>> = combine(passwordDao.getAllEntries(), _searchQuery) { entries, query ->
        withContext(Dispatchers.Default) {
            val decryptedEntries = entries.map { it.decrypted() }
            if (query.isBlank()) {
                decryptedEntries
            } else {
                val q = normalizeForSearch(query)
                decryptedEntries.filter { entry ->
                    normalizeForSearch(entry.title).contains(q) ||
                    normalizeForSearch(entry.username).contains(q) ||
                    normalizeForSearch(entry.website).contains(q) ||
                    normalizeForSearch(entry.notes).contains(q) ||
                    entry.getCustomFields().any { field ->
                        normalizeForSearch(field.label).contains(q) ||
                        normalizeForSearch(field.value).contains(q)
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val passwordHistory: StateFlow<List<GeneratedPasswordHistory>> = passwordDao.getAllHistory()
        .map { list -> list.map { it.decrypted() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scannedCardData = MutableStateFlow<ParsedCardData?>(null)
    val scannedCardData: StateFlow<ParsedCardData?> = _scannedCardData.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _lastImportReport = MutableStateFlow<PasswordImportReport?>(null)
    val lastImportReport: StateFlow<PasswordImportReport?> = _lastImportReport.asStateFlow()

    fun clearImportReport() {
        _lastImportReport.value = null
    }

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
        performContentHashMigrationIfNeeded()

        val defaultGroupsCreated = plainPrefs.getBoolean("default_password_groups_created", false)
        if (!defaultGroupsCreated) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    passwordDao.insertGroup(PasswordGroup(name = "Chrome Password", parentGroupId = null))
                    passwordDao.insertGroup(PasswordGroup(name = "Bitwarden Password", parentGroupId = null))
                    plainPrefs.edit().putBoolean("default_password_groups_created", true).apply()
                    SyncLogger.log("PASSWORDS", "گروه‌های پیش‌فرض Chrome Password و Bitwarden Password ساخته شدند")
                } catch (e: Throwable) {
                    SyncLogger.log("PASSWORDS", "خطا در ساخت گروه‌های پیش‌فرض پسورد: ${e.message}")
                }
            }
        }
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

    private fun performContentHashMigrationIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val emptyHashEntries = passwordDao.getEntriesWithEmptyHash()
                if (emptyHashEntries.isNotEmpty()) {
                    SyncLogger.log("MIGRATION", "شروع محاسبه contentHash برای ${emptyHashEntries.size} پسورد بدون هش...")
                    var count = 0
                    for (entry in emptyHashEntries) {
                        try {
                            val decrypted = entry.decrypted()
                            val hash = PasswordEntry.computeHash(decrypted)
                            if (hash.isNotBlank()) {
                                passwordDao.updateContentHash(entry.id, hash)
                                count++
                            }
                        } catch (e: Throwable) {
                            SyncLogger.log("MIGRATION", "خطا در محاسبه هش برای ورودی ${entry.id}: ${e.message}")
                        }
                    }
                    SyncLogger.log("MIGRATION", "محاسبه contentHash برای $count پسورد تکمیل شد.")
                }
            } catch (e: Throwable) {
                SyncLogger.log("MIGRATION", "خطا در بررسی مهاجرت contentHash: ${e.message}")
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
                val existing = cardDao.getCardById(card.id)
                if (existing != null && (existing.expiryYear != card.expiryYear || existing.expiryMonth != card.expiryMonth)) {
                    com.example.util.ExpiryReminderManager.clearItemState(getApplication(), "card_${card.id}")
                }
                cardDao.insertCard(card.encrypted())
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در رمزنگاری و ذخیره‌سازی کارت: ${e.localizedMessage}")
            }
        }
    }

    fun deleteCard(card: BankCard) {
        viewModelScope.launch {
            com.example.util.ExpiryReminderManager.clearItemState(getApplication(), "card_${card.id}")
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
                val existing = docDao.getDocumentById(doc.id)
                if (existing != null && existing.expiryDate != doc.expiryDate) {
                    com.example.util.ExpiryReminderManager.clearItemState(getApplication(), "doc_${doc.id}")
                }
                docDao.insertDocument(doc.encrypted())
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در رمزنگاری و ذخیره‌سازی مدرک: ${e.localizedMessage}")
            }
        }
    }

    fun deleteDocument(doc: IdentityDocument) {
        viewModelScope.launch {
            com.example.util.ExpiryReminderManager.clearItemState(getApplication(), "doc_${doc.id}")
            docDao.deleteDocumentById(doc.id)
        }
    }

    fun addOrUpdateGroup(group: PasswordGroup) {
        viewModelScope.launch {
            passwordDao.insertGroup(group)
        }
    }

    fun deleteGroupCascade(group: PasswordGroup) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            deleteGroupAndChildrenRecursive(group.id)
        }
    }

    private suspend fun deleteGroupAndChildrenRecursive(groupId: String) {
        val allGroups = passwordDao.getAllGroups().first()
        val children = allGroups.filter { it.parentGroupId == groupId }
        for (child in children) {
            deleteGroupAndChildrenRecursive(child.id)
        }
        passwordDao.deleteEntriesByGroupId(groupId)
        passwordDao.deleteGroupById(groupId)
    }

    fun addOrUpdatePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            try {
                val computedHash = if (entry.contentHash.isBlank()) {
                    PasswordEntry.computeHash(entry)
                } else {
                    entry.contentHash
                }
                val entryWithHash = entry.copy(
                    contentHash = computedHash,
                    updatedAt = System.currentTimeMillis()
                )
                val encrypted = entryWithHash.encrypted()
                passwordDao.insertEntry(encrypted)
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در رمزنگاری و ذخیره‌سازی پسورد: ${e.localizedMessage}")
                throw SecurityException("خطای امنیتی 15.6: شکست در رمزنگاری پسورد: ${e.message}", e)
            }
        }
    }

    fun deletePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            passwordDao.deleteEntryById(entry.id)
        }
    }

    fun deleteAllEntriesInGroup(
        groupId: String,
        onSuccess: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = passwordDao.countEntriesByGroup(groupId)
                if (count > 0) {
                    passwordDao.deleteEntriesByGroupId(groupId)
                }
                withContext(Dispatchers.Main) {
                    onSuccess(count)
                }
            } catch (e: Throwable) {
                SyncLogger.log("PASSWORD", "خطا در حذف همه پسوردهای گروه: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در حذف پسوردها: ${e.localizedMessage}")
                }
            }
        }
    }

    fun toggleFavoritePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            try {
                val newFav = !entry.isFavorite
                val updated = entry.copy(
                    isFavorite = newFav,
                    updatedAt = System.currentTimeMillis()
                ).encrypted()
                passwordDao.updateEntry(updated)
            } catch (e: Throwable) {
                _syncState.value = SyncUiState.Error("خطا در به‌روزرسانی پسورد: ${e.localizedMessage}")
                throw SecurityException("خطای امنیتی 15.6: شکست در رمزنگاری پسورد: ${e.message}", e)
            }
        }
    }

    suspend fun getDecryptedEntryById(entryId: String): PasswordEntry? = withContext(Dispatchers.IO) {
        try {
            val entry = passwordDao.getEntryById(entryId) ?: return@withContext null
            entry.decrypted()
        } catch (e: Throwable) {
            SyncLogger.log("SECURITY", "خطای امنیتی 15.6: شکست در رمزگشایی ورودی $entryId: ${e.message}")
            _syncState.value = SyncUiState.Error("خطای امنیتی در رمزگشایی پسورد: ${e.localizedMessage}")
            null
        }
    }

    suspend fun getDecryptedPasswordEntryById(id: String): PasswordEntry? {
        return getDecryptedEntryById(id)
    }

    fun addGeneratedPasswordHistory(password: String) {
        viewModelScope.launch {
            try {
                val historyItem = GeneratedPasswordHistory(password = password).encrypted()
                passwordDao.insertHistory(historyItem)
            } catch (e: Throwable) {
                SyncLogger.log("PASSWORD", "خطا در ثبت تاریخچه پسورد: ${e.message}")
            }
        }
    }

    fun clearPasswordHistory() {
        viewModelScope.launch {
            passwordDao.clearAllHistory()
        }
    }

    fun importChromePasswordsFromUri(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncState.value = SyncUiState.Loading
            try {
                val groups = passwordDao.getAllGroups().first()
                val chromeGroup = groups.firstOrNull {
                    it.name.trim().equals("Chrome Password", ignoreCase = true) && it.parentGroupId == null
                } ?: groups.firstOrNull {
                    it.name.trim().equals("Chrome Password", ignoreCase = true)
                } ?: run {
                    _syncState.value = SyncUiState.Error("گروه پیش‌فرض «Chrome Password» یافت نشد")
                    return@launch
                }

                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: run {
                        _syncState.value = SyncUiState.Error("امکان باز کردن فایل انتخاب‌شده وجود ندارد")
                        return@launch
                    }

                val parseResult = inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    PasswordCsvParser.parseChromeCsv(reader, chromeGroup.id)
                }

                if (parseResult.entries.isEmpty() && parseResult.skippedCount == 0) {
                    _syncState.value = SyncUiState.Error("فایل CSV انتخاب‌شده خالی است یا حاوی ردیف معتبری نیست")
                    return@launch
                }

                // Load existing entries in chromeGroup
                val existingRawEntries = passwordDao.getEntriesListByGroupId(chromeGroup.id)
                val existingDecrypted = existingRawEntries.mapNotNull { entry ->
                    try {
                        val dec = entry.decrypted()
                        val computedHash = if (entry.contentHash.isNotBlank()) entry.contentHash else PasswordEntry.computeHash(dec)
                        dec.copy(contentHash = computedHash)
                    } catch (e: Throwable) {
                        null
                    }
                }

                val existingHashes = existingDecrypted.map { it.contentHash }.filter { it.isNotBlank() }.toMutableSet()
                val existingByIdentity = existingDecrypted.associateBy {
                    Triple(it.title.trim().lowercase(), it.username.trim().lowercase(), it.website.trim().lowercase())
                }.toMutableMap()

                var addedCount = 0
                var updatedCount = 0
                var skippedCount = parseResult.skippedCount

                val entriesToSave = mutableListOf<PasswordEntry>()

                for (rawEntry in parseResult.entries) {
                    val plain = rawEntry.decrypted()
                    val hash = PasswordEntry.computeHash(plain)
                    val identityKey = Triple(plain.title.trim().lowercase(), plain.username.trim().lowercase(), plain.website.trim().lowercase())

                    if (existingHashes.contains(hash)) {
                        // Exact identical content already exists
                        skippedCount++
                        continue
                    }

                    val existingEntry = existingByIdentity[identityKey]
                    if (existingEntry != null) {
                        // Same identity but different content/hash -> Update
                        val updated = existingEntry.copy(
                            password = plain.password,
                            notes = plain.notes,
                            customFieldsJson = plain.customFieldsJson,
                            contentHash = hash,
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToSave.add(updated)
                        existingHashes.add(hash)
                        existingByIdentity[identityKey] = updated
                        updatedCount++
                    } else {
                        // Brand new entry
                        val newEntry = plain.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            groupId = chromeGroup.id,
                            contentHash = hash,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToSave.add(newEntry)
                        existingHashes.add(hash)
                        existingByIdentity[identityKey] = newEntry
                        addedCount++
                    }
                }

                val totalToProcess = entriesToSave.size
                var processedCount = 0
                _syncState.value = SyncUiState.Progress(0, totalToProcess.coerceAtLeast(1), "در حال ذخیره‌سازی پسوردها...")

                val chunks = entriesToSave.chunked(50)
                for (chunk in chunks) {
                    db.withTransaction {
                        for (item in chunk) {
                            val encrypted = item.encrypted()
                            passwordDao.insertEntry(encrypted)
                        }
                    }
                    processedCount += chunk.size
                    _syncState.value = SyncUiState.Progress(processedCount, totalToProcess.coerceAtLeast(1), "در حال ذخیره‌سازی پسوردها...")
                }

                val report = PasswordImportReport(
                    addedCount = addedCount,
                    updatedCount = updatedCount,
                    skippedCount = skippedCount
                )
                _lastImportReport.value = report

                _syncState.value = SyncUiState.Success(
                    "واردات با موفقیت انجام شد: $addedCount جدید، $updatedCount به‌روزرسانی، $skippedCount رد شده"
                )
                SyncLogger.log("CHROME_CSV", "گزارش واردات Chrome: جدید=$addedCount، به‌روزرسانی=$updatedCount، ردشده=$skippedCount")
            } catch (e: Throwable) {
                val msg = e.localizedMessage ?: "خطای ناشناخته در وارد کردن پسوردها"
                _syncState.value = SyncUiState.Error("خطا در وارد کردن پسوردها: $msg")
                SyncLogger.log("CHROME_CSV", "خطا در وارد کردن پسوردها: $msg")
            }
        }
    }

    fun exportChromePasswordsToUri(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncState.value = SyncUiState.Loading
            try {
                val groups = passwordDao.getAllGroups().first()
                val chromeGroup = groups.firstOrNull {
                    it.name.trim().equals("Chrome Password", ignoreCase = true) && it.parentGroupId == null
                } ?: groups.firstOrNull {
                    it.name.trim().equals("Chrome Password", ignoreCase = true)
                } ?: run {
                    _syncState.value = SyncUiState.Error("گروه پیش‌فرض «Chrome Password» یافت نشد")
                    return@launch
                }

                val entries = passwordDao.getEntriesByGroup(chromeGroup.id).first()
                if (entries.isEmpty()) {
                    _syncState.value = SyncUiState.Error("هیچ پسوردی در گروه «Chrome Password» برای خروجی وجود ندارد")
                    return@launch
                }

                val total = entries.size
                _syncState.value = SyncUiState.Progress(0, total, "در حال رمزگشایی و آماده‌سازی خروجی...")

                val decryptedEntries = ArrayList<PasswordEntry>(total)
                entries.forEachIndexed { index, entry ->
                    decryptedEntries.add(entry.decrypted())
                    if ((index + 1) % 25 == 0 || index + 1 == total) {
                        _syncState.value = SyncUiState.Progress(index + 1, total, "در حال رمزگشایی و آماده‌سازی خروجی...")
                    }
                }

                val outputStream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: run {
                        _syncState.value = SyncUiState.Error("امکان ایجاد و باز کردن فایل مقصد وجود ندارد")
                        return@launch
                    }

                outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    PasswordCsvParser.writeChromeCsv(writer, decryptedEntries)
                }

                _syncState.value = SyncUiState.Success("تعداد ${entries.size} پسورد با موفقیت در فایل CSV ذخیره شد")
                SyncLogger.log("CHROME_CSV", "تعداد ${entries.size} پسورد در فایل CSV خروجی گرفته شد")
            } catch (e: Throwable) {
                val msg = e.localizedMessage ?: "خطا در استخراج پسوردها"
                _syncState.value = SyncUiState.Error("خطا در استخراج پسوردها: $msg")
                SyncLogger.log("CHROME_CSV", "خطا در استخراج پسوردها: $msg")
            }
        }
    }

    fun importBitwardenFromUri(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncState.value = SyncUiState.Loading
            try {
                val groups = passwordDao.getAllGroups().first()
                val bitwardenGroup = groups.firstOrNull {
                    it.name.trim().equals("Bitwarden Password", ignoreCase = true) && it.parentGroupId == null
                } ?: groups.firstOrNull {
                    it.name.trim().equals("Bitwarden Password", ignoreCase = true)
                } ?: run {
                    _syncState.value = SyncUiState.Error("گروه پیش‌فرض «Bitwarden Password» یافت نشد")
                    return@launch
                }

                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: run {
                        _syncState.value = SyncUiState.Error("امکان باز کردن فایل انتخاب‌شده وجود ندارد")
                        return@launch
                    }

                val parseResult = inputStream.use { stream ->
                    BitwardenPasswordParser.parseBitwardenStream(stream)
                }

                if (parseResult.items.isEmpty() && parseResult.skippedCount == 0) {
                    _syncState.value = SyncUiState.Error("فایل انتخاب‌شده خالی است یا حاوی ردیف معتبری نیست")
                    return@launch
                }

                // Cache subgroups map in memory to avoid repeated queries
                val existingSubgroups = passwordDao.getGroupsByParentId(bitwardenGroup.id).first().toMutableList()

                val allBitwardenGroupIds = listOf(bitwardenGroup.id) + existingSubgroups.map { it.id }
                val allExistingRaw = mutableListOf<PasswordEntry>()
                for (gId in allBitwardenGroupIds) {
                    allExistingRaw.addAll(passwordDao.getEntriesListByGroupId(gId))
                }

                val existingDecrypted = allExistingRaw.mapNotNull { entry ->
                    try {
                        val dec = entry.decrypted()
                        val computedHash = if (entry.contentHash.isNotBlank()) entry.contentHash else PasswordEntry.computeHash(dec)
                        dec.copy(contentHash = computedHash)
                    } catch (e: Throwable) {
                        null
                    }
                }

                val existingHashesByGroup = mutableMapOf<String, MutableSet<String>>()
                val existingIdentityByGroup = mutableMapOf<String, MutableMap<Triple<String, String, String>, PasswordEntry>>()

                for (dec in existingDecrypted) {
                    val gId = dec.groupId ?: bitwardenGroup.id
                    val hashSet = existingHashesByGroup.getOrPut(gId) { mutableSetOf() }
                    if (dec.contentHash.isNotBlank()) hashSet.add(dec.contentHash)
                    val identityMap = existingIdentityByGroup.getOrPut(gId) { mutableMapOf() }
                    val identityKey = Triple(dec.title.trim().lowercase(), dec.username.trim().lowercase(), dec.website.trim().lowercase())
                    identityMap[identityKey] = dec
                }

                var addedCount = 0
                var updatedCount = 0
                var skippedCount = parseResult.skippedCount

                val entriesToSave = mutableListOf<PasswordEntry>()

                for (item in parseResult.items) {
                    val targetGroupId = if (item.folderName.isNullOrBlank()) {
                        bitwardenGroup.id
                    } else {
                        val folderClean = item.folderName.trim()
                        val existing = existingSubgroups.firstOrNull {
                            it.name.trim().equals(folderClean, ignoreCase = true)
                        }
                        if (existing != null) {
                            existing.id
                        } else {
                            val newGroup = PasswordGroup(
                                id = java.util.UUID.randomUUID().toString(),
                                name = folderClean,
                                parentGroupId = bitwardenGroup.id,
                                createdAt = System.currentTimeMillis()
                            )
                            passwordDao.insertGroup(newGroup)
                            existingSubgroups.add(newGroup)
                            newGroup.id
                        }
                    }

                    val hash = PasswordEntry.computeHash(
                        title = item.title,
                        username = item.username,
                        password = item.password,
                        website = item.website,
                        notes = item.notes,
                        customFields = item.customFields
                    )
                    val identityKey = Triple(item.title.trim().lowercase(), item.username.trim().lowercase(), item.website.trim().lowercase())

                    val hashSet = existingHashesByGroup.getOrPut(targetGroupId) { mutableSetOf() }
                    val identityMap = existingIdentityByGroup.getOrPut(targetGroupId) { mutableMapOf() }

                    if (hashSet.contains(hash)) {
                        skippedCount++
                        continue
                    }

                    val customFieldsJson = PasswordEntry.encodeCustomFields(item.customFields)
                    val existingEntry = identityMap[identityKey]
                    if (existingEntry != null) {
                        val updated = existingEntry.copy(
                            password = item.password,
                            notes = item.notes,
                            customFieldsJson = customFieldsJson,
                            isFavorite = item.isFavorite,
                            contentHash = hash,
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToSave.add(updated)
                        hashSet.add(hash)
                        identityMap[identityKey] = updated
                        updatedCount++
                    } else {
                        val newEntry = PasswordEntry(
                            id = java.util.UUID.randomUUID().toString(),
                            groupId = targetGroupId,
                            title = item.title,
                            username = item.username,
                            password = item.password,
                            website = item.website,
                            notes = item.notes,
                            customFieldsJson = customFieldsJson,
                            isFavorite = item.isFavorite,
                            contentHash = hash,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToSave.add(newEntry)
                        hashSet.add(hash)
                        identityMap[identityKey] = newEntry
                        addedCount++
                    }
                }

                val totalToProcess = entriesToSave.size
                var processedCount = 0
                _syncState.value = SyncUiState.Progress(0, totalToProcess.coerceAtLeast(1), "در حال ذخیره‌سازی پسوردهای بیتواردن...")

                val chunks = entriesToSave.chunked(50)
                for (chunk in chunks) {
                    db.withTransaction {
                        for (entry in chunk) {
                            val encrypted = entry.encrypted()
                            passwordDao.insertEntry(encrypted)
                        }
                    }
                    processedCount += chunk.size
                    _syncState.value = SyncUiState.Progress(processedCount, totalToProcess.coerceAtLeast(1), "در حال ذخیره‌سازی پسوردهای بیتواردن...")
                }

                val report = PasswordImportReport(
                    addedCount = addedCount,
                    updatedCount = updatedCount,
                    skippedCount = skippedCount
                )
                _lastImportReport.value = report

                _syncState.value = SyncUiState.Success(
                    "واردات با موفقیت انجام شد: $addedCount جدید، $updatedCount به‌روزرسانی، $skippedCount رد شده"
                )
                SyncLogger.log("BITWARDEN", "گزارش واردات Bitwarden: جدید=$addedCount، به‌روزرسانی=$updatedCount، ردشده=$skippedCount")
            } catch (e: Throwable) {
                val msg = e.localizedMessage ?: "خطای ناشناخته در وارد کردن پسوردهای بیتواردن"
                _syncState.value = SyncUiState.Error("خطا در وارد کردن پسوردهای بیتواردن: $msg")
                SyncLogger.log("BITWARDEN", "خطا در وارد کردن پسوردهای بیتواردن: $msg")
            }
        }
    }

    fun exportBitwardenToUri(uri: Uri, format: BitwardenFormat) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncState.value = SyncUiState.Loading
            try {
                val groups = passwordDao.getAllGroups().first()
                val bitwardenGroup = groups.firstOrNull {
                    it.name.trim().equals("Bitwarden Password", ignoreCase = true) && it.parentGroupId == null
                } ?: groups.firstOrNull {
                    it.name.trim().equals("Bitwarden Password", ignoreCase = true)
                } ?: run {
                    _syncState.value = SyncUiState.Error("گروه پیش‌فرض «Bitwarden Password» یافت نشد")
                    return@launch
                }

                val directSubgroups = passwordDao.getGroupsByParentId(bitwardenGroup.id).first()
                val rootEntries = passwordDao.getEntriesByGroup(bitwardenGroup.id).first()

                val allEntriesToExport = mutableListOf<Pair<PasswordGroup?, PasswordEntry>>()
                for (entry in rootEntries) {
                    allEntriesToExport.add(Pair(null, entry))
                }
                for (subgroup in directSubgroups) {
                    val subEntries = passwordDao.getEntriesByGroup(subgroup.id).first()
                    for (entry in subEntries) {
                        allEntriesToExport.add(Pair(subgroup, entry))
                    }
                }

                if (allEntriesToExport.isEmpty()) {
                    _syncState.value = SyncUiState.Error("هیچ پسوردی در گروه «Bitwarden Password» برای خروجی وجود ندارد")
                    return@launch
                }

                val total = allEntriesToExport.size
                _syncState.value = SyncUiState.Progress(0, total, "در حال رمزگشایی و آماده‌سازی خروجی...")

                val exportItems = mutableListOf<BitwardenIntermediateItem>()
                allEntriesToExport.forEachIndexed { index, pair ->
                    val subgroup = pair.first
                    val entry = pair.second
                    val dec = entry.decrypted()
                    exportItems.add(
                        BitwardenIntermediateItem(
                            folderId = subgroup?.id,
                            folderName = subgroup?.name,
                            title = dec.title,
                            username = dec.username,
                            password = dec.password,
                            website = dec.website,
                            notes = dec.notes,
                            isFavorite = dec.isFavorite,
                            customFields = dec.getCustomFields()
                        )
                    )
                    if ((index + 1) % 25 == 0 || index + 1 == total) {
                        _syncState.value = SyncUiState.Progress(index + 1, total, "در حال رمزگشایی و آماده‌سازی خروجی...")
                    }
                }

                val outputStream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: run {
                        _syncState.value = SyncUiState.Error("امکان ایجاد و باز کردن فایل مقصد وجود ندارد")
                        return@launch
                    }

                outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    when (format) {
                        BitwardenFormat.JSON -> {
                            val folders = directSubgroups.map { BitwardenFolder(id = it.id, name = it.name) }
                            BitwardenPasswordParser.writeBitwardenJson(writer, folders, exportItems)
                        }
                        BitwardenFormat.CSV -> {
                            BitwardenPasswordParser.writeBitwardenCsv(writer, exportItems)
                        }
                    }
                }

                val formatLabel = if (format == BitwardenFormat.JSON) "JSON" else "CSV"
                _syncState.value = SyncUiState.Success("تعداد ${exportItems.size} پسورد برای بیتواردن با موفقیت ذخیره شد")
                SyncLogger.log("BITWARDEN", "تعداد ${exportItems.size} پسورد در قالب $formatLabel برای بیتواردن خروجی گرفته شد")
            } catch (e: Throwable) {
                val msg = e.localizedMessage ?: "خطا در استخراج پسوردهای بیتواردن"
                _syncState.value = SyncUiState.Error("خطا در استخراج پسوردهای بیتواردن: $msg")
                SyncLogger.log("BITWARDEN", "خطا در استخراج پسوردهای بیتواردن: $msg")
            }
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
            }.onSuccess { result ->
                val cardsCount = result.cardsCount
                val docsCount = result.docsCount
                val passwordsCount = result.passwordsCount
                _totpSecret.value = safeGetEncryptedString("totp_secret", "")
                _isTotpEnabled.value = (safeGetEncryptedString("totp_enabled", "false") == "true")
                _syncState.value = SyncUiState.Success("بازیابی اطلاعات با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک، $passwordsCount پسورد)")
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
            }.onSuccess { result ->
                val cardsCount = result.cardsCount
                val docsCount = result.docsCount
                val passwordsCount = result.passwordsCount
                _totpSecret.value = safeGetEncryptedString("totp_secret", "")
                _isTotpEnabled.value = (safeGetEncryptedString("totp_enabled", "false") == "true")
                _syncState.value = SyncUiState.Success("بازیابی از سرور WebDAV با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک، $passwordsCount پسورد)")
            }.onFailure { err ->
                _syncState.value = SyncUiState.Error(err.localizedMessage ?: "خطا در دریافت فایل از WebDAV")
            }
        }
    }

    fun resetSyncState() {
        _syncState.value = SyncUiState.Idle
    }
}

