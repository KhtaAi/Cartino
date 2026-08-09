package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.PasskeyManager
import com.example.util.findActivity

@Composable
fun PasskeySetupDialog(
    onConfirmEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) onDismiss() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "راه‌اندازی احراز هویت دو مرحله‌ای (Passkey)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Passkey یک روش احراز هویت دو مرحله‌ای امن است که با اثر انگشت یا چهره شما کار می‌کند. Passkey در Google Password Manager شما ذخیره می‌شود و در همه دستگاه‌های شما قابل استفاده است.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "پس از فعال‌سازی، هنگام بازیابی اطلاعات، می‌توانید با Passkey احراز هویت کنید.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activity = context.findActivity()
                        if (activity != null) {
                            isProcessing = true
                            PasskeyManager.createPasskey(
                                activity = activity,
                                onSuccess = {
                                    isProcessing = false
                                    onConfirmEnable()
                                },
                                onError = { errorMessage ->
                                    isProcessing = false
                                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            Toast.makeText(context, "خطا در دسترسی به محیط برنامه", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("فعال‌سازی Passkey", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isProcessing,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}
