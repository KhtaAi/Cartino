package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GeneratedPasswordHistory
import com.example.ui.CartinoViewModel
import com.example.ui.theme.EmeraldStatus
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.ClipboardAutoClearManager
import java.security.SecureRandom

private fun generateSecurePassword(
    length: Int,
    includeUpper: Boolean,
    includeLower: Boolean,
    includeDigits: Boolean,
    includeSymbols: Boolean,
    avoidAmbiguous: Boolean
): String {
    var upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    var lower = "abcdefghijklmnopqrstuvwxyz"
    var digits = "0123456789"
    var symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?"

    if (avoidAmbiguous) {
        val ambiguous = setOf('l', '1', 'I', 'O', '0', 'o')
        upper = upper.filter { it !in ambiguous }
        lower = lower.filter { it !in ambiguous }
        digits = digits.filter { it !in ambiguous }
        symbols = symbols.filter { it !in ambiguous }
    }

    val activeSets = mutableListOf<String>()
    if (includeUpper && upper.isNotEmpty()) activeSets.add(upper)
    if (includeLower && lower.isNotEmpty()) activeSets.add(lower)
    if (includeDigits && digits.isNotEmpty()) activeSets.add(digits)
    if (includeSymbols && symbols.isNotEmpty()) activeSets.add(symbols)

    if (activeSets.isEmpty()) {
        activeSets.add(lower.ifEmpty { "abcdefghijklmnopqrstuvwxyz" })
    }

    val allPool = activeSets.joinToString("")
    val random = SecureRandom()
    val passwordChars = mutableListOf<Char>()

    // Guarantee at least one from each active set
    for (set in activeSets) {
        passwordChars.add(set[random.nextInt(set.length)])
    }

    // Fill the remainder
    val targetLen = length.coerceAtLeast(passwordChars.size)
    while (passwordChars.size < targetLen) {
        passwordChars.add(allPool[random.nextInt(allPool.length)])
    }

    passwordChars.shuffle(random)
    return passwordChars.joinToString("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorDialog(
    viewModel: CartinoViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var passwordLength by remember { mutableFloatStateOf(16f) }
    var includeUpper by remember { mutableStateOf(true) }
    var includeLower by remember { mutableStateOf(true) }
    var includeDigits by remember { mutableStateOf(true) }
    var includeSymbols by remember { mutableStateOf(true) }
    var avoidAmbiguous by remember { mutableStateOf(false) }

    var generatedPassword by remember { mutableStateOf("") }

    val historyList by viewModel.passwordHistory.collectAsState()

    fun regenerate() {
        generatedPassword = generateSecurePassword(
            length = passwordLength.toInt(),
            includeUpper = includeUpper,
            includeLower = includeLower,
            includeDigits = includeDigits,
            includeSymbols = includeSymbols,
            avoidAmbiguous = avoidAmbiguous
        )
    }

    LaunchedEffect(passwordLength, includeUpper, includeLower, includeDigits, includeSymbols, avoidAmbiguous) {
        regenerate()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = VazirmatnFontFamily)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Text(
                    text = "تولیدکننده پسورد امن",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // Password Display Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = generatedPassword,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.5.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("generated_password_display")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Password", generatedPassword))
                                    ClipboardAutoClearManager.onCopied(context, generatedPassword)
                                    viewModel.addGeneratedPasswordHistory(generatedPassword)
                                    Toast.makeText(context, "کپی شد و در تاریخچه ذخیره شد", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldStatus),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("copy_generated_password_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("کپی", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { regenerate() },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("regenerate_password_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تولید مجدد", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Controls Section
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Length Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "طول پسورد:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "${passwordLength.toInt()}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = GoldPrimary
                                )
                            }
                        }

                        Slider(
                            value = passwordLength,
                            onValueChange = { passwordLength = it },
                            valueRange = 8f..128f,
                            steps = 119,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_length_slider")
                        )

                        // Toggles
                        GeneratorOptionRow(
                            title = "حروف بزرگ (A-Z)",
                            checked = includeUpper,
                            onCheckedChange = { includeUpper = it }
                        )

                        GeneratorOptionRow(
                            title = "حروف کوچک (a-z)",
                            checked = includeLower,
                            onCheckedChange = { includeLower = it }
                        )

                        GeneratorOptionRow(
                            title = "ارقام (0-9)",
                            checked = includeDigits,
                            onCheckedChange = { includeDigits = it }
                        )

                        GeneratorOptionRow(
                            title = "نمادها (!@#$%...)",
                            checked = includeSymbols,
                            onCheckedChange = { includeSymbols = it }
                        )

                        GeneratorOptionRow(
                            title = "پرهیز از نویسه‌های مبهم (l, 1, I, O, 0)",
                            checked = avoidAmbiguous,
                            onCheckedChange = { avoidAmbiguous = it }
                        )
                    }
                }

                // History Section
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تاریخچه پسوردهای تولیدشده (${historyList.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (historyList.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.clearPasswordHistory() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "پاکسازی تاریخچه",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (historyList.isEmpty()) {
                        Text(
                            text = "هنوز هیچ پسوردی تولید یا ذخیره نشده است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        historyList.forEach { historyItem ->
                            HistoryItemRow(historyItem = historyItem)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratorOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun HistoryItemRow(
    historyItem: GeneratedPasswordHistory
) {
    val context = LocalContext.current
    var isRevealed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = if (isRevealed) historyItem.password else "•".repeat(historyItem.password.length.coerceAtMost(16)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { isRevealed = !isRevealed },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isRevealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (isRevealed) "پنهان‌سازی" else "نمایش",
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Password", historyItem.password))
                        ClipboardAutoClearManager.onCopied(context, historyItem.password)
                        Toast.makeText(context, "کپی شد", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "کپی",
                        tint = EmeraldStatus,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
