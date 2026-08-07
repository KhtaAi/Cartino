package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.BankCard
import kotlinx.coroutines.flow.Flow

@Dao
interface BankCardDao {
    @Query("SELECT * FROM bank_cards ORDER BY isFavorite DESC, createdAt DESC")
    fun getAllCards(): Flow<List<BankCard>>

    @Query("SELECT * FROM bank_cards WHERE id = :id")
    suspend fun getCardById(id: String): BankCard?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: BankCard)

    @Update
    suspend fun updateCard(card: BankCard)

    @Delete
    suspend fun deleteCard(card: BankCard)

    @Query("DELETE FROM bank_cards WHERE id = :id")
    suspend fun deleteCardById(id: String)

    @Query("DELETE FROM bank_cards")
    suspend fun clearAll()
}
