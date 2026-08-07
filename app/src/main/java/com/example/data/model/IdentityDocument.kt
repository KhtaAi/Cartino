package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.util.SecurityManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

enum class DocumentType(val titleFa: String) {
    NATIONAL_CARD("کارت ملی"),
    BIRTH_CERTIFICATE("شناسنامه"),
    DRIVING_LICENSE("گواهینامه رانندگی"),
    PASSPORT("گذرنامه"),
    OTHER("سایر مدارک")
}

data class CustomDocField(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val value: String = "",
    val side: String = "FRONT" // "FRONT" or "BACK"
)

@Entity(tableName = "identity_documents")
data class IdentityDocument(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val docType: DocumentType,
    val nationalCode: String = "",
    val documentNumber: String = "",
    val issueDate: String = "",
    val expiryDate: String = "",
    val imagePathFront: String? = null,
    val imagePathBack: String? = null,
    val notes: String = "",
    val customFieldsJson: String = "[]",
    val fieldPlacementsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun encrypted(): IdentityDocument = copy(
        nationalCode = SecurityManager.encryptField(nationalCode),
        documentNumber = SecurityManager.encryptField(documentNumber)
    )

    fun decrypted(): IdentityDocument = copy(
        nationalCode = SecurityManager.decryptField(nationalCode),
        documentNumber = SecurityManager.decryptField(documentNumber)
    )

    fun getCustomFields(): List<CustomDocField> {
        if (customFieldsJson.isBlank()) return emptyList()
        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val type = Types.newParameterizedType(List::class.java, CustomDocField::class.java)
            val adapter = moshi.adapter<List<CustomDocField>>(type)
            adapter.fromJson(customFieldsJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getFieldPlacements(): Map<String, String> {
        val defaultMap = mapOf(
            "nationalCode" to "FRONT",
            "documentNumber" to "FRONT",
            "issueDate" to "FRONT",
            "expiryDate" to "FRONT",
            "notes" to "BACK"
        )
        if (fieldPlacementsJson.isBlank()) return defaultMap
        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val type = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
            val adapter = moshi.adapter<Map<String, String>>(type)
            val parsed = adapter.fromJson(fieldPlacementsJson) ?: emptyMap()
            defaultMap + parsed
        } catch (e: Exception) {
            defaultMap
        }
    }
}
