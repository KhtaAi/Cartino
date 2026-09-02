package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.BankCard
import com.example.data.model.DocumentType
import com.example.data.model.GeneratedPasswordHistory
import com.example.data.model.IdentityDocument
import com.example.data.model.PasswordEntry
import com.example.data.model.PasswordGroup

class DocumentTypeConverter {
    @TypeConverter
    fun fromDocumentType(type: DocumentType): String = type.name

    @TypeConverter
    fun toDocumentType(value: String): DocumentType = runCatching { DocumentType.valueOf(value) }.getOrDefault(DocumentType.OTHER)
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bank_cards ADD COLUMN reminderMonthsBefore INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE identity_documents ADD COLUMN reminderMonthsBefore INTEGER NOT NULL DEFAULT 2")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `password_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentGroupId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `password_entries` (`id` TEXT NOT NULL, `groupId` TEXT, `title` TEXT NOT NULL, `username` TEXT NOT NULL, `password` TEXT NOT NULL, `website` TEXT NOT NULL, `notes` TEXT NOT NULL, `customFieldsJson` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `generated_password_history` (`id` TEXT NOT NULL, `password` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE password_entries ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [
        BankCard::class,
        IdentityDocument::class,
        PasswordGroup::class,
        PasswordEntry::class,
        GeneratedPasswordHistory::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(DocumentTypeConverter::class)
abstract class CartinoDatabase : RoomDatabase() {
    abstract fun bankCardDao(): BankCardDao
    abstract fun identityDocumentDao(): IdentityDocumentDao
    abstract fun passwordDao(): PasswordDao

    companion object {
        @Volatile
        private var INSTANCE: CartinoDatabase? = null

        fun getDatabase(context: Context): CartinoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CartinoDatabase::class.java,
                    "cartino_secure_db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
