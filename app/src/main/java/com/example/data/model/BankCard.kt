package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.util.SecurityManager
import java.util.UUID

data class CustomCardField(
    val label: String = "",
    val value: String = "",
    val isHidden: Boolean = false
)

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

    fun getCustomFields(): List<CustomCardField> {
        if (customFieldsJson.isBlank()) return emptyList()
        return customFieldsJson.split("\n")
            .mapNotNull { line ->
                val parts = line.split(":::")
                if (parts.size >= 2 && parts[0].isNotBlank()) {
                    val label = parts[0].trim()
                    val value = parts[1].trim()
                    val isHidden = if (parts.size >= 3) parts[2].trim().toBooleanStrictOrNull() ?: false else false
                    CustomCardField(label = label, value = value, isHidden = isHidden)
                } else null
            }
    }

    companion object {
        fun encodeCustomFields(fields: List<CustomCardField>): String {
            return fields
                .filter { it.label.isNotBlank() }
                .joinToString("\n") { "${it.label.trim()}:::${it.value.trim()}:::${it.isHidden}" }
        }
    }
}
