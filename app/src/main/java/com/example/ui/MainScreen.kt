package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import com.example.util.JalaliCalendarHelper
import com.example.data.model.BankCard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AddEditCardDialog
import com.example.ui.screens.CameraOcrScreen
import com.example.ui.screens.CardsScreen
import com.example.ui.screens.DocumentsScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.screens.SyncBackupScreen
import com.example.ui.theme.EmeraldStatus
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.TextMutedDark
import com.example.util.SecurityManager
import com.example.util.findActivity

enum class CartinoTab(val titleFa: String) {
    CARDS("کارت‌ها"),
    DOCUMENTS("مدارک"),
    SCANNER("اسکنر"),
    SYNC("همگام‌سازی"),
    SETTINGS("تنظیمات")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: CartinoViewModel
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(CartinoTab.CARDS) }

    val cards by viewModel.allCards.collectAsState()
    val documents by viewModel.allDocuments.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val scannedCardData by viewModel.scannedCardData.collectAsState()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val isFlagSecureEnabled by viewModel.isFlagSecureEnabled.collectAsState()

    var isUnlocked by remember { mutableStateOf(!isBiometricEnabled) }
    var showScannedDialog by remember { mutableStateOf(false) }

    var showExpiryAlertDialog by remember { mutableStateOf(false) }
    var expiringCardsList by remember { mutableStateOf<List<Pair<BankCard, Int>>>(emptyList()) }

    // Check for expiring cards (within 2 Jalali months)
    LaunchedEffect(cards, isUnlocked) {
        if (isUnlocked && cards.isNotEmpty()) {
            val expiring = cards.mapNotNull { card ->
                val status = JalaliCalendarHelper.getCardExpiryStatus(card.expiryYear, card.expiryMonth)
                if (status != null) card to status else null
            }
            if (expiring.isNotEmpty()) {
                expiringCardsList = expiring
                showExpiryAlertDialog = true
            }
        }
    }

    // Trigger Screen Protection
    LaunchedEffect(isFlagSecureEnabled) {
        val activity = context.findActivity()
        if (activity != null) {
            SecurityManager.setScreenProtection(activity, isFlagSecureEnabled)
        }
    }

    // Trigger Biometric Check on Launch if enabled
    LaunchedEffect(isBiometricEnabled) {
        if (isBiometricEnabled && !isUnlocked) {
            val activity = context.findActivity()
            if (activity != null && SecurityManager.isBiometricAvailable(context)) {
                SecurityManager.authenticateBiometric(
                    activity = activity,
                    onSuccess = { isUnlocked = true },
                    onError = { err ->
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                isUnlocked = true
            }
        } else {
            isUnlocked = true
        }
    }

    // Set Persian Right-To-Left (RTL) Layout Direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        if (!isUnlocked) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.statusBarsPadding(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(GoldPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = GoldPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text(
                            text = "مخزن امن کارتینو قفل است",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "برای دسترسی به کارت‌ها و مدارک احراز هویت کنید",
                            fontSize = 13.sp,
                            color = TextMutedDark
                        )
                        Button(
                            onClick = {
                                val activity = context.findActivity()
                                if (activity != null) {
                                    SecurityManager.authenticateBiometric(
                                        activity = activity,
                                        onSuccess = { isUnlocked = true },
                                        onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("بازکردن با اثر انگشت / چهره")
                        }
                    }
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = com.example.ui.theme.KeePassGreenBottomNav,
                        contentColor = Color.White,
                        tonalElevation = 8.dp
                    ) {
                        val navItemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            unselectedIconColor = Color(0xFF9CA3AF),
                            unselectedTextColor = Color(0xFF9CA3AF)
                        )

                        NavigationBarItem(
                            selected = currentTab == CartinoTab.CARDS,
                            onClick = { currentTab = CartinoTab.CARDS },
                            icon = { Icon(Icons.Default.CreditCard, contentDescription = "Cards") },
                            label = { Text("کارت‌ها") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = currentTab == CartinoTab.DOCUMENTS,
                            onClick = { currentTab = CartinoTab.DOCUMENTS },
                            icon = { Icon(Icons.Default.Description, contentDescription = "Documents") },
                            label = { Text("مدارک") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = currentTab == CartinoTab.SCANNER,
                            onClick = { currentTab = CartinoTab.SCANNER },
                            icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scanner") },
                            label = { Text("اسکنر") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = currentTab == CartinoTab.SYNC,
                            onClick = { currentTab = CartinoTab.SYNC },
                            icon = { Icon(Icons.Default.CloudSync, contentDescription = "Sync") },
                            label = { Text("همگام‌سازی") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = currentTab == CartinoTab.SETTINGS,
                            onClick = { currentTab = CartinoTab.SETTINGS },
                            icon = { Icon(Icons.Default.Security, contentDescription = "Settings") },
                            label = { Text("تنظیمات") },
                            colors = navItemColors
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentTab) {
                        CartinoTab.CARDS -> CardsScreen(
                            cards = cards,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                            onFavoriteToggle = { viewModel.toggleFavoriteCard(it) },
                            onDeleteCard = { viewModel.deleteCard(it) },
                            onSaveCard = { viewModel.addOrUpdateCard(it) },
                            onNavigateToScan = { currentTab = CartinoTab.SCANNER }
                        )
                        CartinoTab.DOCUMENTS -> DocumentsScreen(
                            documents = documents,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                            onDeleteDocument = { viewModel.deleteDocument(it) },
                            onSaveDocument = { viewModel.addOrUpdateDocument(it) }
                        )
                        CartinoTab.SCANNER -> CameraOcrScreen(
                            onCardScanned = { data ->
                                viewModel.setScannedCardData(data)
                                showScannedDialog = true
                            },
                            onBackClick = { currentTab = CartinoTab.CARDS }
                        )
                        CartinoTab.SYNC -> SyncBackupScreen(
                            viewModel = viewModel
                        )
                        CartinoTab.SETTINGS -> SecuritySettingsScreen(
                            viewModel = viewModel
                        )
                    }
                }
            }

            if (showScannedDialog && scannedCardData != null) {
                AddEditCardDialog(
                    scannedData = scannedCardData,
                    onDismiss = {
                        showScannedDialog = false
                        viewModel.setScannedCardData(null)
                    },
                    onSave = { newCard ->
                        viewModel.addOrUpdateCard(newCard)
                        showScannedDialog = false
                        viewModel.setScannedCardData(null)
                        currentTab = CartinoTab.CARDS
                        Toast.makeText(context, "کارت اسکن شده با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (showExpiryAlertDialog && expiringCardsList.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { showExpiryAlertDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "هشدار تاریخ انقضای کارت‌های بانکی",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "کارت‌های زیر تا دو ماه آینده منقضی می‌شوند. لطفاً جهت تمدید آن‌ها اقدام کنید:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            expiringCardsList.forEach { (card, remainingMonths) ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "بانک ${card.bankName} (${card.cardHolderName.ifBlank { card.cardNumber.takeLast(4) }})",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = "تاریخ انقضا: ${card.expiryYear}/${card.expiryMonth}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = if (remainingMonths == 0) "منقضی در این ماه" else "منقضی در $remainingMonths ماه آینده",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showExpiryAlertDialog = false }) {
                            Text("متوجه شدم")
                        }
                    }
                )
            }
        }
    }
}
