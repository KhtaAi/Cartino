package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.IdentityDocument
import com.example.util.IranianBankHelper

@Composable
fun DocumentCardItem(
    doc: IdentityDocument,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFlipped by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val cardRotationY by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "docFlipAnimation"
    )

    fun copyToClipboard(label: String, textToCopy: String) {
        if (textToCopy.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label کپی شد", Toast.LENGTH_SHORT).show()
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("تایید حذف مدرک شناسایی", fontWeight = FontWeight.Bold) },
            text = {
                Text("آیا از حذف مدرک «${doc.title}» اطمینان دارید؟ این عملیات قابل بازگشت نیست.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("حذف مدرک", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    val placements = doc.getFieldPlacements()
    val customFields = doc.getCustomFields()
    val isValidNationalCode = IranianBankHelper.validateNationalCode(doc.nationalCode)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = cardRotationY
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (cardRotationY <= 90f) {
                // FRONT SIDE
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Badge,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = doc.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${doc.docType.titleFa} (روی کارت)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Rotate Icon to flip to back
                            IconButton(
                                onClick = { isFlipped = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlipToBack,
                                    contentDescription = "چرخش کارت به پشت",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "گزینه‌های مدرک",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("ویرایش اطلاعات") },
                                        onClick = {
                                            showMenu = false
                                            onEditClick()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("حذف مدرک", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            showDeleteConfirmDialog = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Fields assigned to FRONT
                    val frontCustomFields = customFields.filter { it.side == "FRONT" && it.value.isNotBlank() }
                    val showFrontNationalCode = (placements["nationalCode"] ?: "FRONT") == "FRONT" && doc.nationalCode.isNotBlank()
                    val showFrontDocNum = (placements["documentNumber"] ?: "FRONT") == "FRONT" && doc.documentNumber.isNotBlank()
                    val showFrontIssueDate = (placements["issueDate"] ?: "FRONT") == "FRONT" && doc.issueDate.isNotBlank()
                    val showFrontExpiryDate = (placements["expiryDate"] ?: "FRONT") == "FRONT" && doc.expiryDate.isNotBlank()
                    val showFrontNotes = (placements["notes"] ?: "BACK") == "FRONT" && doc.notes.isNotBlank()

                    val hasAnyFrontFields = showFrontNationalCode || showFrontDocNum || showFrontIssueDate || showFrontExpiryDate || showFrontNotes || frontCustomFields.isNotEmpty()

                    if (!hasAnyFrontFields) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "هیچ فیلدی برای روی کارت تنظیم نشده است. با کلیک روی آیکن چرخش، پشت کارت را ببینید.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // National Code
                            if (showFrontNationalCode) {
                                DocValueRow(
                                    label = "کد ملی",
                                    value = doc.nationalCode,
                                    isValid = isValidNationalCode,
                                    onCopy = { copyToClipboard("کد ملی", doc.nationalCode) }
                                )
                            }

                            // Document Number
                            if (showFrontDocNum) {
                                DocValueRow(
                                    label = "شماره مدرک / سریال",
                                    value = doc.documentNumber,
                                    onCopy = { copyToClipboard("شماره مدرک", doc.documentNumber) }
                                )
                            }

                            // Dates Row
                            if (showFrontIssueDate || showFrontExpiryDate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (showFrontIssueDate) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            DocValueRow(
                                                label = "تاریخ صدور",
                                                value = doc.issueDate,
                                                onCopy = { copyToClipboard("تاریخ صدور", doc.issueDate) }
                                            )
                                        }
                                    }
                                    if (showFrontExpiryDate) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            DocValueRow(
                                                label = "تاریخ انقضا",
                                                value = doc.expiryDate,
                                                onCopy = { copyToClipboard("تاریخ انقضا", doc.expiryDate) }
                                            )
                                        }
                                    }
                                }
                            }

                            // Notes
                            if (showFrontNotes) {
                                DocValueRow(
                                    label = "یادداشت",
                                    value = doc.notes,
                                    onCopy = { copyToClipboard("یادداشت", doc.notes) }
                                )
                            }

                            // Custom Fields
                            frontCustomFields.forEach { cf ->
                                DocValueRow(
                                    label = cf.label,
                                    value = cf.value,
                                    isHidden = cf.isHidden,
                                    onCopy = { copyToClipboard(cf.label, cf.value) }
                                )
                            }
                        }
                    }
                }
            } else {
                // BACK SIDE (Mirrored back via 180deg Y rotation)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { rotationY = 180f }
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "پشت مدرک ${doc.title}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Flip Back Icon Button
                        IconButton(
                            onClick = { isFlipped = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipToFront,
                                contentDescription = "بازگشت به روی کارت",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Fields assigned to BACK
                    val backCustomFields = customFields.filter { it.side == "BACK" && it.value.isNotBlank() }
                    val showBackNationalCode = (placements["nationalCode"] ?: "FRONT") == "BACK" && doc.nationalCode.isNotBlank()
                    val showBackDocNum = (placements["documentNumber"] ?: "FRONT") == "BACK" && doc.documentNumber.isNotBlank()
                    val showBackIssueDate = (placements["issueDate"] ?: "FRONT") == "BACK" && doc.issueDate.isNotBlank()
                    val showBackExpiryDate = (placements["expiryDate"] ?: "FRONT") == "BACK" && doc.expiryDate.isNotBlank()
                    val showBackNotes = (placements["notes"] ?: "BACK") == "BACK" && doc.notes.isNotBlank()

                    val hasAnyBackFields = showBackNationalCode || showBackDocNum || showBackIssueDate || showBackExpiryDate || showBackNotes || backCustomFields.isNotEmpty()

                    if (!hasAnyBackFields) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "هیچ فیلدی برای پشت این کارت ثبت یا تنظیم نشده است.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = onEditClick) {
                                    Text("افزودن فیلد به پشت کارت", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showBackNationalCode) {
                                DocValueRow(
                                    label = "کد ملی",
                                    value = doc.nationalCode,
                                    isValid = isValidNationalCode,
                                    onCopy = { copyToClipboard("کد ملی", doc.nationalCode) }
                                )
                            }

                            if (showBackDocNum) {
                                DocValueRow(
                                    label = "شماره مدرک / سریال",
                                    value = doc.documentNumber,
                                    onCopy = { copyToClipboard("شماره مدرک", doc.documentNumber) }
                                )
                            }

                            if (showBackIssueDate || showBackExpiryDate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (showBackIssueDate) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            DocValueRow(
                                                label = "تاریخ صدور",
                                                value = doc.issueDate,
                                                onCopy = { copyToClipboard("تاریخ صدور", doc.issueDate) }
                                            )
                                        }
                                    }
                                    if (showBackExpiryDate) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            DocValueRow(
                                                label = "تاریخ انقضا",
                                                value = doc.expiryDate,
                                                onCopy = { copyToClipboard("تاریخ انقضا", doc.expiryDate) }
                                            )
                                        }
                                    }
                                }
                            }

                            if (showBackNotes) {
                                DocValueRow(
                                    label = "یادداشت",
                                    value = doc.notes,
                                    onCopy = { copyToClipboard("یادداشت", doc.notes) }
                                )
                            }

                            backCustomFields.forEach { cf ->
                                DocValueRow(
                                    label = cf.label,
                                    value = cf.value,
                                    isHidden = cf.isHidden,
                                    onCopy = { copyToClipboard(cf.label, cf.value) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DocValueRow(
    label: String,
    value: String,
    isValid: Boolean = false,
    isHidden: Boolean = false,
    onCopy: () -> Unit
) {
    var isRevealed by remember { mutableStateOf(false) }
    val displayValue = if (isHidden && !isRevealed) "••••••" else value

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isValid) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Valid",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isHidden) {
                    IconButton(
                        onClick = { isRevealed = !isRevealed },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isRevealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (isRevealed) "پنهان کردن" else "نمایش",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
