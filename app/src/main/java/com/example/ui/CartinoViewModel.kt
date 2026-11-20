package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import com.example.data.local.CartinoDatabase
import com.example.data.model.BankCard
import com.example.data.model.DocumentGroup
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.data.model.PasswordGroup
import com.example.data.model.PasswordEntry
import com.example.data.model.GeneratedPasswordHistory
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.util.ArchiveResult
import com.example.util.BackupSyncManager
import com.example.util.ExportResult
import com.example.util.IranianBankHelper
import com.example.util.OrphanCleanupResult
import com.example.util.ParsedCardData
import com.example.util.SyncLogger
import com.example.util.TotpManager
import com.example.util.DomainUtils
import com.example.util.VaultArchiveGroupMetadata
import com.example.util.VaultArchiveManifest
import com.example.util.VaultArchiveManager
import com.example.util.VaultStorageManager
import com.example.util.VaultZipExporter
import com.example.util.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    val skippedCount: Int,
    val archivedCount: Int = 0
)

enum class VaultDuplicateAction {
    SKIP,
    KEEP
}

data class VaultDuplicateCandidate(
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val existingFile: VaultFile
)

data class VaultImportReport(
    val importedCount: Int,
    val duplicateSkippedCount: Int,
    val duplicateKeptCount: Int,
    val rejectedCount: Int,
    val totalImportedBytes: Long,
    val messages: List<String> = emptyList()
)

enum class MergeStrategy {
    ASK_USER,
    SKIP_DUPLICATES,
    REPLACE_DUPLICATES,
    RENAME_DUPLICATES
}

data class VaultArchiveMergeConflict(
    val duplicateGroupsCount: Int,
    val duplicateFilesCount: Int
)

data class VaultImportArchiveReport(
    val totalGroups: Int,
    val newGroups: Int,
    val reusedGroups: Int,
    val totalFiles: Int,
    val newFiles: Int,
    val skippedFiles: Int,
    val replacedFiles: Int,
    val renamedFiles: Int
)

class CartinoViewModel(application: Application) : AndroidViewModel(application) {

    private val db = CartinoDatabase.getDatabase(application)
    private val cardDao = db.bankCardDao()
    private val docDao = db.identityDocumentDao()
    private val passwordDao = db.passwordDao()
    private val vaultDao = db.vaultDao()

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

    val allDocumentGroups: StateFlow<List<DocumentGroup>> = docDao.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documentGroups: StateFlow<List<DocumentGroup>> = allDocumentGroups

    val allVaultGroups: StateFlow<List<VaultGroup>> = vaultDao.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allVaultFiles: StateFlow<List<VaultFile>> = vaultDao.getAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _vaultBackupTrigger = MutableStateFlow(System.currentTimeMillis())

