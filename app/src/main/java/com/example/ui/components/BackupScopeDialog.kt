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
import androidx.compose.ui.unit.sp

enum class BackupScope {
    APP_DATA_ONLY, // فقط داده‌های اپ (.enc)
    VAULT_ONLY,    // فقط صندوق (.cvault)
    BOTH           // هر دو
}

enum class ScopeDialogMode {
    BACKUP,
    RESTORE
}

@Composable
fun BackupScopeDialog(
    mode: ScopeDialogMode,
    initialScope: BackupScope = BackupScope.APP_DATA_ONLY,
    onConfirm: (BackupScope) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedScope by remember { mutableStateOf(initialScope) }

    val dialogTitle = if (mode == ScopeDialogMode.BACKUP) {
        "محدوده پشتیبان‌گیری"
    } else {
        "محدوده بازیابی"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = dialogTitle,
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
                    text = if (mode == ScopeDialogMode.BACKUP) {
                        "بخش‌های مورد نظر برای ایجاد پشتیبان را انتخاب کنید:"
                    } else {
                        "بخش‌های مورد نظر برای بازیابی اطلاعات را انتخاب کنید:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Option 1: App Data Only (.enc)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedScope == BackupScope.APP_DATA_ONLY) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedScope = BackupScope.APP_DATA_ONLY }
                        .testTag("scope_option_app_data")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedScope == BackupScope.APP_DATA_ONLY),
                            onClick = { selectedScope = BackupScope.APP_DATA_ONLY }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "فقط داده‌های اپ (.enc)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedScope == BackupScope.APP_DATA_ONLY) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Option 2: Vault Only (.cvault)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedScope == BackupScope.VAULT_ONLY) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedScope = BackupScope.VAULT_ONLY }
                        .testTag("scope_option_vault")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedScope == BackupScope.VAULT_ONLY),
                            onClick = { selectedScope = BackupScope.VAULT_ONLY }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "فقط صندوق (.cvault)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedScope == BackupScope.VAULT_ONLY) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Option 3: Both (.enc & .cvault)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedScope == BackupScope.BOTH) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedScope = BackupScope.BOTH }
                        .testTag("scope_option_both")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedScope == BackupScope.BOTH),
                                onClick = { selectedScope = BackupScope.BOTH }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "هر دو",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedScope == BackupScope.BOTH) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        if (selectedScope == BackupScope.BOTH) {
                            Text(
                                text = if (mode == ScopeDialogMode.BACKUP) {
                                    "دو فایل جداگانه ساخته می‌شوند"
                                } else {
                                    "دو فایل جداگانه بازیابی می‌شوند"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedScope) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("scope_dialog_confirm")
            ) {
                Text("ادامه")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("scope_dialog_dismiss")
            ) {
                Text("انصراف")
            }
        }
    )
}
