package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.IdentityDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDocumentDao {
    @Query("SELECT * FROM identity_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<IdentityDocument>>

    @Query("SELECT * FROM identity_documents WHERE id = :id")
    suspend fun getDocumentById(id: String): IdentityDocument?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: IdentityDocument)

    @Update
    suspend fun updateDocument(document: IdentityDocument)

    @Delete
    suspend fun deleteDocument(document: IdentityDocument)

    @Query("DELETE FROM identity_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    @Query("DELETE FROM identity_documents")
    suspend fun clearAll()
}
