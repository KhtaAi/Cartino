package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    // ==========================================
    // VaultGroup Operations
    // ==========================================

    @Query("SELECT * FROM vault_groups ORDER BY name ASC")
    fun getAllGroups(): Flow<List<VaultGroup>>

    @Query("SELECT * FROM vault_groups ORDER BY name ASC")
    suspend fun getAllGroupsList(): List<VaultGroup>

    @Query("SELECT * FROM vault_groups WHERE id = :id")
    suspend fun getGroupById(id: String): VaultGroup?

    @Query(
        "SELECT * FROM vault_groups " +
            "WHERE LOWER(name) = LOWER(:name) " +
            "AND ((:parentGroupId IS NULL AND parentGroupId IS NULL) OR parentGroupId = :parentGroupId) " +
            "LIMIT 1"
    )
    suspend fun findGroupByNameAndParent(name: String, parentGroupId: String?): VaultGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: VaultGroup)

    @Update
    suspend fun updateGroup(group: VaultGroup)

    @Query("DELETE FROM vault_groups WHERE id = :id")
    suspend fun deleteGroupById(id: String)

    @Query("SELECT COUNT(*) FROM vault_groups WHERE parentGroupId = :groupId")
    suspend fun countChildGroups(groupId: String): Int

    @Query("SELECT COUNT(*) FROM vault_files WHERE groupId = :groupId")
    suspend fun countFilesByGroup(groupId: String): Int

    // ==========================================
    // VaultFile Operations
    // ==========================================

    @Query("SELECT * FROM vault_files ORDER BY updatedAt DESC, createdAt DESC")
    fun getAllFiles(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files ORDER BY updatedAt DESC, createdAt DESC")
    suspend fun getAllFilesList(): List<VaultFile>

    @Query("SELECT * FROM vault_files WHERE id = :id")
    suspend fun getFileById(id: String): VaultFile?

    @Query(
        "SELECT * FROM vault_files " +
            "WHERE (:groupId IS NULL AND groupId IS NULL) OR groupId = :groupId " +
            "ORDER BY updatedAt DESC, createdAt DESC"
    )
    fun getFilesByGroupId(groupId: String?): Flow<List<VaultFile>>

    @Query(
        "SELECT * FROM vault_files " +
            "WHERE (:groupId IS NULL AND groupId IS NULL) OR groupId = :groupId " +
            "ORDER BY updatedAt DESC, createdAt DESC"
    )
    suspend fun getFilesListByGroupId(groupId: String?): List<VaultFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: VaultFile)

    @Update
    suspend fun updateFile(file: VaultFile)

    @Query("DELETE FROM vault_files WHERE id = :id")
    suspend fun deleteFileById(id: String)

    @Query("DELETE FROM vault_files WHERE groupId = :groupId")
    suspend fun deleteFilesByGroupId(groupId: String)

    @Query("SELECT storageName FROM vault_files")
    suspend fun getAllStorageNames(): List<String>

    @Query("SELECT COUNT(*) FROM vault_files")
    suspend fun getTotalFileCount(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM vault_files")
    suspend fun getTotalSizeBytes(): Long

    @Query(
        "SELECT * FROM vault_files " +
            "WHERE sha256 = :sha256 " +
            "AND ((:groupId IS NULL AND groupId IS NULL) OR groupId = :groupId) " +
            "LIMIT 1"
    )
    suspend fun findFileBySha256InGroup(sha256: String, groupId: String?): VaultFile?
}
