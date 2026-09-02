package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.util.SecurityManager
import java.util.UUID

@Entity(tableName = "generated_password_history")
data class GeneratedPasswordHistory(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val password: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Encrypts the generated password using SecurityManager.
     * Fails loudly on any encryption failure per Rule 15.6.
     */
    fun encrypted(): GeneratedPasswordHistory = copy(
        password = SecurityManager.encryptField(password)
    )

    /**
     * Decrypts the generated password using SecurityManager.
     */
    fun decrypted(): GeneratedPasswordHistory = copy(
        password = SecurityManager.decryptField(password)
    )
}
