package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BankCard
import com.example.ui.components.AddEditCardDialog
import com.example.ui.components.BankCardItem
import com.example.util.IranianBankHelper

@Composable
fun CardsScreen(
    cards: List<BankCard>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFavoriteToggle: (BankCard) -> Unit,
    onDeleteCard: (BankCard) -> Unit,
    onSaveCard: (BankCard) -> Unit,
    onNavigateToScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<BankCard?>(null) }
    var filterFavoritesOnly by remember { mutableStateOf(false) }

    var isFabExpanded by remember { mutableStateOf(false) }

    val displayedCards = remember(cards, filterFavoritesOnly) {
        if (filterFavoritesOnly) cards.filter { it.isFavorite } else cards
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(visible = isFabExpanded) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Option 1: Camera Scan
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                onNavigateToScan()
                            },
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Scan Card")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("اسکن هوشمند (OCR)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Option 2: Add Card
                        SmallFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                editingCard = null
                                showAddDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CreditCard, contentDescription = "Add Card")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("افزودن دستی کارت", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Main Circular + Floating Action Button
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = com.example.ui.theme.GoldPrimary,
                    contentColor = Color.Black,
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Expand Add Menu"
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("جستجوی نام بانک، شماره کارت، شبا...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = com.example.ui.theme.GoldPrimary) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        androidx.compose.material3.IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "پاک کردن", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = com.example.ui.theme.GoldPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Modern Redesigned Segmented Filter Toggle
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, com.example.ui.theme.CustomCyanBlue.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val filterAccentColor = com.example.ui.theme.CustomCyanBlue

                    // Option 1: All Cards
                    val isAllSelected = !filterFavoritesOnly
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { filterFavoritesOnly = false },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAllSelected) filterAccentColor else Color.Transparent,
                        border = if (isAllSelected) BorderStroke(1.dp, filterAccentColor) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreditCard,
                                contentDescription = null,
                                tint = if (isAllSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "همه کارت‌ها (${cards.size})",
                                fontSize = 13.sp,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAllSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Option 2: Pinned Cards
                    val isPinnedSelected = filterFavoritesOnly
                    val pinnedCount = cards.count { it.isFavorite }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { filterFavoritesOnly = true },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isPinnedSelected) filterAccentColor else Color.Transparent,
                        border = if (isPinnedSelected) BorderStroke(1.dp, filterAccentColor) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                tint = if (isPinnedSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "پین‌شده‌ها ($pinnedCount)",
                                fontSize = 13.sp,
                                fontWeight = if (isPinnedSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isPinnedSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (displayedCards.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CreditCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (filterFavoritesOnly) "هیچ کارت علامت‌گذاری شده‌ای وجود ندارد" else "هیچ کارت بانکی ثبت نشده است",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "با اسکن هوشمند یا به صورت دستی اولین کارت بانکی خود را اضافه کنید",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(displayedCards, key = { it.id }) { card ->
                        BankCardItem(
                            card = card,
                            onFavoriteToggle = { onFavoriteToggle(card) },
                            onEditClick = {
                                editingCard = card
                                showAddDialog = true
                            },
                            onDeleteClick = { onDeleteCard(card) },
                            onShareClick = {
                                val textToShare = """
                                    کارت بانکی: ${card.bankName}
                                    صاحب کارت: ${card.cardHolderName}
                                    شماره کارت: ${IranianBankHelper.formatCardNumberDisplay(card.cardNumber)}
                                    شماره شبا: ${IranianBankHelper.formatIbanDisplay(card.iban)}
                                """.trimIndent()

                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, textToShare)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "اشتراک‌گذاری اطلاعات کارت")
                                context.startActivity(shareIntent)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditCardDialog(
            initialCard = editingCard,
            onDismiss = {
                showAddDialog = false
                editingCard = null
            },
            onSave = { updatedCard ->
                onSaveCard(updatedCard)
                showAddDialog = false
                editingCard = null
                Toast.makeText(context, "کارت با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
