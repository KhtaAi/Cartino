package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "vault_groups")
data class VaultGroup(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentGroupId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
