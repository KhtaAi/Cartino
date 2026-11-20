package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.VaultFile
import java.util.Locale

internal fun formatVaultFileSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    return when {
        safeBytes < 1024L -> "\u2068$safeBytes\u2069 بایت"
        safeBytes < 1024L * 1024L -> {
            val kb = String.format(Locale.US, "%.1f", safeBytes / 1024.0)
            "\u2068$kb KB\u2069"
        }
        else -> {
            val mb = String.format(Locale.US, "%.2f", safeBytes / (1024.0 * 1024.0))
            "\u2068$mb MB\u2069"
        }
    }
}

internal fun iconForVaultMimeType(mimeType: String): ImageVector {
    val normalized = mimeType.trim().lowercase(Locale.US)
    return when {
        normalized.startsWith("image/") -> Icons.Default.Image
        normalized == "application/pdf" -> Icons.Default.PictureAsPdf
        normalized.startsWith("audio/") -> Icons.Default.AudioFile
        normalized.startsWith("video/") -> Icons.Default.VideoFile
        normalized.startsWith("text/") -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

@Composable
fun VaultFileItem(
    file: VaultFile,
    onFileClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val formattedSize = formatVaultFileSize(file.sizeBytes)
    val subtitleText = if (
        file.originalFileName.isNotBlank() &&
        file.originalFileName.trim() != file.displayName.trim()
    ) {
        "$formattedSize • ${file.originalFileName}"
    } else {
        formattedSize
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onFileClick)
            .testTag("vault_file_${file.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = iconForVaultMimeType(file.mimeType),
                        contentDescription = "فایل صندوق",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.padding(top = 2.dp))
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
