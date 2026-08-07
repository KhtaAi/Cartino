package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.util.SecurityManager
import java.util.UUID

@Entity(tableName = "bank_cards")
data class BankCard(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val cardNumber: String,
    val bankName: String,
    val bankCode: String,
    val cardHolderName: String,
    val iban: String,
    val expiryYear: String,
    val expiryMonth: String,
    val cvv2: String,
    val colorStartHex: String,
    val colorEndHex: String,
    val accountNumber: String = "",
    val notes: String = "",
    val customFieldsJson: String = "",
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun encrypted(): BankCard = copy(
        cardNumber = SecurityManager.encryptField(cardNumber),
        cvv2 = SecurityManager.encryptField(cvv2),
        iban = SecurityManager.encryptField(iban)
    )

    fun decrypted(): BankCard = copy(
        cardNumber = SecurityManager.decryptField(cardNumber),
        cvv2 = SecurityManager.decryptField(cvv2),
        iban = SecurityManager.decryptField(iban)
    )

    fun getCustomFields(): List<Pair<String, String>> {
        if (customFieldsJson.isBlank()) return emptyList()
        return customFieldsJson.split("\n")
            .mapNotNull { line ->
                val parts = line.split(":::", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    Pair(parts[0].trim(), parts[1].trim())
                } else null
            }
    }

    companion object {
        fun encodeCustomFields(fields: List<Pair<String, String>>): String {
            return fields
                .filter { it.first.isNotBlank() }
                .joinToString("\n") { "${it.first.trim()}:::${it.second.trim()}" }
        }
    }
}
