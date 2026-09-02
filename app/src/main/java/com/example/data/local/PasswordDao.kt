package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.GeneratedPasswordHistory
import com.example.data.model.PasswordEntry
import com.example.data.model.PasswordGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordDao {

    // ==========================================
    // PasswordGroup Operations
    // ==========================================

    @Query("SELECT * FROM password_groups ORDER BY name ASC")
    fun getAllGroups(): Flow<List<PasswordGroup>>

    @Query("SELECT * FROM password_groups WHERE parentGroupId IS NULL ORDER BY name ASC")
    fun getRootGroups(): Flow<List<PasswordGroup>>

    @Query("SELECT * FROM password_groups WHERE (:parentGroupId IS NULL AND parentGroupId IS NULL) OR parentGroupId = :parentGroupId ORDER BY name ASC")
    fun getGroupsByParentId(parentGroupId: String?): Flow<List<PasswordGroup>>

    @Query("SELECT * FROM password_groups WHERE parentGroupId = :parentGroupId ORDER BY name ASC")
    fun getChildGroups(parentGroupId: String): Flow<List<PasswordGroup>>

    @Query("SELECT * FROM password_groups WHERE id = :id")
    suspend fun getGroupById(id: String): PasswordGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: PasswordGroup)

    @Update
    suspend fun updateGroup(group: PasswordGroup)

    @Delete
    suspend fun deleteGroup(group: PasswordGroup)

    @Query("DELETE FROM password_groups WHERE id = :id")
    suspend fun deleteGroupById(id: String)

    @Query("DELETE FROM password_groups")
    suspend fun clearAllGroups()

    // ==========================================
    // PasswordEntry Operations
    // ==========================================

    @Query("SELECT * FROM password_entries ORDER BY isFavorite DESC, updatedAt DESC")
    fun getAllEntries(): Flow<List<PasswordEntry>>

    @Query("SELECT * FROM password_entries WHERE groupId = :groupId ORDER BY isFavorite DESC, updatedAt DESC")
    fun getEntriesByGroup(groupId: String): Flow<List<PasswordEntry>>

    @Query("SELECT * FROM password_entries WHERE (:groupId IS NULL AND groupId IS NULL) OR groupId = :groupId ORDER BY isFavorite DESC, updatedAt DESC")
    fun getEntriesByGroupId(groupId: String?): Flow<List<PasswordEntry>>

    @Query("SELECT * FROM password_entries WHERE groupId IS NULL ORDER BY isFavorite DESC, updatedAt DESC")
    fun getRootEntries(): Flow<List<PasswordEntry>>

    @Query("SELECT * FROM password_entries WHERE id = :id")
    suspend fun getEntryById(id: String): PasswordEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PasswordEntry)

    @Update
    suspend fun updateEntry(entry: PasswordEntry)

    @Delete
    suspend fun deleteEntry(entry: PasswordEntry)

    @Query("DELETE FROM password_entries WHERE id = :id")
    suspend fun deleteEntryById(id: String)

    @Query("DELETE FROM password_entries WHERE groupId = :groupId")
    suspend fun deleteEntriesByGroupId(groupId: String)

    @Query("DELETE FROM password_entries")
    suspend fun clearAllEntries()

    // ==========================================
    // GeneratedPasswordHistory Operations
    // ==========================================

    @Query("SELECT * FROM generated_password_history ORDER BY createdAt DESC")
    fun getAllHistory(): Flow<List<GeneratedPasswordHistory>>

    @Query("SELECT * FROM generated_password_history WHERE id = :id")
    suspend fun getHistoryById(id: String): GeneratedPasswordHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryItem(item: GeneratedPasswordHistory)

    @Update
    suspend fun updateHistory(item: GeneratedPasswordHistory)

    @Delete
    suspend fun deleteHistory(item: GeneratedPasswordHistory)

    @Query("DELETE FROM generated_password_history WHERE id = :id")
    suspend fun deleteHistoryById(id: String)

    @Query("DELETE FROM generated_password_history WHERE id NOT IN (SELECT id FROM generated_password_history ORDER BY createdAt DESC LIMIT :keepCount)")
    suspend fun trimHistory(keepCount: Int = 20)

    @Query("DELETE FROM generated_password_history")
    suspend fun clearAllHistory()

    @Transaction
    suspend fun insertHistory(item: GeneratedPasswordHistory) {
        insertHistoryItem(item)
        trimHistory(20)
    }

    @Transaction
    suspend fun insertHistoryWithLimit(item: GeneratedPasswordHistory, keepCount: Int) {
        insertHistoryItem(item)
        trimHistory(keepCount)
    }
}
