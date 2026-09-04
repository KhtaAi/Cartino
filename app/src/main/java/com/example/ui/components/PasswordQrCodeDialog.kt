package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.VazirmatnFontFamily
import com.example.util.TotpManager

@Composable
fun PasswordQrCodeDialog(
    fieldLabel: String = "کلمه عبور",
    secretValue: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(secretValue) {
        TotpManager.generateQrCodeBitmap(secretValue, 512, 512)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "اسکن برای دریافت $fieldLabel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = VazirmatnFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code برای انتقال امن",
                        modifier = Modifier
                            .size(250.dp)
                            .padding(12.dp)
                            .testTag("password_qr_code_image")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "برای انتقال امن بدون استفاده از کلیپ‌بورد، این بارکد را با دستگاه دیگر اسکن کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontFamily = VazirmatnFontFamily,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_qr_dialog_button")
            ) {
                Text(
                    text = "بستن",
                    fontFamily = VazirmatnFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
