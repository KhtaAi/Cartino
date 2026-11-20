package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "vault_files")
data class VaultFile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val groupId: String? = null,
    val displayName: String,
    val originalFileName: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long,
    val sha256: String,
    val storageName: String,
    val encryptionVersion: Int = 1,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
