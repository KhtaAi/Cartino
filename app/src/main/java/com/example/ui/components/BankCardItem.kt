package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.example.util.ClipboardAutoClearManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import com.example.data.model.CustomCardField
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.IranianBankHelper
import com.example.util.formatIban
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BankCard

@Composable
fun BankCardItem(
    card: BankCard,
    onFavoriteToggle: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isCvvVisible by remember { mutableStateOf(false) }
    var lastCvvClickTime by remember { mutableStateOf(0L) }
    var showMenu by remember { mutableStateOf(false) }
    var isFlipped by remember { mutableStateOf(false) }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showPinnedCannotDeleteDialog by remember { mutableStateOf(false) }

    val bank = IranianBankHelper.getBankByCardNumber(card.cardNumber)
    val logoText = bank.logoSymbol.replace("_", " ")
    val logoSize = when {
        logoText.length <= 6 -> 10.sp
        logoText.length <= 9 -> 8.sp
        else -> 7.sp
    }

    val startColor = runCatching { Color(android.graphics.Color.parseColor(card.colorStartHex)) }.getOrDefault(Color(0xFF1E293B))
    val endColor = runCatching { Color(android.graphics.Color.parseColor(card.colorEndHex)) }.getOrDefault(Color(0xFF334155))

    val gradientBrush = Brush.linearGradient(
        colors = listOf(startColor, endColor)
    )

    // Smooth 3D Card Flip Rotation
    val rotationY by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "cardFlipAnimation"
    )

    fun copyToClipboard(label: String, textToCopy: String, toastMessage: String) {
        if (textToCopy.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, textToCopy)
        clipboard.setPrimaryClip(clip)
        ClipboardAutoClearManager.onCopied(context, textToCopy)

        val prefs = context.applicationContext.getSharedPreferences("cartino_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("clipboard_auto_clear_enabled", true)
        val seconds = prefs.getInt("clipboard_auto_clear_seconds", 30)

        val finalToast = if (enabled) {
            "کپی شد — پاکسازی خودکار پس از $seconds ثانیه"
        } else {
            "کپی شد"
        }
        Toast.makeText(context, finalToast, Toast.LENGTH_SHORT).show()
    }

    if (showPinnedCannotDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showPinnedCannotDeleteDialog = false },
            title = { Text("امکان حذف کارت پین‌شده وجود ندارد", fontWeight = FontWeight.Bold) },
            text = {
                Text("این کارت پین شده است. برای حذف این کارت، ابتدا باید آن را از حالت پین خارج کنید.")
            },
            confirmButton = {
                Button(onClick = { showPinnedCannotDeleteDialog = false }) {
                    Text("متوجه شدم")
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("تایید حذف کارت بانکی", fontWeight = FontWeight.Bold) },
            text = {
                Text("آیا از حذف کارت بانک ${card.bankName} (${card.cardNumber.takeLast(4)}) اطمینان دارید؟ این عملیات قابل بازگشت نیست.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("حذف کارت", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.rotationY = rotationY
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(20.dp)
        ) {
            // Front Side of Card (Default Fields)
            if (rotationY <= 90f) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Top Row: Bank Name, Logo, Favorite Pin & Actions (Flip + Overflow)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val logoResId = context.resources.getIdentifier(bank.logoSymbol.lowercase(), "drawable", context.packageName)
                            if (logoResId != 0) {
                                Image(
                                    painter = painterResource(id = logoResId),
                                    contentDescription = bank.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White.copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = logoText,
                                            color = Color.White,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = logoSize,
                                            letterSpacing = 0.5.sp,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 10.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = card.bankName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            if (card.isFavorite) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFF59E0B).copy(alpha = 0.25f),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PushPin,
                                            contentDescription = "Pinned",
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Top Action Icons: Flip Button + Overflow Menu
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Animated Flip Button
                            IconButton(
                                onClick = { isFlipped = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlipToBack,
                                    contentDescription = "چرخش و مشاهده پشت کارت",
                                    tint = Color.White
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Card Options Menu",
                                        tint = Color.White
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (card.isFavorite) "خروج از حالت پین" else "پین کردن کارت") },
                                        onClick = {
                                            showMenu = false
                                            onFavoriteToggle()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (card.isFavorite) Icons.Outlined.StarBorder else Icons.Default.PushPin,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("اشتراک‌گذاری کارت") },
                                        onClick = {
                                            showMenu = false
                                            onShareClick()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Share, contentDescription = null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("ویرایش اطلاعات کارت") },
                                        onClick = {
                                            showMenu = false
                                            onEditClick()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("حذف کارت", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            if (card.isFavorite) {
                                                showPinnedCannotDeleteDialog = true
                                            } else {
                                                showDeleteConfirmDialog = true
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // 16-Digit Card Number Box
                    val formattedCardNumber = IranianBankHelper.formatCardNumberDisplay(card.cardNumber)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .clickable { copyToClipboard("Card Number", card.cardNumber, "شماره کارت کپی شد") }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Card Number",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "کپی کارت",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = formattedCardNumber,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = VazirmatnFontFamily,
                                    letterSpacing = 0.5.sp,
                                    fontSize = 15.sp
                                ),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Card Details: Cardholder, Expiry Date, CVV2 (Large Touch Target for CVV2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(text = "صاحب کارت", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                            Text(
                                text = card.cardHolderName.ifBlank { "ثبت نشده" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "انقضا", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "${card.expiryYear}/${card.expiryMonth}".ifBlank { "--/--" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // CVV2 Section: Single tap = copy, Double tap = toggle visibility
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val now = System.currentTimeMillis()
                                    if (now - lastCvvClickTime < 380) {
                                        // Fast double click: toggle show/hide CVV2
                                        isCvvVisible = !isCvvVisible
                                    } else {
                                        // Single click: copy CVV2 to clipboard
                                        if (card.cvv2.isNotBlank()) {
                                            copyToClipboard("CVV2", card.cvv2, "کد CVV2 کپی شد (دوبار کلیک سریع جهت نمایش)")
                                        }
                                    }
                                    lastCvvClickTime = now
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(text = "CVV2", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                            Spacer(modifier = Modifier.height(2.dp))
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = if (isCvvVisible) card.cvv2.ifBlank { "---" } else "•••",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Account Number (شماره حساب) Row
                    if (card.accountNumber.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    copyToClipboard("Account Number", card.accountNumber, "شماره حساب کپی شد")
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Account Number",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "کپی حساب",
                                        fontSize = 10.sp,
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = "شماره حساب: ${card.accountNumber}",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontFamily = VazirmatnFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // IBAN (شماره شبا) Row
                    if (card.iban.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    copyToClipboard("IBAN", formatIban(card.iban), "شماره شبا کپی شد")
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.25f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy IBAN",
                                        tint = Color(0xFF34D399),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "کپی شبا",
                                        fontSize = 10.sp,
                                        color = Color(0xFF34D399),
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = formatIban(card.iban),
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontFamily = VazirmatnFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Back Side of Card (Custom Fields & Notes ONLY) - Mirror corrected via graphicsLayer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.rotationY = 180f
                        }
                ) {
                    // Header of Back Card
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "پشت کارت ${card.bankName}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Button to flip back to front
                        IconButton(
                            onClick = { isFlipped = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipToFront,
                                contentDescription = "بازگشت به روی کارت",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val customFields = card.getCustomFields()
                    val hasNotes = card.notes.isNotBlank()

                    if (customFields.isEmpty() && !hasNotes) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "هیچ فیلد اختصاصی یا یادداشتی ثبت نشده است",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onEditClick,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                                ) {
                                    Text("افزودن فیلد اختصاصی", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (customFields.isNotEmpty()) {
                                Text(
                                    text = "فیلدهای اختصاصی:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                                customFields.forEach { field ->
                                    CardCustomValueRow(
                                        field = field,
                                        onCopy = { label, value -> copyToClipboard(label, value, "$label کپی شد") }
                                    )
                                }
                            }

                            if (hasNotes) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "یادداشت‌ها:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.25f)
                                ) {
                                    Text(
                                        text = card.notes,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardCustomValueRow(
    field: CustomCardField,
    onCopy: (String, String) -> Unit
) {
    var isRevealed by remember { mutableStateOf(false) }
    val displayValue = if (field.isHidden && !isRevealed) "••••••" else field.value

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(field.label, field.value) },
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.25f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${field.label}: $displayValue",
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (field.isHidden) {
                    IconButton(
                        onClick = { isRevealed = !isRevealed },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isRevealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (isRevealed) "پنهان کردن" else "نمایش",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { onCopy(field.label, field.value) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy ${field.label}",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

