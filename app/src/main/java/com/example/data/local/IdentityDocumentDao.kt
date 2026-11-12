package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.DocumentGroup
import com.example.data.model.IdentityDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDocumentDao {

    // ==========================================
    // DocumentGroup Operations
    // ==========================================

    @Query("SELECT * FROM document_groups ORDER BY name ASC")
    fun getAllGroups(): Flow<List<DocumentGroup>>

    @Query("SELECT * FROM document_groups")
    suspend fun getAllGroupsList(): List<DocumentGroup>

    @Query("SELECT * FROM document_groups WHERE LOWER(name) = LOWER(:name) AND ((:parentGroupId IS NULL AND parentGroupId IS NULL) OR parentGroupId = :parentGroupId) LIMIT 1")
    suspend fun findGroupByNameAndParent(name: String, parentGroupId: String?): DocumentGroup?

    @Query("SELECT * FROM document_groups WHERE parentGroupId IS NULL ORDER BY name ASC")
    fun getRootGroups(): Flow<List<DocumentGroup>>

    @Query("SELECT * FROM document_groups WHERE parentGroupId = :parentGroupId ORDER BY name ASC")
    fun getChildGroups(parentGroupId: String): Flow<List<DocumentGroup>>

    @Query("SELECT * FROM document_groups WHERE id = :id")
    suspend fun getGroupById(id: String): DocumentGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: DocumentGroup)

    @Update
    suspend fun updateGroup(group: DocumentGroup)

    @Delete
    suspend fun deleteGroup(group: DocumentGroup)

    @Query("DELETE FROM document_groups WHERE id = :id")
    suspend fun deleteGroupById(id: String)

    @Query("DELETE FROM document_groups")
    suspend fun clearAllGroups()

    // ==========================================
    // IdentityDocument Operations
    // ==========================================

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

    // ==========================================
    // Document Group-related operations
    // ==========================================

    @Query("SELECT COUNT(*) FROM identity_documents WHERE groupId = :groupId")
    suspend fun countDocumentsByGroup(groupId: String): Int

    @Query("SELECT * FROM identity_documents WHERE groupId = :groupId")
    suspend fun getDocumentsListByGroupId(groupId: String): List<IdentityDocument>

    @Query("SELECT * FROM identity_documents WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun getDocumentsByGroup(groupId: String): Flow<List<IdentityDocument>>

    @Query("SELECT * FROM identity_documents WHERE (:groupId IS NULL AND groupId IS NULL) OR groupId = :groupId ORDER BY createdAt DESC")
    fun getDocumentsByGroupId(groupId: String?): Flow<List<IdentityDocument>>

    @Query("DELETE FROM identity_documents WHERE groupId = :groupId")
    suspend fun deleteDocumentsByGroupId(groupId: String)
}
