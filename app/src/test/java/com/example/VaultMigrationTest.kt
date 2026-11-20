package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CartinoDatabase
import com.example.data.local.MIGRATION_3_4
import com.example.data.local.MIGRATION_4_5
import com.example.data.local.MIGRATION_5_6
import com.example.data.local.MIGRATION_6_7
import com.example.data.local.MIGRATION_7_8
import com.example.data.local.MIGRATION_8_9
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultMigrationTest {

    private lateinit var context: Context
    private val testDbName = "test_migration_8_9.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    @Test
    fun migration_8_to_9_createsVaultTables_andPreservesAllExistingData() = runBlocking {
        // 1. Create a genuine version-8 SQLite database with all v8 tables and pre-existing user rows
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(testDbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `bank_cards` (`id` TEXT NOT NULL, `cardNumber` TEXT NOT NULL, `bankName` TEXT NOT NULL, `bankCode` TEXT NOT NULL, `cardHolderName` TEXT NOT NULL, `iban` TEXT NOT NULL, `expiryYear` TEXT NOT NULL, `expiryMonth` TEXT NOT NULL, `cvv2` TEXT NOT NULL, `colorStartHex` TEXT NOT NULL, `colorEndHex` TEXT NOT NULL, `accountNumber` TEXT NOT NULL, `notes` TEXT NOT NULL, `customFieldsJson` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `reminderMonthsBefore` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `identity_documents` (`id` TEXT NOT NULL, `groupId` TEXT, `title` TEXT NOT NULL, `docType` TEXT NOT NULL, `nationalCode` TEXT NOT NULL, `documentNumber` TEXT NOT NULL, `issueDate` TEXT NOT NULL, `expiryDate` TEXT NOT NULL, `imagePathFront` TEXT, `imagePathBack` TEXT, `notes` TEXT NOT NULL, `customFieldsJson` TEXT NOT NULL, `fieldPlacementsJson` TEXT NOT NULL, `reminderMonthsBefore` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `document_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentGroupId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `password_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentGroupId` TEXT, `isFavorite` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `password_entries` (`id` TEXT NOT NULL, `groupId` TEXT, `title` TEXT NOT NULL, `username` TEXT NOT NULL, `password` TEXT NOT NULL, `website` TEXT NOT NULL, `notes` TEXT NOT NULL, `customFieldsJson` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `generated_password_history` (`id` TEXT NOT NULL, `password` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )

                        // Seed sample v8 records
                        db.execSQL(
                            "INSERT INTO `bank_cards` VALUES ('card-v8', '6037991122334455', 'ملی', '017', 'علی رضایی', 'IR1234', '1408', '05', '123', '#000000', '#111111', '9988', '', '', 1, 2, 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO `document_groups` VALUES ('doc-grp-v8', 'شخصی', NULL, 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO `identity_documents` VALUES ('doc-v8', 'doc-grp-v8', 'کارت ملی', 'NATIONAL_CARD', '0011223344', 'A123', '1400/01/01', '1407/01/01', NULL, NULL, '', '[]', '{}', 2, 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO `password_groups` VALUES ('pwd-grp-v8', 'ایمیل', NULL, 0, 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO `password_entries` VALUES ('pwd-v8', 'pwd-grp-v8', 'Gmail', 'user', 'secret', 'gmail.com', '', '[]', 0, 'hash1', 1000, 1000)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        // 2. Open with Room at version 9 using MIGRATION_8_9 (triggers strict Room schema validation)
        val db = Room.databaseBuilder(context, CartinoDatabase::class.java, testDbName)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        try {
            // 3. Assert all v8 data survived untouched
            val card = db.bankCardDao().getCardById("card-v8")
            assertNotNull(card)
            assertEquals("ملی", card?.bankName)

            val docGroup = db.identityDocumentDao().getGroupById("doc-grp-v8")
            assertNotNull(docGroup)
            assertEquals("شخصی", docGroup?.name)

            val doc = db.identityDocumentDao().getDocumentById("doc-v8")
            assertNotNull(doc)
            assertEquals("کارت ملی", doc?.title)

            val pwdGroup = db.passwordDao().getGroupById("pwd-grp-v8")
            assertNotNull(pwdGroup)
            assertEquals("ایمیل", pwdGroup?.name)

            val pwdEntry = db.passwordDao().getEntryById("pwd-v8")
            assertNotNull(pwdEntry)
            assertEquals("Gmail", pwdEntry?.title)

            // 4. Verify new vault_groups and vault_files tables work properly
            val vaultDao = db.vaultDao()
            assertEquals(0, vaultDao.getTotalFileCount())
            assertEquals(0L, vaultDao.getTotalSizeBytes())

            val group = VaultGroup(id = "vg-1", name = "اسناد مالی")
            vaultDao.insertGroup(group)
            assertNotNull(vaultDao.findGroupByNameAndParent("اسناد مالی", null))

            val file = VaultFile(
                id = "vf-1",
                groupId = "vg-1",
                displayName = "فیش حقوقی",
                originalFileName = "payslip.pdf",
                mimeType = "application/pdf",
                sizeBytes = 2048L,
                sha256 = "abcdef0123456789",
                storageName = "vf-1.vault"
            )
            vaultDao.insertFile(file)

            assertEquals(1, vaultDao.getTotalFileCount())
            assertEquals(2048L, vaultDao.getTotalSizeBytes())
            assertEquals(listOf("vf-1.vault"), vaultDao.getAllStorageNames())
            assertNotNull(vaultDao.findFileBySha256InGroup("abcdef0123456789", "vg-1"))
            assertNull(vaultDao.findFileBySha256InGroup("abcdef0123456789", null))
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_8_to_9_directExecution_isIdempotentAndIncremental() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(testDbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `bank_cards` (`id` TEXT NOT NULL, `cardNumber` TEXT NOT NULL, `bankName` TEXT NOT NULL, `bankCode` TEXT NOT NULL, `cardHolderName` TEXT NOT NULL, `iban` TEXT NOT NULL, `expiryYear` TEXT NOT NULL, `expiryMonth` TEXT NOT NULL, `cvv2` TEXT NOT NULL, `colorStartHex` TEXT NOT NULL, `colorEndHex` TEXT NOT NULL, `accountNumber` TEXT NOT NULL, `notes` TEXT NOT NULL, `customFieldsJson` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `reminderMonthsBefore` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL("INSERT INTO `bank_cards` VALUES ('c1', '1234', 'سامان', '056', 'تست', 'IR1', '1409', '01', '111', '#000', '#111', '1', '', '', 0, 2, 500)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val sqliteDb = helper.writableDatabase
        try {
            MIGRATION_8_9.migrate(sqliteDb)
            // Running again with IF NOT EXISTS should not fail
            MIGRATION_8_9.migrate(sqliteDb)

            sqliteDb.query("SELECT id, bankName FROM bank_cards WHERE id = 'c1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("سامان", cursor.getString(1))
            }
            sqliteDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('vault_groups', 'vault_files')").use { cursor ->
                assertEquals(2, cursor.count)
            }
        } finally {
            sqliteDb.close()
        }
    }
}
