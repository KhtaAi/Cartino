package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.BankCard
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument

class DocumentTypeConverter {
    @TypeConverter
    fun fromDocumentType(type: DocumentType): String = type.name

    @TypeConverter
    fun toDocumentType(value: String): DocumentType = runCatching { DocumentType.valueOf(value) }.getOrDefault(DocumentType.OTHER)
}

@Database(entities = [BankCard::class, IdentityDocument::class], version = 3, exportSchema = false)
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
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