    val isVaultUnbackedUp: StateFlow<Boolean> = combine(
        allVaultGroups,
        allVaultFiles,
        _vaultBackupTrigger
    ) { groups, files, _ ->
        if (groups.isEmpty() && files.isEmpty()) {
            false
        } else {
            val lastVaultBackup = plainPrefs.getLong("last_vault_backup_timestamp", 0L)
            if (lastVaultBackup == 0L) {
                true
            } else {
                val latestGroupChange = groups.maxOfOrNull { it.createdAt } ?: 0L
                val latestFileChange = files.maxOfOrNull { maxOf(it.createdAt, it.updatedAt) } ?: 0L
                val latestVaultChange = maxOf(latestGroupChange, latestFileChange)
                latestVaultChange > lastVaultBackup
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isLoadingPasswords = MutableStateFlow(false)
    val isLoadingPasswords: StateFlow<Boolean> = _isLoadingPasswords.asStateFlow()

    private val decryptedPasswordCache = java.util.concurrent.ConcurrentHashMap<String, PasswordEntry>()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val debouncedSearchQuery: Flow<String> = _searchQuery
        .debounce(300L)
        .distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decryptedEntriesFlow: Flow<List<PasswordEntry>> = passwordDao.getAllEntries().flatMapLatest { rawList ->
        flow {
            val currentIds = rawList.map { it.id }.toSet()
            decryptedPasswordCache.keys.retainAll(currentIds)

            val hasUncached = rawList.any { raw ->
                val cached = decryptedPasswordCache[raw.id]
                cached == null || cached.updatedAt != raw.updatedAt
            }
            if (hasUncached) {
                _isLoadingPasswords.value = true
            }

            // Immediately emit whatever entries are already cached so UI displays them with zero delay
            val initialList = rawList.mapNotNull { raw ->
                val cached = decryptedPasswordCache[raw.id]
                if (cached != null && cached.updatedAt == raw.updatedAt) cached else null
            }
            if (initialList.isNotEmpty()) {
                emit(initialList)
            } else if (!hasUncached) {
                emit(emptyList())
            }

            // Progressive chunked decryption
            val result = ArrayList<PasswordEntry>(rawList.size)
            val chunkSize = 20
            val chunks = rawList.chunked(chunkSize)
            for (chunk in chunks) {
                for (raw in chunk) {
                    val cached = decryptedPasswordCache[raw.id]
                    if (cached != null && cached.updatedAt == raw.updatedAt) {
                        result.add(cached)
                    } else {
                        val dec = runCatching { raw.decrypted() }.getOrDefault(raw)
                        decryptedPasswordCache[raw.id] = dec
                        result.add(dec)
                    }
                }
                emit(ArrayList(result))
                kotlinx.coroutines.yield()
            }
            _isLoadingPasswords.value = false
        }.flowOn(Dispatchers.Default)
    }

    private val _cachedDecryptedEntries: StateFlow<List<PasswordEntry>> = decryptedEntriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPasswordEntries: StateFlow<List<PasswordEntry>> = combine(_cachedDecryptedEntries, debouncedSearchQuery) { entries, query ->
        withContext(Dispatchers.Default) {
            if (query.isBlank()) {
                entries
            } else {
                val q = normalizeForSearch(query)
                entries.filter { entry ->
                    // Fast filter on plaintext fields first
                    normalizeForSearch(entry.title).contains(q) ||
                    normalizeForSearch(entry.website).contains(q) ||
                    normalizeForSearch(entry.username).contains(q) ||
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

    fun addOrUpdateDocumentGroup(group: DocumentGroup) {
        viewModelScope.launch {
            docDao.insertGroup(group)
        }
    }

    fun addOrUpdateGroup(group: DocumentGroup) {
        addOrUpdateDocumentGroup(group)
    }

    fun deleteDocumentGroupCascade(
        groupId: String,
        onSuccess: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allGroups = docDao.getAllGroupsList()
                fun collectDescendantIds(parentId: String): List<String> {
                    val children = allGroups.filter { it.parentGroupId == parentId }
                    val list = mutableListOf<String>()
                    for (child in children) {
                        list.addAll(collectDescendantIds(child.id))
                        list.add(child.id)
                    }
                    return list
                }

                val groupsToDelete = collectDescendantIds(groupId) + groupId
                var totalDeletedDocs = 0

                for (gId in groupsToDelete) {
                    val docsInGroup = docDao.getDocumentsListByGroupId(gId)
                    for (doc in docsInGroup) {
                        com.example.util.ExpiryReminderManager.clearItemState(getApplication(), "doc_${doc.id}")
                    }
                    totalDeletedDocs += docsInGroup.size
                    docDao.deleteDocumentsByGroupId(gId)
                    docDao.deleteGroupById(gId)
                }

                withContext(Dispatchers.Main) {
                    onSuccess(totalDeletedDocs)
                }
            } catch (e: Throwable) {
                SyncLogger.log("DOCUMENT", "خطا در حذف آبشاری گروه مدارک: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در حذف گروه مدارک: ${e.localizedMessage}")
                }
            }
        }
    }

    fun deleteDocumentGroupCascade(
        group: DocumentGroup,
        onSuccess: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        deleteDocumentGroupCascade(group.id, onSuccess, onError)
    }

    fun deleteGroupCascade(
        group: DocumentGroup,
        onSuccess: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        deleteDocumentGroupCascade(group.id, onSuccess, onError)
    }

    // ==========================================
    // Vault Operations (Groups, Files, Cascade, Orphan Cleanup)
    // ==========================================

    fun addOrUpdateVaultGroup(group: VaultGroup) {
        viewModelScope.launch(Dispatchers.IO) {
            vaultDao.insertGroup(group)
        }
    }

    fun updateVaultFileMetadata(file: VaultFile) {
        viewModelScope.launch(Dispatchers.IO) {
            vaultDao.updateFile(file.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    internal suspend fun updateVaultFileMetadataInternal(
        fileId: String,
        newDisplayName: String,
        newNotes: String,
        newGroupId: String?
    ): VaultFile? {
        val existing = vaultDao.getFileById(fileId) ?: return null
        val sanitizedName = newDisplayName.trim().ifBlank { existing.displayName }
        val updated = existing.copy(
            displayName = sanitizedName,
            notes = newNotes.trim(),
            groupId = newGroupId,
            updatedAt = System.currentTimeMillis()
        )
        vaultDao.updateFile(updated)
        SyncLogger.log("VAULT", "متادیتای فایل صندوق به‌روزرسانی شد (شناسه: ${fileId.take(8)})")
        return updated
    }

    fun updateVaultFileMetadata(
        fileId: String,
        newDisplayName: String,
        newNotes: String,
        newGroupId: String?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = updateVaultFileMetadataInternal(
                    fileId = fileId,
                    newDisplayName = newDisplayName,
                    newNotes = newNotes,
                    newGroupId = newGroupId
                )
                if (updated != null) {
                    withContext(Dispatchers.Main) { onSuccess() }
                } else {
                    withContext(Dispatchers.Main) { onError("فایل مورد نظر در صندوق یافت نشد") }
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در به‌روزرسانی متادیتای فایل صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در ذخیره تغییرات فایل: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            }
        }
    }

    internal suspend fun importVaultFilesInternal(
        uris: List<Uri>,
        currentGroupId: String?,
        onDuplicatePrompt: (suspend (VaultDuplicateCandidate) -> VaultDuplicateAction)? = null
    ): VaultImportReport {
        val appContext = getApplication<Application>().applicationContext
        val resolver = appContext.contentResolver

        var importedCount = 0
        var duplicateSkippedCount = 0
        var duplicateKeptCount = 0
        var rejectedCount = 0
        var totalImportedBytes = 0L
        val messages = mutableListOf<String>()

        for (uri in uris) {
            var displayName: String? = null
            var reportedSizeBytes = -1L

            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            displayName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            reportedSizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }
            }

            val resolvedFileName = displayName
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "فایل بی‌نام"
            val resolvedMimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"

            if (reportedSizeBytes > VaultStorageManager.DEFAULT_MAX_FILE_SIZE_BYTES) {
                rejectedCount++
                messages.add("فایل «$resolvedFileName» بیش از سقف مجاز 100 مگابایت است")
                continue
            }

            val existingTotalBytes = vaultDao.getTotalSizeBytes()
            val availableSpaceBytes = VaultStorageManager.getAvailableSpaceBytes(appContext)
            if (reportedSizeBytes > 0L && (existingTotalBytes + reportedSizeBytes) > availableSpaceBytes) {
                rejectedCount++
                messages.add("ظرفیت باقیمانده صندوق برای فایل «$resolvedFileName» کافی نیست")
                continue
            }

            val importResult = try {
                VaultStorageManager.importFromUri(
                    context = appContext,
                    uri = uri,
                    maxFileSizeBytes = VaultStorageManager.DEFAULT_MAX_FILE_SIZE_BYTES
                )
            } catch (t: Throwable) {
                rejectedCount++
                val reason = t.localizedMessage ?: "خطا در رمزنگاری و کپی فایل"
                messages.add("«$resolvedFileName»: $reason")
                SyncLogger.log("VAULT", "رد وارد کردن فایل صندوق ($resolvedFileName): $reason")
                continue
            }

            if ((existingTotalBytes + importResult.sizeBytes) > availableSpaceBytes) {
                VaultStorageManager.deleteEncryptedFile(appContext, importResult.storageName)
                rejectedCount++
                messages.add("ظرفیت باقیمانده صندوق برای فایل «$resolvedFileName» کافی نیست")
                continue
            }

            val existingDuplicate = vaultDao.findFileBySha256InGroup(importResult.sha256, currentGroupId)
            if (existingDuplicate != null) {
                val action = onDuplicatePrompt?.invoke(
                    VaultDuplicateCandidate(
                        fileName = resolvedFileName,
                        sha256 = importResult.sha256,
                        sizeBytes = importResult.sizeBytes,
                        existingFile = existingDuplicate
                    )
                ) ?: VaultDuplicateAction.SKIP

                if (action == VaultDuplicateAction.SKIP) {
                    VaultStorageManager.deleteEncryptedFile(appContext, importResult.storageName)
                    duplicateSkippedCount++
                    continue
                } else {
                    duplicateKeptCount++
                }
            }

            val now = System.currentTimeMillis()
            val newVaultFile = VaultFile(
                id = UUID.randomUUID().toString(),
                groupId = currentGroupId,
                displayName = resolvedFileName,
                originalFileName = resolvedFileName,
                mimeType = resolvedMimeType,
                sizeBytes = importResult.sizeBytes,
                sha256 = importResult.sha256,
                storageName = importResult.storageName,
                encryptionVersion = importResult.encryptionVersion,
                notes = "",
                createdAt = now,
                updatedAt = now
            )
            vaultDao.insertFile(newVaultFile)
            importedCount++
            totalImportedBytes += importResult.sizeBytes
        }

        SyncLogger.log(
            "VAULT",
            "پایان وارد کردن فایل به صندوق: $importedCount موفق، $duplicateSkippedCount تکراری ردشده، $rejectedCount ردشده"
        )

        return VaultImportReport(
            importedCount = importedCount,
            duplicateSkippedCount = duplicateSkippedCount,
            duplicateKeptCount = duplicateKeptCount,
            rejectedCount = rejectedCount,
            totalImportedBytes = totalImportedBytes,
            messages = messages
        )
    }

    fun importVaultFiles(
        uris: List<Uri>,
        currentGroupId: String?,
        onSuccess: (VaultImportReport) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        importVaultFiles(
            uris = uris,
            currentGroupId = currentGroupId,
            onDuplicatePrompt = null,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    fun importVaultFiles(
        uris: List<Uri>,
        currentGroupId: String?,
        onDuplicatePrompt: (suspend (VaultDuplicateCandidate) -> VaultDuplicateAction)?,
        onSuccess: (VaultImportReport) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = importVaultFilesInternal(
                    uris = uris,
                    currentGroupId = currentGroupId,
                    onDuplicatePrompt = onDuplicatePrompt
                )
                withContext(Dispatchers.Main) {
                    onSuccess(report)
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در فرآیند وارد کردن فایل‌های صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در وارد کردن فایل‌ها: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            }
        }
    }

    internal suspend fun exportVaultFileInternal(
        file: VaultFile,
        targetUri: Uri
    ): ExportResult {
        val appContext = getApplication<Application>().applicationContext
        val dbRecord = vaultDao.getFileById(file.id) ?: file
        val result = VaultStorageManager.exportToUri(
            context = appContext,
            storageName = dbRecord.storageName,
            targetUri = targetUri,
            expectedSha256 = dbRecord.sha256
        )
        if (result.success) {
            SyncLogger.log(
                "VAULT",
                "خروجی فایل صندوق با موفقیت و تأیید SHA-256 انجام شد (شناسه: ${dbRecord.id.take(8)})"
            )
        } else {
            SyncLogger.log(
                "VAULT",
                "خروجی فایل صندوق ناموفق بود (شناسه: ${dbRecord.id.take(8)}): ${result.errorMessage}"
            )
        }
        return result
    }

    fun exportVaultFile(
        file: VaultFile,
        targetUri: Uri,
        onSuccess: (ExportResult) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportVaultFileInternal(file = file, targetUri = targetUri)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        onSuccess(result)
                    } else {
                        onError(result.errorMessage ?: "یکپارچگی فایل تأیید نشد")
                    }
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در خروجی گرفتن فایل صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در خروجی گرفتن فایل: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            }
        }
    }

    fun exportVaultFile(
        fileId: String,
        targetUri: Uri,
        onSuccess: (ExportResult) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val record = vaultDao.getFileById(fileId)
                if (record == null) {
                    withContext(Dispatchers.Main) {
                        onError("فایل مورد نظر در صندوق یافت نشد")
                    }
                    return@launch
                }
                val result = exportVaultFileInternal(file = record, targetUri = targetUri)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        onSuccess(result)
                    } else {
                        onError(result.errorMessage ?: "یکپارچگی فایل تأیید نشد")
                    }
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در خروجی گرفتن فایل صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در خروجی گرفتن فایل: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            }
        }
    }

    internal suspend fun exportVaultGroupAsZipInternal(
        groupId: String,
        targetUri: Uri,
        zipPassword: CharArray
    ): ExportResult {
        return VaultZipExporter.exportGroupToZip(
            context = getApplication<Application>().applicationContext,
            groupId = groupId,
            targetUri = targetUri,
            zipPassword = zipPassword
        )
    }

    fun exportVaultGroupAsZip(
        groupId: String,
        targetUri: Uri,
        zipPassword: CharArray,
        onSuccess: (ExportResult) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val passwordCopy = zipPassword.copyOf()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportVaultGroupAsZipInternal(
                    groupId = groupId,
                    targetUri = targetUri,
                    zipPassword = passwordCopy
                )
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        onSuccess(result)
                    } else {
                        onError(result.errorMessage ?: "خطا در خروجی ZIP گروه")
                    }
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در خروجی ZIP گروه صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در خروجی ZIP گروه: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            } finally {
                passwordCopy.fill('\u0000')
            }
        }
    }

    internal suspend fun exportVaultArchiveInternal(
        targetUri: Uri,
        password: CharArray,
        totpCode: String? = null
    ): ArchiveResult {
        val result = VaultArchiveManager.createVaultArchive(
            context = getApplication<Application>().applicationContext,
            targetUri = targetUri,
            password = password,
            totpCode = totpCode
        )
        if (result.success) {
            plainPrefs.edit()
                .putLong("last_vault_backup_timestamp", System.currentTimeMillis())
                .commit()
            _vaultBackupTrigger.value = System.currentTimeMillis()
        }
        return result
    }

    fun exportVaultArchive(
        targetUri: Uri,
        password: CharArray,
        totpCode: String? = null,
        onSuccess: (ArchiveResult) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val passwordCopy = password.copyOf()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportVaultArchiveInternal(
                    targetUri = targetUri,
                    password = passwordCopy,
                    totpCode = totpCode
                )
                withContext(Dispatchers.Main) {
                    val appContext = getApplication<Application>().applicationContext
                    if (result.success) {
                        Toast.makeText(
                            appContext,
                            "بک‌آپ صندوق با \u2068${result.totalGroups}\u2069 گروه و \u2068${result.totalFiles}\u2069 فایل ساخته شد",
                            Toast.LENGTH_LONG
                        ).show()
                        onSuccess(result)
                    } else {
                        val errMsg = result.errorMessage ?: "خطا در ساخت آرشیو صندوق"
                        Toast.makeText(appContext, errMsg, Toast.LENGTH_LONG).show()
                        onError(errMsg)
                    }
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT_ARCHIVE", "خطا در ساخت آرشیو صندوق: ${e.message}")
                val errMsg = "خطا در ساخت آرشیو صندوق: ${e.localizedMessage ?: "خطای نامشخص"}"
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication<Application>().applicationContext,
                        errMsg,
                        Toast.LENGTH_LONG
                    ).show()
                    onError(errMsg)
                }
            } finally {
                passwordCopy.fill('\u0000')
            }
        }
    }

    internal suspend fun importVaultArchiveInternal(
        sourceUri: Uri,
        password: CharArray,
        totpCode: String? = null,
        mergeStrategy: MergeStrategy = MergeStrategy.ASK_USER,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMergeConflict: (suspend (VaultArchiveMergeConflict) -> MergeStrategy)? = null
    ): VaultImportArchiveReport {
        val appContext = getApplication<Application>().applicationContext
        val restoreResult = VaultArchiveManager.restoreVaultArchive(
            context = appContext,
            sourceUri = sourceUri,
            password = password,
            totpCode = totpCode,
            onProgress = onProgress
        )

        if (!restoreResult.success || restoreResult.manifest == null) {
            throw IllegalStateException(restoreResult.errorMessage ?: "خطا در بازیابی آرشیو صندوق")
        }

        val manifest = restoreResult.manifest
        val remainingStaged = restoreResult.restoredStorageNames.values.toMutableSet()

        fun cleanupUnusedStagedFiles() {
            for (storage in remainingStaged) {
                VaultStorageManager.deleteEncryptedFile(appContext, storage)
            }
            remainingStaged.clear()
        }

        try {
            val orderedGroups = sortVaultArchiveGroupsParentFirst(manifest.groups)

            // Detect potential duplicates before modifying DB
            val simulatedGroupMap = mutableMapOf<String, String?>()
            var detectedDuplicateGroups = 0
            for ((grp, isOrphanFallback) in orderedGroups) {
                val mappedParentId = if (isOrphanFallback) null else grp.parentGroupId?.let { simulatedGroupMap[it] }
                val existing = if (!isOrphanFallback && grp.parentGroupId != null && mappedParentId == null) {
                    null
                } else {
                    vaultDao.findGroupByNameAndParent(grp.name.trim(), mappedParentId)
                }
                if (existing != null) {
                    simulatedGroupMap[grp.id] = existing.id
                    detectedDuplicateGroups++
                } else {
                    simulatedGroupMap[grp.id] = null
                }
            }

            val cachedSimulatedFilesByGroup = mutableMapOf<String?, List<VaultFile>>()
            var detectedDuplicateFiles = 0
            for (file in manifest.files) {
                val targetGroupId: String?
                val groupExistsInDb: Boolean
                if (file.groupId == null) {
                    targetGroupId = null
                    groupExistsInDb = true
                } else {
                    targetGroupId = simulatedGroupMap[file.groupId]
                    groupExistsInDb = targetGroupId != null
                }
                if (groupExistsInDb) {
                    val existingFiles = cachedSimulatedFilesByGroup.getOrPut(targetGroupId) {
                        vaultDao.getFilesListByGroupId(targetGroupId)
                    }
                    if (existingFiles.any { it.displayName.trim().equals(file.displayName.trim(), ignoreCase = true) }) {
                        detectedDuplicateFiles++
                    }
                }
            }

            val effectiveStrategy = if ((detectedDuplicateGroups > 0 || detectedDuplicateFiles > 0) &&
                mergeStrategy == MergeStrategy.ASK_USER
            ) {
                val resolved = onMergeConflict?.invoke(
                    VaultArchiveMergeConflict(
                        duplicateGroupsCount = detectedDuplicateGroups,
                        duplicateFilesCount = detectedDuplicateFiles
                    )
                )
                if (resolved == null || resolved == MergeStrategy.ASK_USER) {
                    cleanupUnusedStagedFiles()
                    throw IllegalStateException("موارد تکراری یافت شد؛ لطفاً استراتژی ادغام را انتخاب کنید")
                }
                resolved
            } else {
                mergeStrategy
            }

            var newGroupsCount = 0
            var reusedGroupsCount = 0
            val groupIdMap = mutableMapOf<String, String>()

            var newFilesCount = 0
            var skippedFilesCount = 0
            var replacedFilesCount = 0
            var renamedFilesCount = 0

            db.withTransaction {
                // 1. Merge / Create groups in strict parent-before-child topological order
                for ((grp, isOrphanFallback) in orderedGroups) {
                    val mappedParentId = if (isOrphanFallback) {
                        null
                    } else {
                        grp.parentGroupId?.let { groupIdMap[it] }
                    }
                    val existingGroup = vaultDao.findGroupByNameAndParent(grp.name.trim(), mappedParentId)
                    if (existingGroup != null) {
                        groupIdMap[grp.id] = existingGroup.id
                        reusedGroupsCount++
                    } else {
                        val newGroup = VaultGroup(
                            id = UUID.randomUUID().toString(),
                            name = grp.name.trim(),
                            parentGroupId = mappedParentId,
                            createdAt = grp.createdAt
                        )
                        vaultDao.insertGroup(newGroup)
                        groupIdMap[grp.id] = newGroup.id
                        newGroupsCount++
                    }
                }

                // 2. Merge / Insert files according to effectiveStrategy
                val filesByGroupCache = mutableMapOf<String?, MutableList<VaultFile>>()
                for (manifestFile in manifest.files) {
                    val mappedGroupId = manifestFile.groupId?.let { groupIdMap[it] ?: it }
                    val stagedStorageName = restoreResult.restoredStorageNames[manifestFile.storageName]
                        ?: throw IllegalStateException("فایل رمزگشایی‌شده برای «${manifestFile.displayName}» یافت نشد")

                    val existingFiles = filesByGroupCache.getOrPut(mappedGroupId) {
                        vaultDao.getFilesListByGroupId(mappedGroupId).toMutableList()
                    }
                    val existingMatch = existingFiles.firstOrNull {
                        it.displayName.trim().equals(manifestFile.displayName.trim(), ignoreCase = true)
                    }

                    if (existingMatch == null) {
                        val newFile = VaultFile(
                            id = UUID.randomUUID().toString(),
                            groupId = mappedGroupId,
                            displayName = manifestFile.displayName,
                            originalFileName = manifestFile.originalFileName,
                            mimeType = manifestFile.mimeType,
                            sizeBytes = manifestFile.sizeBytes,
                            sha256 = manifestFile.sha256,
                            storageName = stagedStorageName,
                            encryptionVersion = VaultStorageManager.ENCRYPTION_VERSION,
                            notes = manifestFile.notes,
                            createdAt = manifestFile.createdAt,
                            updatedAt = manifestFile.updatedAt
                        )
                        vaultDao.insertFile(newFile)
                        existingFiles.add(newFile)
                        remainingStaged.remove(stagedStorageName)
                        newFilesCount++
                    } else {
                        when (effectiveStrategy) {
                            MergeStrategy.SKIP_DUPLICATES, MergeStrategy.ASK_USER -> {
                                VaultStorageManager.deleteEncryptedFile(appContext, stagedStorageName)
                                remainingStaged.remove(stagedStorageName)
                                skippedFilesCount++
                            }
                            MergeStrategy.REPLACE_DUPLICATES -> {
                                vaultDao.deleteFileById(existingMatch.id)
                                existingFiles.remove(existingMatch)
                                if (existingMatch.storageName != stagedStorageName) {
                                    VaultStorageManager.deleteEncryptedFile(appContext, existingMatch.storageName)
                                }
                                val replacement = VaultFile(
                                    id = UUID.randomUUID().toString(),
                                    groupId = mappedGroupId,
                                    displayName = manifestFile.displayName,
                                    originalFileName = manifestFile.originalFileName,
                                    mimeType = manifestFile.mimeType,
                                    sizeBytes = manifestFile.sizeBytes,
                                    sha256 = manifestFile.sha256,
                                    storageName = stagedStorageName,
                                    encryptionVersion = VaultStorageManager.ENCRYPTION_VERSION,
                                    notes = manifestFile.notes,
                                    createdAt = manifestFile.createdAt,
                                    updatedAt = System.currentTimeMillis()
                                )
                                vaultDao.insertFile(replacement)
                                existingFiles.add(replacement)
                                remainingStaged.remove(stagedStorageName)
                                replacedFilesCount++
                            }
                            MergeStrategy.RENAME_DUPLICATES -> {
                                val uniqueName = resolveUniqueVaultDisplayName(
                                    desiredName = manifestFile.displayName,
                                    existingFiles = existingFiles
                                )
                                val renamedFile = VaultFile(
                                    id = UUID.randomUUID().toString(),
                                    groupId = mappedGroupId,
                                    displayName = uniqueName,
                                    originalFileName = manifestFile.originalFileName,
                                    mimeType = manifestFile.mimeType,
                                    sizeBytes = manifestFile.sizeBytes,
                                    sha256 = manifestFile.sha256,
                                    storageName = stagedStorageName,
                                    encryptionVersion = VaultStorageManager.ENCRYPTION_VERSION,
                                    notes = manifestFile.notes,
                                    createdAt = manifestFile.createdAt,
                                    updatedAt = manifestFile.updatedAt
                                )
                                vaultDao.insertFile(renamedFile)
                                existingFiles.add(renamedFile)
                                remainingStaged.remove(stagedStorageName)
                                renamedFilesCount++
                            }
                        }
                    }
                }
            }

            plainPrefs.edit()
                .putLong("last_vault_backup_timestamp", System.currentTimeMillis())
                .commit()
            _vaultBackupTrigger.value = System.currentTimeMillis()

            val report = VaultImportArchiveReport(
                totalGroups = manifest.groups.size,
                newGroups = newGroupsCount,
                reusedGroups = reusedGroupsCount,
                totalFiles = manifest.files.size,
                newFiles = newFilesCount,
                skippedFiles = skippedFilesCount,
                replacedFiles = replacedFilesCount,
                renamedFiles = renamedFilesCount
            )
            SyncLogger.log(
                "VAULT_ARCHIVE",
                "بازیابی آرشیو صندوق تکمیل شد: $newFilesCount جدید، $replacedFilesCount جایگزین، $renamedFilesCount تغییرنام‌یافته، $skippedFilesCount ردشده"
            )
            return report
        } catch (t: Throwable) {
            cleanupUnusedStagedFiles()
            throw t
        }
    }

    fun importVaultArchive(
        sourceUri: Uri,
        password: CharArray,
        totpCode: String? = null,
        mergeStrategy: MergeStrategy = MergeStrategy.ASK_USER,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onSuccess: (VaultImportArchiveReport) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        importVaultArchive(
            sourceUri = sourceUri,
            password = password,
            totpCode = totpCode,
            mergeStrategy = mergeStrategy,
            onProgress = onProgress,
            onMergeConflict = null,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    fun importVaultArchive(
        sourceUri: Uri,
        password: CharArray,
        totpCode: String? = null,
        mergeStrategy: MergeStrategy = MergeStrategy.ASK_USER,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onMergeConflict: (suspend (VaultArchiveMergeConflict) -> MergeStrategy)? = null,
        onSuccess: (VaultImportArchiveReport) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val passwordCopy = password.copyOf()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = importVaultArchiveInternal(
                    sourceUri = sourceUri,
                    password = passwordCopy,
                    totpCode = totpCode,
                    mergeStrategy = mergeStrategy,
                    onProgress = { cur, total ->
                        viewModelScope.launch(Dispatchers.Main) {
                            onProgress(cur, total)
                        }
                    },
                    onMergeConflict = onMergeConflict
                )
                withContext(Dispatchers.Main) {
                    onSuccess(report)
                }
            } catch (e: Throwable) {
                val errMsg = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "خطا در بازیابی آرشیو صندوق"
                SyncLogger.log("VAULT_ARCHIVE", "خطا در importVaultArchive: $errMsg")
                withContext(Dispatchers.Main) {
                    onError(errMsg)
                }
            } finally {
                passwordCopy.fill('\u0000')
            }
        }
    }

    private fun sortVaultArchiveGroupsParentFirst(
        groups: List<VaultArchiveGroupMetadata>
    ): List<Pair<VaultArchiveGroupMetadata, Boolean>> {
        val remaining = groups.sortedWith(
            compareBy<VaultArchiveGroupMetadata> { it.createdAt }.thenBy { it.name.lowercase() }
        ).toMutableList()
        val resolvedIds = mutableSetOf<String>()
        val ordered = mutableListOf<Pair<VaultArchiveGroupMetadata, Boolean>>()

        while (remaining.isNotEmpty()) {
            val iter = remaining.iterator()
            var progressed = false
            while (iter.hasNext()) {
                val grp = iter.next()
                if (grp.parentGroupId == null || resolvedIds.contains(grp.parentGroupId)) {
                    ordered.add(grp to false)
                    resolvedIds.add(grp.id)
                    iter.remove()
                    progressed = true
                }
            }
            if (!progressed && remaining.isNotEmpty()) {
                val orphan = remaining.removeAt(0)
                ordered.add(orphan to true)
                resolvedIds.add(orphan.id)
            }
        }
        return ordered
    }

    private fun resolveUniqueVaultDisplayName(
        desiredName: String,
        existingFiles: List<VaultFile>
    ): String {
        val usedLower = existingFiles.map { it.displayName.trim().lowercase() }.toSet()
        val base = desiredName.trim().ifBlank { "فایل بی‌نام" }
        if (!usedLower.contains(base.lowercase())) return base

        val dotIndex = base.lastIndexOf('.')
        val (stem, ext) = if (dotIndex > 0) {
            base.substring(0, dotIndex) to base.substring(dotIndex)
        } else {
            base to ""
        }
        var counter = 1
        while (true) {
            val candidate = "$stem ($counter)$ext"
            if (!usedLower.contains(candidate.lowercase())) {
                return candidate
            }
            counter++
        }
    }

    suspend fun inspectVaultArchiveFromUri(
        uri: Uri,
        password: CharArray,
        totpCode: String? = null
    ): VaultArchiveManifest? = withContext(Dispatchers.IO) {
        try {
            VaultArchiveManager.inspectVaultArchive(
                context = getApplication<Application>().applicationContext,
                sourceUri = uri,
                password = password,
                totpCode = totpCode
            )
        } catch (e: Throwable) {
            SyncLogger.log("VAULT_ARCHIVE", "خطا در inspectVaultArchiveFromUri: ${e.message}")
            null
        }
    }

    suspend fun hasVaultArchiveDuplicates(manifest: VaultArchiveManifest): Boolean = withContext(Dispatchers.IO) {
        val orderedGroups = sortVaultArchiveGroupsParentFirst(manifest.groups)
        val simulatedGroupMap = mutableMapOf<String, String?>()
        var hasDuplicates = false

        for ((grp, isOrphanFallback) in orderedGroups) {
            val mappedParentId = if (isOrphanFallback) null else grp.parentGroupId?.let { simulatedGroupMap[it] }
            val existing = if (!isOrphanFallback && grp.parentGroupId != null && mappedParentId == null) {
                null
            } else {
                vaultDao.findGroupByNameAndParent(grp.name.trim(), mappedParentId)
            }
            if (existing != null) {
                simulatedGroupMap[grp.id] = existing.id
                hasDuplicates = true
            } else {
                simulatedGroupMap[grp.id] = null
            }
        }

        if (hasDuplicates) return@withContext true

        val cachedFiles = mutableMapOf<String?, List<VaultFile>>()
        for (file in manifest.files) {
            val targetGroupId: String? = if (file.groupId == null) null else simulatedGroupMap[file.groupId]
            val existingFiles = cachedFiles.getOrPut(targetGroupId) {
                vaultDao.getFilesListByGroupId(targetGroupId)
            }
            if (existingFiles.any { it.displayName.trim().equals(file.displayName.trim(), ignoreCase = true) }) {
                return@withContext true
            }
        }

        false
    }

    fun deleteVaultFile(
        file: VaultFile,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                vaultDao.deleteFileById(file.id)
                VaultStorageManager.deleteEncryptedFile(getApplication(), file.storageName)
                SyncLogger.log("VAULT", "فایل صندوق حذف شد (شناسه: ${file.id.take(8)})")
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در حذف فایل صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در حذف فایل صندوق: ${e.localizedMessage}")
                }
            }
        }
    }

    internal suspend fun deleteVaultGroupCascadeInternal(groupId: String): Pair<Int, Long> {
        val allGroups = vaultDao.getAllGroupsList()
        val visited = mutableSetOf<String>()

        fun collectDescendantIds(parentId: String): List<String> {
            if (!visited.add(parentId)) return emptyList()
            val children = allGroups.filter { it.parentGroupId == parentId }
            val result = mutableListOf<String>()
            for (child in children) {
                result.addAll(collectDescendantIds(child.id))
                result.add(child.id)
            }
            return result
        }

        val groupsToDelete = collectDescendantIds(groupId) + groupId
        var deletedFilesCount = 0
        var freedBytes = 0L

        for (gId in groupsToDelete) {
            val filesInGroup = vaultDao.getFilesListByGroupId(gId)
            for (file in filesInGroup) {
                val diskBytes = VaultStorageManager.getEncryptedFileSizeBytes(getApplication(), file.storageName)
                VaultStorageManager.deleteEncryptedFile(getApplication(), file.storageName)
                deletedFilesCount++
                freedBytes += if (diskBytes > 0L) diskBytes else file.sizeBytes
            }
            vaultDao.deleteFilesByGroupId(gId)
            vaultDao.deleteGroupById(gId)
        }

        SyncLogger.log(
            "VAULT",
            "حذف آبشاری گروه صندوق انجام شد ($deletedFilesCount فایل، $freedBytes بایت آزاد شد)"
        )
        return deletedFilesCount to freedBytes
    }

    fun deleteVaultGroupCascade(
        groupId: String,
        onSuccess: (deletedFilesCount: Int, freedBytes: Long) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (deletedFilesCount, freedBytes) = deleteVaultGroupCascadeInternal(groupId)
                withContext(Dispatchers.Main) {
                    onSuccess(deletedFilesCount, freedBytes)
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در حذف آبشاری گروه صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در حذف گروه صندوق: ${e.localizedMessage}")
                }
            }
        }
    }

    fun deleteVaultGroupCascade(
        group: VaultGroup,
        onSuccess: (deletedFilesCount: Int, freedBytes: Long) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        deleteVaultGroupCascade(group.id, onSuccess, onError)
    }

    internal suspend fun cleanupOrphanVaultFilesInternal(): OrphanCleanupResult {
        val validStorageNames = vaultDao.getAllStorageNames().toSet()
        return VaultStorageManager.cleanupOrphanFiles(getApplication(), validStorageNames)
    }

    fun cleanupOrphanVaultFiles(
        onSuccess: (deletedCount: Int, freedBytes: Long) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = cleanupOrphanVaultFilesInternal()
                withContext(Dispatchers.Main) {
                    onSuccess(result.deletedCount, result.freedBytes)
                }
            } catch (e: Throwable) {
                SyncLogger.log("VAULT", "خطا در پاکسازی فایل‌های یتیم صندوق: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("خطا در پاکسازی فایل‌های یتیم صندوق: ${e.localizedMessage}")
                }
            }
        }
    }

    fun addOrUpdateGroup(group: PasswordGroup) {
        viewModelScope.launch {
            passwordDao.insertGroup(group)
        }
    }

    fun updateGroupFavorite(groupId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                passwordDao.updateGroupFavorite(groupId, isFavorite)
            } catch (e: Throwable) {
                SyncLogger.log("PASSWORD", "خطا در تغییر وضعیت پین گروه: ${e.message}")
            }
        }
    }

    fun toggleFavoritePasswordGroup(group: PasswordGroup) {
        updateGroupFavorite(group.id, !group.isFavorite)
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
                var count = passwordDao.countEntriesByGroup(groupId)
                passwordDao.deleteEntriesByGroupId(groupId)
                val directChildren = passwordDao.getAllGroupsList().filter { it.parentGroupId == groupId }
                for (child in directChildren) {
                    count += passwordDao.countEntriesByGroup(child.id)
                    passwordDao.deleteEntriesByGroupId(child.id)
                    passwordDao.deleteGroupById(child.id)
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
        decryptedPasswordCache[entryId]?.let { return@withContext it }
        try {
            val entry = passwordDao.getEntryById(entryId) ?: return@withContext null
            val dec = entry.decrypted()
            decryptedPasswordCache[entryId] = dec
            dec
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

                val directSubgroups = passwordDao.getAllGroupsList()
                    .filter { it.parentGroupId == chromeGroup.id }
                    .toMutableList()

                // Load existing entries in chromeGroup and its subgroups
                val allGroupIds = mutableListOf(chromeGroup.id)
                allGroupIds.addAll(directSubgroups.map { it.id })

                val existingRawEntries = mutableListOf<PasswordEntry>()
                for (gid in allGroupIds) {
                    existingRawEntries.addAll(passwordDao.getEntriesListByGroupId(gid))
                }

                val existingDecrypted = existingRawEntries.mapNotNull { entry ->
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

                for (item in existingDecrypted) {
                    val gid = item.groupId ?: chromeGroup.id
                    existingHashesByGroup.getOrPut(gid) { mutableSetOf() }.add(item.contentHash)
                    val key = Triple(item.title.trim().lowercase(), item.username.trim().lowercase(), item.website.trim().lowercase())
                    existingIdentityByGroup.getOrPut(gid) { mutableMapOf() }[key] = item
                }

                var addedCount = 0
                var updatedCount = 0
                var skippedCount = parseResult.skippedCount

                val entriesToSave = mutableListOf<PasswordEntry>()

                val plainEntries = parseResult.entries.map { it.decrypted() }
                val domainCounts = plainEntries.groupingBy {
                    DomainUtils.extractDomainTitle(it.website, it.title)
                }.eachCount()

                val csvContentHashes = mutableSetOf<String>()
                val csvIdentityKeys = mutableSetOf<Triple<String, String, String>>()

                for (plain in plainEntries) {
                    val domainTitle = DomainUtils.extractDomainTitle(plain.website, plain.title)
                    val count = domainCounts[domainTitle] ?: 0
                    val targetGroupId = if (count >= 2) {
                        val domainGroup = directSubgroups.firstOrNull { it.name.trim().equals(domainTitle, ignoreCase = true) }
                            ?: run {
                                val newSub = PasswordGroup(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = domainTitle,
                                    parentGroupId = chromeGroup.id,
                                    isFavorite = false,
                                    createdAt = System.currentTimeMillis()
                                )
                                passwordDao.insertGroup(newSub)
                                directSubgroups.add(newSub)
                                newSub
                            }
                        domainGroup.id
                    } else {
                        // Less than 2 entries for this domain: keep in main Chrome Password group
                        chromeGroup.id
                    }

                    val hash = PasswordEntry.computeHash(plain)
                    val identityKey = Triple(plain.title.trim().lowercase(), plain.username.trim().lowercase(), plain.website.trim().lowercase())
                    csvContentHashes.add(hash)
                    csvIdentityKeys.add(identityKey)

                    val groupHashes = existingHashesByGroup.getOrPut(targetGroupId) { mutableSetOf() }
                    val groupIdentities = existingIdentityByGroup.getOrPut(targetGroupId) { mutableMapOf() }

                    if (groupHashes.contains(hash)) {
                        // Exact identical content already exists
                        skippedCount++
                        continue
                    }

                    val existingEntry = groupIdentities[identityKey]
                    if (existingEntry != null) {
                        // Same identity but different content/hash -> Update
                        val updated = existingEntry.copy(
                            groupId = targetGroupId,
                            password = plain.password,
                            notes = plain.notes,
                            customFieldsJson = plain.customFieldsJson,
                            contentHash = hash,
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToSave.add(updated)
                        groupHashes.add(hash)
                        groupIdentities[identityKey] = updated
                        updatedCount++
                    } else {
                        // Brand new entry
                        val newEntry = plain.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            groupId = targetGroupId,
                            contentHash = hash,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToSave.add(newEntry)
                        groupHashes.add(hash)
                        groupIdentities[identityKey] = newEntry
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

                // Archive logic: detect old Chrome entries not present in the new CSV
                var archiveGroup = directSubgroups.firstOrNull { it.name.trim().equals("Archive", ignoreCase = true) }
                val itemsToArchive = existingDecrypted.filter { old ->
                    // If already in Archive, do not re-archive
                    if (archiveGroup != null && old.groupId == archiveGroup.id) {
                        return@filter false
                    }
                    val oldIdentity = Triple(old.title.trim().lowercase(), old.username.trim().lowercase(), old.website.trim().lowercase())
                    val isPresentInCsv = csvContentHashes.contains(old.contentHash) || csvIdentityKeys.contains(oldIdentity)
                    !isPresentInCsv
                }

                var archivedCount = 0
                if (itemsToArchive.isNotEmpty()) {
                    val targetArchiveGroup = archiveGroup ?: run {
                        val newArchive = PasswordGroup(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "Archive",
                            parentGroupId = chromeGroup.id,
                            isFavorite = false,
                            createdAt = System.currentTimeMillis()
                        )
                        passwordDao.insertGroup(newArchive)
                        directSubgroups.add(newArchive)
                        archiveGroup = newArchive
                        newArchive
                    }

                    val idsToArchive = itemsToArchive.map { it.id }
                    db.withTransaction {
                        passwordDao.updateEntriesGroupId(idsToArchive, targetArchiveGroup.id, System.currentTimeMillis())
                    }
                    for (item in itemsToArchive) {
                        val cached = decryptedPasswordCache[item.id]
                        if (cached != null) {
                            decryptedPasswordCache[item.id] = cached.copy(groupId = targetArchiveGroup.id)
                        }
                    }
                    archivedCount = itemsToArchive.size
                }

                val report = PasswordImportReport(
                    addedCount = addedCount,
                    updatedCount = updatedCount,
                    skippedCount = skippedCount,
                    archivedCount = archivedCount
                )
                _lastImportReport.value = report

                val archiveNote = if (archivedCount > 0) "، $archivedCount منتقل‌شده به آرشیو" else ""
                _syncState.value = SyncUiState.Success(
                    "واردات با موفقیت انجام شد: $addedCount جدید، $updatedCount به‌روزرسانی، $skippedCount رد شده$archiveNote"
                )
                SyncLogger.log("CHROME_CSV", "گزارش واردات Chrome: جدید=$addedCount، به‌روزرسانی=$updatedCount، ردشده=$skippedCount، آرشیو=$archivedCount")
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

                val directSubgroups = passwordDao.getGroupsByParentId(chromeGroup.id).first()
                val entries = mutableListOf<PasswordEntry>()
                entries.addAll(passwordDao.getEntriesByGroup(chromeGroup.id).first())
                for (sub in directSubgroups) {
                    entries.addAll(passwordDao.getEntriesByGroup(sub.id).first())
                }
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

                val domainCounts = parseResult.items.filter { it.folderName.isNullOrBlank() }
                    .groupingBy { DomainUtils.extractDomainTitle(it.website, it.title) }
                    .eachCount()

                for (item in parseResult.items) {
                    val targetGroupId = if (item.folderName.isNullOrBlank()) {
                        val domainTitle = DomainUtils.extractDomainTitle(item.website, item.title)
                        val count = domainCounts[domainTitle] ?: 0
                        if (count >= 2) {
                            val existing = existingSubgroups.firstOrNull {
                                it.name.trim().equals(domainTitle, ignoreCase = true)
                            }
                            if (existing != null) {
                                existing.id
                            } else {
                                val newGroup = PasswordGroup(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = domainTitle,
                                    parentGroupId = bitwardenGroup.id,
                                    isFavorite = false,
                                    createdAt = System.currentTimeMillis()
                                )
                                passwordDao.insertGroup(newGroup)
                                existingSubgroups.add(newGroup)
                                newGroup.id
                            }
                        } else {
                            // Less than 2 entries for this domain: keep in main Bitwarden Password group
                            bitwardenGroup.id
                        }
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
                                isFavorite = false,
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
                plainPrefs.edit().putLong("last_enc_backup_timestamp", System.currentTimeMillis()).commit()
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
                val newDocGroupsCount = result.newDocGroupsCount
                val reusedDocGroupsCount = result.reusedDocGroupsCount
                _totpSecret.value = safeGetEncryptedString("totp_secret", "")
                _isTotpEnabled.value = (safeGetEncryptedString("totp_enabled", "false") == "true")
                _syncState.value = SyncUiState.Success("بازیابی اطلاعات با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک، $newDocGroupsCount گروه مدرک جدید، $reusedDocGroupsCount گروه مدرک تطبیق‌یافته، $passwordsCount پسورد)")
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
                plainPrefs.edit().putLong("last_enc_backup_timestamp", System.currentTimeMillis()).commit()
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
                val newDocGroupsCount = result.newDocGroupsCount
                val reusedDocGroupsCount = result.reusedDocGroupsCount
                _totpSecret.value = safeGetEncryptedString("totp_secret", "")
                _isTotpEnabled.value = (safeGetEncryptedString("totp_enabled", "false") == "true")
                _syncState.value = SyncUiState.Success("بازیابی از سرور WebDAV با موفقیت انجام شد ($cardsCount کارت، $docsCount مدرک، $newDocGroupsCount گروه مدرک جدید، $reusedDocGroupsCount گروه مدرک تطبیق‌یافته، $passwordsCount پسورد)")
            }.onFailure { err ->
                _syncState.value = SyncUiState.Error(err.localizedMessage ?: "خطا در دریافت فایل از WebDAV")
            }
        }
    }

    fun uploadVaultArchiveToWebDav(
        password: CharArray,
        totpCode: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val passwordCopy = password.copyOf()
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val config = _webDavConfig.value
            if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                val err = "آدرس سرور، نام کاربری و کلمه عبور WebDAV را وارد کنید"
                _syncState.value = SyncUiState.Error(err)
                onError(err)
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    BackupSyncManager.uploadVaultArchiveToWebDav(
                        context = getApplication(),
                        config = config,
                        password = passwordCopy,
                        totpCode = totpCode
                    )
                }
                plainPrefs.edit()
                    .putLong("last_vault_backup_timestamp", System.currentTimeMillis())
                    .commit()
                _vaultBackupTrigger.value = System.currentTimeMillis()
                _syncState.value = SyncUiState.Success("آرشیو صندوق با موفقیت در WebDAV آپلود شد")
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Throwable) {
                val errorMsg = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "خطا در ارسال فایل آرشیو صندوق به WebDAV"
                _syncState.value = SyncUiState.Error(errorMsg)
                withContext(Dispatchers.Main) {
                    onError(errorMsg)
                }
            } finally {
                passwordCopy.fill('\u0000')
            }
        }
    }

    fun downloadAndRestoreVaultArchiveFromWebDav(
        password: CharArray,
        totpCode: String? = null,
        mergeStrategy: MergeStrategy = MergeStrategy.SKIP_DUPLICATES,
        onSuccess: (VaultImportArchiveReport) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val passwordCopy = password.copyOf()
        viewModelScope.launch {
            _syncState.value = SyncUiState.Loading
            val config = _webDavConfig.value
            if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                val err = "اطلاعات سرور، نام کاربری و کلمه عبور WebDAV کامل نیست"
                _syncState.value = SyncUiState.Error(err)
                onError(err)
                return@launch
            }
            var tempFile: File? = null
            try {
                tempFile = withContext(Dispatchers.IO) {
                    BackupSyncManager.downloadVaultArchiveToTempFile(getApplication(), config)
                }
                val tempUri = Uri.fromFile(tempFile)
                val report = importVaultArchiveInternal(
                    sourceUri = tempUri,
                    password = passwordCopy,
                    totpCode = totpCode,
                    mergeStrategy = mergeStrategy
                )
                _syncState.value = SyncUiState.Success("بازیابی آرشیو صندوق از WebDAV با موفقیت انجام شد (\u2068${report.newFiles + report.replacedFiles + report.renamedFiles}\u2069 فایل اضافه یا بازیابی شد)")
                withContext(Dispatchers.Main) {
                    onSuccess(report)
                }
            } catch (e: Throwable) {
                val errorMsg = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "خطا در دریافت یا بازیابی آرشیو صندوق از WebDAV"
                _syncState.value = SyncUiState.Error(errorMsg)
                withContext(Dispatchers.Main) {
                    onError(errorMsg)
                }
            } finally {
                tempFile?.delete()
                passwordCopy.fill('\u0000')
            }
        }
    }

    fun setSyncError(message: String) {
        _syncState.value = SyncUiState.Error(message)
    }

    fun resetSyncState() {
        _syncState.value = SyncUiState.Idle
    }
}

