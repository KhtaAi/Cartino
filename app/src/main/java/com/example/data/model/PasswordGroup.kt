package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "password_groups")
data class PasswordGroup(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentGroupId: String? = null,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
