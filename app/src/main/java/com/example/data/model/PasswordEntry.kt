package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.util.SecurityManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

data class CustomPasswordField(
    val label: String = "",
    val value: String = "",
    val isHidden: Boolean = false
)

@Entity(tableName = "password_entries")
data class PasswordEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val groupId: String? = null,
    val title: String,
    val username: String = "",
    val password: String = "",
    val website: String = "",
    val notes: String = "",
    val customFieldsJson: String = "[]",
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Encrypts sensitive fields (password, username, notes, customFieldsJson) using SecurityManager.
     * Title and website remain plain text for search and display.
     * Fails loudly on any encryption failure per Rule 15.6.
     */
    fun encrypted(): PasswordEntry = copy(
        password = SecurityManager.encryptField(password),
        username = SecurityManager.encryptField(username),
        notes = SecurityManager.encryptField(notes),
        customFieldsJson = SecurityManager.encryptField(customFieldsJson)
    )

    /**
     * Decrypts sensitive fields (password, username, notes, customFieldsJson) using SecurityManager.
     */
    fun decrypted(): PasswordEntry = copy(
        password = SecurityManager.decryptField(password),
        username = SecurityManager.decryptField(username),
        notes = SecurityManager.decryptField(notes),
        customFieldsJson = SecurityManager.decryptField(customFieldsJson)
    )

    fun getCustomFields(): List<CustomPasswordField> {
        if (customFieldsJson.isBlank()) return emptyList()
        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val type = Types.newParameterizedType(List::class.java, CustomPasswordField::class.java)
            val adapter = moshi.adapter<List<CustomPasswordField>>(type)
            adapter.fromJson(customFieldsJson) ?: emptyList()
        } catch (e: Exception) {
            // Fallback parser in case of delimiter format
            customFieldsJson.split("\n").mapNotNull { line ->
                val parts = line.split(":::")
                if (parts.size >= 2 && parts[0].isNotBlank()) {
                    val label = parts[0].trim()
                    val value = parts[1].trim()
                    val isHidden = if (parts.size >= 3) parts[2].trim().toBooleanStrictOrNull() ?: false else false
                    CustomPasswordField(label = label, value = value, isHidden = isHidden)
                } else null
            }
        }
    }

    companion object {
        fun encodeCustomFields(fields: List<CustomPasswordField>): String {
            val valid = fields.filter { it.label.isNotBlank() }
            return try {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val type = Types.newParameterizedType(List::class.java, CustomPasswordField::class.java)
                val adapter = moshi.adapter<List<CustomPasswordField>>(type)
                adapter.toJson(valid)
            } catch (e: Exception) {
                valid.joinToString("\n") { "${it.label.trim()}:::${it.value.trim()}:::${it.isHidden}" }
            }
        }
    }
}
