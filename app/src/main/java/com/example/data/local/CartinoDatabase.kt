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
import com.example.data.model.IdentityDocument

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

@Database(entities = [BankCard::class, IdentityDocument::class], version = 4, exportSchema = false)
@TypeConverters(DocumentTypeConverter::class)
abstract class CartinoDatabase : RoomDatabase() {
    abstract fun bankCardDao(): BankCardDao
    abstract fun identityDocumentDao(): IdentityDocumentDao

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
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
