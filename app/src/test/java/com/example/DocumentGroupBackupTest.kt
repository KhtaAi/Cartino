package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CartinoDatabase
import com.example.data.model.DocumentGroup
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.util.BackupPayload
import com.example.util.BackupSyncManager
import com.example.util.SecurityManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentGroupBackupTest {

    private lateinit var db: CartinoDatabase
    private lateinit var context: Context

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = CartinoDatabase.getDatabase(context)
        db.identityDocumentDao().clearAll()
        db.identityDocumentDao().clearAllGroups()
        db.bankCardDao().clearAll()
        db.passwordDao().clearAllEntries()
        db.passwordDao().clearAllGroups()
    }

    @After
    fun tearDown() = runBlocking {
        db.identityDocumentDao().clearAll()
        db.identityDocumentDao().clearAllGroups()
        db.bankCardDao().clearAll()
        db.passwordDao().clearAllEntries()
        db.passwordDao().clearAllGroups()
    }

    @Test
    fun restoreBackup_resolvesDocumentGroupHierarchyAndMapsDocumentGroupIds() = runBlocking {
        val docDao = db.identityDocumentDao()

        // Pre-existing group in DB: "مدارک رسمی" (root)
        val preExistingRoot = DocumentGroup(id = "existing-root-id", name = "مدارک رسمی", parentGroupId = null)
        docDao.insertGroup(preExistingRoot)

        // Backup payload containing:
        // 1. "مدارک رسمی" (root) -> should be reused
        // 2. "گذرنامه‌ها" (child of "مدارک رسمی") -> should be created under existing-root-id
        // 3. "بیمه‌نامه‌ها" (root) -> should be created as new root
        val payloadDocGroups = listOf(
            DocumentGroup(id = "backup-root-1", name = "مدارک رسمی", parentGroupId = null),
            DocumentGroup(id = "backup-child-1", name = "گذرنامه‌ها", parentGroupId = "backup-root-1"),
            DocumentGroup(id = "backup-root-2", name = "بیمه‌نامه‌ها", parentGroupId = null)
        )

        val payloadDocs = listOf(
            IdentityDocument(id = "doc-1", groupId = "backup-child-1", title = "پاسپورت علی", docType = DocumentType.PASSPORT, nationalCode = "ENC:0012345678", customFieldsJson = "ENC:[]"),
            IdentityDocument(id = "doc-2", groupId = "backup-root-2", title = "بیمه شخص ثالث", docType = DocumentType.OTHER, nationalCode = "ENC:0087654321", customFieldsJson = "ENC:[]"),
            IdentityDocument(id = "doc-3", groupId = "missing-group-id", title = "مدرک با گروه ناموجود", docType = DocumentType.OTHER, customFieldsJson = "ENC:[]")
        )

        val payload = BackupPayload(
            version = 3,
            cards = emptyList(),
            documents = payloadDocs,
            documentGroups = payloadDocGroups,
            passwordGroups = emptyList(),
            passwordEntries = emptyList()
        )

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(BackupPayload::class.java)
        val json = adapter.toJson(payload)
        val encrypted = SecurityManager.encryptData(json, "TestPass123!".toCharArray())

        val result = BackupSyncManager.restoreFromEncryptedPayload(
            context = context,
            encryptedBase64 = encrypted,
            password = "TestPass123!"
        )

        assertEquals(3, result.docsCount)
        assertEquals(2, result.newDocGroupsCount) // "گذرنامه‌ها" + "بیمه‌نامه‌ها"
        assertEquals(1, result.reusedDocGroupsCount) // "مدارک رسمی"

        val allDbGroups = docDao.getAllGroups().first()
        assertEquals(3, allDbGroups.size)

        val dbChildGroup = docDao.findGroupByNameAndParent("گذرنامه‌ها", preExistingRoot.id)
        assertNotNull(dbChildGroup)

        val dbDoc1 = docDao.getDocumentById("doc-1")
        assertNotNull(dbDoc1)
        assertEquals(dbChildGroup?.id, dbDoc1?.groupId)

        // doc-3 had an unresolvable groupId in backup -> mapped defensively to root (null)
        val dbDoc3 = docDao.getDocumentById("doc-3")
        assertNotNull(dbDoc3)
        assertNull(dbDoc3?.groupId)
    }

    @Test
    fun restoreLegacyBackupV2_restoresDocumentsToRootWithoutErrors() = runBlocking {
        val docDao = db.identityDocumentDao()

        val legacyPayload = BackupPayload(
            version = 2,
            documents = listOf(
                IdentityDocument(id = "legacy-doc", title = "کارت ملی قدیمی", docType = DocumentType.NATIONAL_CARD, customFieldsJson = "ENC:[]")
            )
        )

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val json = moshi.adapter(BackupPayload::class.java).toJson(legacyPayload)
        val encrypted = SecurityManager.encryptData(json, "Pass123!".toCharArray())

        val result = BackupSyncManager.restoreFromEncryptedPayload(
            context = context,
            encryptedBase64 = encrypted,
            password = "Pass123!"
        )

        assertEquals(1, result.docsCount)
        assertEquals(0, result.newDocGroupsCount)
        assertEquals(0, result.reusedDocGroupsCount)

        val restoredDoc = docDao.getDocumentById("legacy-doc")
        assertNotNull(restoredDoc)
        assertEquals("کارت ملی قدیمی", restoredDoc?.title)
        assertNull(restoredDoc?.groupId)
    }
}
