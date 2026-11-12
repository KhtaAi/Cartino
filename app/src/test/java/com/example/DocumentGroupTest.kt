package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CartinoDatabase
import com.example.data.model.DocumentGroup
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
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
class DocumentGroupTest {

    private lateinit var db: CartinoDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CartinoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun documentGroup_defaultsAreCorrect() {
        val group = DocumentGroup(name = "مدارک هویتی")
        assertNotNull(group.id)
        assertTrue(group.id.isNotBlank())
        assertEquals("مدارک هویتی", group.name)
        assertNull(group.parentGroupId)
        assertTrue(group.createdAt > 0)
    }

    @Test
    fun identityDocument_groupIdPreservedAcrossEncryptionAndDecryption() {
        val doc = IdentityDocument(
            groupId = "group-123",
            title = "پاسپورت",
            docType = DocumentType.PASSPORT,
            nationalCode = "ENC:sample_encrypted_code",
            notes = "ENC:sample_encrypted_notes",
            customFieldsJson = "ENC:[]"
        )

        assertEquals("group-123", doc.groupId)

        val encrypted = doc.encrypted()
        assertEquals("group-123", encrypted.groupId)
        assertEquals("ENC:sample_encrypted_code", encrypted.nationalCode)

        val decrypted = encrypted.decrypted()
        assertEquals("group-123", decrypted.groupId)
    }

    @Test
    fun dao_groupOperationsAndCascadeCleanup() = runBlocking {
        val dao = db.identityDocumentDao()

        // 1. Insert groups
        val rootGroup = DocumentGroup(id = "root-1", name = "اسناد رسمی")
        val childGroup = DocumentGroup(id = "child-1", name = "گذرنامه‌ها", parentGroupId = "root-1")
        dao.insertGroup(rootGroup)
        dao.insertGroup(childGroup)

        val allGroups = dao.getAllGroupsList()
        assertEquals(2, allGroups.size)

        // 2. Find group by name and parent
        val foundChild = dao.findGroupByNameAndParent("گذرنامه‌ها", "root-1")
        assertNotNull(foundChild)
        assertEquals("child-1", foundChild?.id)

        val notFoundChild = dao.findGroupByNameAndParent("گذرنامه‌ها", null)
        assertNull(notFoundChild)

        // 3. Insert documents in groups
        val doc1 = IdentityDocument(id = "d1", groupId = "root-1", title = "سند ملک", docType = DocumentType.OTHER)
        val doc2 = IdentityDocument(id = "d2", groupId = "child-1", title = "پاسپورت مسافرتی", docType = DocumentType.PASSPORT)
        val rootDoc = IdentityDocument(id = "d3", groupId = null, title = "کارت ملی", docType = DocumentType.NATIONAL_CARD)

        dao.insertDocument(doc1)
        dao.insertDocument(doc2)
        dao.insertDocument(rootDoc)

        // 4. Count and list by group
        assertEquals(1, dao.countDocumentsByGroup("root-1"))
        assertEquals(1, dao.countDocumentsByGroup("child-1"))
        assertEquals(0, dao.countDocumentsByGroup("non-existent"))

        val childDocs = dao.getDocumentsListByGroupId("child-1")
        assertEquals(1, childDocs.size)
        assertEquals("d2", childDocs[0].id)

        // 5. Delete documents by group
        dao.deleteDocumentsByGroupId("child-1")
        assertEquals(0, dao.countDocumentsByGroup("child-1"))
        assertNull(dao.getDocumentById("d2"))
        assertNotNull(dao.getDocumentById("d1"))
        assertNotNull(dao.getDocumentById("d3"))

        // 6. Delete group by id
        dao.deleteGroupById("child-1")
        val remainingGroups = dao.getAllGroups().first()
        assertEquals(1, remainingGroups.size)
        assertEquals("root-1", remainingGroups[0].id)
    }

    @Test
    fun migration_7_to_8_executesSuccessfully() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name("test_migration.db")
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(sqLiteDb: androidx.sqlite.db.SupportSQLiteDatabase) {
                            sqLiteDb.execSQL(
                                "CREATE TABLE IF NOT EXISTS `identity_documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `docType` TEXT NOT NULL, `nationalCode` TEXT NOT NULL, `documentNumber` TEXT NOT NULL, `issueDate` TEXT NOT NULL, `expiryDate` TEXT NOT NULL, `imagePathFront` TEXT, `imagePathBack` TEXT, `notes` TEXT NOT NULL, `customFieldsJson` TEXT NOT NULL, `fieldPlacementsJson` TEXT NOT NULL, `reminderMonthsBefore` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                            )
                            sqLiteDb.execSQL(
                                "INSERT INTO `identity_documents` (`id`, `title`, `docType`, `nationalCode`, `documentNumber`, `issueDate`, `expiryDate`, `notes`, `customFieldsJson`, `fieldPlacementsJson`, `reminderMonthsBefore`, `createdAt`) VALUES ('doc-legacy', 'کارت ملی قدیمی', 'NATIONAL_CARD', '', '', '', '', '', '[]', '{}', 2, 1000)"
                            )
                        }

                        override fun onUpgrade(
                            sqLiteDb: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) {}
                    })
                    .build()
            )

        context.deleteDatabase("test_migration.db")
        val db7 = helper.writableDatabase

        // Execute MIGRATION_7_8
        com.example.data.local.MIGRATION_7_8.migrate(db7)

        // Verify document_groups table exists
        val cursorGroups = db7.query("SELECT * FROM document_groups")
        assertNotNull(cursorGroups)
        cursorGroups.close()

        // Verify groupId column exists on identity_documents and legacy row has NULL groupId
        val cursorDocs = db7.query("SELECT id, groupId FROM identity_documents WHERE id = 'doc-legacy'")
        assertTrue(cursorDocs.moveToFirst())
        assertEquals("doc-legacy", cursorDocs.getString(0))
        assertNull(cursorDocs.getString(1))
        cursorDocs.close()

        db7.close()
        context.deleteDatabase("test_migration.db")
    }
}
