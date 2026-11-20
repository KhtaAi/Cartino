package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.MergeStrategy

@Composable
fun VaultMergeDialog(
    initialStrategy: MergeStrategy = MergeStrategy.SKIP_DUPLICATES,
    onConfirm: (MergeStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedStrategy by remember { mutableStateOf(initialStrategy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "تداخل در بازیابی صندوق",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "برخی از گروه‌ها یا فایل‌های آرشیو با موارد موجود در صندوق هم‌نام هستند. لطفاً نحوه رفتار با موارد تکراری را تعیین کنید:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Strategy 1: SKIP_DUPLICATES (Default)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedStrategy == MergeStrategy.SKIP_DUPLICATES) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStrategy = MergeStrategy.SKIP_DUPLICATES }
                        .testTag("merge_option_skip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedStrategy == MergeStrategy.SKIP_DUPLICATES),
                            onClick = { selectedStrategy = MergeStrategy.SKIP_DUPLICATES }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "رد فایل‌های تکراری",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedStrategy == MergeStrategy.SKIP_DUPLICATES) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Strategy 2: REPLACE_DUPLICATES
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedStrategy == MergeStrategy.REPLACE_DUPLICATES) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStrategy = MergeStrategy.REPLACE_DUPLICATES }
                        .testTag("merge_option_replace")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedStrategy == MergeStrategy.REPLACE_DUPLICATES),
                            onClick = { selectedStrategy = MergeStrategy.REPLACE_DUPLICATES }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "جایگزینی فایل‌های تکراری",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedStrategy == MergeStrategy.REPLACE_DUPLICATES) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Strategy 3: RENAME_DUPLICATES
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedStrategy == MergeStrategy.RENAME_DUPLICATES) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStrategy = MergeStrategy.RENAME_DUPLICATES }
                        .testTag("merge_option_rename")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedStrategy == MergeStrategy.RENAME_DUPLICATES),
                            onClick = { selectedStrategy = MergeStrategy.RENAME_DUPLICATES }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "حفظ هر دو با تغییر نام",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedStrategy == MergeStrategy.RENAME_DUPLICATES) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedStrategy) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("vault_merge_dialog_confirm")
            ) {
                Text("ادامه")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("vault_merge_dialog_dismiss")
            ) {
                Text("انصراف")
            }
        }
    )
}
