package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BankCard
import com.example.util.IranianBank
import com.example.util.IranianBankHelper
import com.example.util.ParsedCardData
import com.example.util.TextPreprocessor

@Composable
fun AddEditCardDialog(
    initialCard: BankCard? = null,
    scannedData: ParsedCardData? = null,
    onDismiss: () -> Unit,
    onSave: (BankCard) -> Unit
) {
    var cardNumber by remember {
        mutableStateOf(
            scannedData?.cardNumber ?: initialCard?.cardNumber ?: ""
        )
    }
    var cardHolderName by remember { mutableStateOf(initialCard?.cardHolderName ?: "") }
    var iban by remember {
        mutableStateOf(
            scannedData?.iban ?: initialCard?.iban ?: ""
        )
    }
    var expiryYear by remember {
        mutableStateOf(
            scannedData?.expiryYear ?: initialCard?.expiryYear ?: ""
        )
    }
    var expiryMonth by remember {
        mutableStateOf(
            scannedData?.expiryMonth ?: initialCard?.expiryMonth ?: ""
        )
    }
    var cvv2 by remember {
        mutableStateOf(
            scannedData?.cvv2 ?: initialCard?.cvv2 ?: ""
        )
    }
    var accountNumber by remember { mutableStateOf(initialCard?.accountNumber ?: "") }
    var notes by remember { mutableStateOf(initialCard?.notes ?: "") }

    val customFieldsList: SnapshotStateList<Pair<String, String>> = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            initialCard?.getCustomFields()?.let { addAll(it) }
        }
    }

    var detectedBank by remember { mutableStateOf(IranianBankHelper.getBankByCardNumber(cardNumber)) }
    var customBankName by remember { mutableStateOf(initialCard?.bankName ?: detectedBank.name) }

    var cardNumberError by remember { mutableStateOf<String?>(null) }
    var ibanError by remember { mutableStateOf<String?>(null) }

    // Live update of detected bank as user types
    LaunchedEffect(cardNumber) {
        val cleanNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(cardNumber)
        val newBank = IranianBankHelper.getBankByCardNumber(cleanNumber)
        if (newBank.name != detectedBank.name) {
            if (customBankName.isBlank() || customBankName == detectedBank.name || customBankName == "بانک ناشناخته") {
                customBankName = newBank.name
            }
            detectedBank = newBank
        }
        if (cleanNumber.length == 16) {
            cardNumberError = if (IranianBankHelper.validateLuhn(cleanNumber)) null else "شماره کارت معتبر نیست (Luhn)"
        } else {
            cardNumberError = null
        }
    }

    LaunchedEffect(iban) {
        val cleanIban = TextPreprocessor.convertPersianArabicDigitsToEnglish(iban).uppercase()
        if (cleanIban.length == 26) {
            ibanError = if (IranianBankHelper.validateIranianIban(cleanIban)) null else "شماره شبا معتبر نیست (MOD-97)"
        } else {
            ibanError = null
        }
    }

    val startColor = runCatching { Color(android.graphics.Color.parseColor(detectedBank.colorStartHex)) }.getOrDefault(Color(0xFF1E293B))
    val endColor = runCatching { Color(android.graphics.Color.parseColor(detectedBank.colorEndHex)) }.getOrDefault(Color(0xFF334155))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val cleanCardNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(cardNumber).replace(" ", "").replace("-", "")
                    val cleanIban = TextPreprocessor.convertPersianArabicDigitsToEnglish(iban).uppercase().replace(" ", "")
                    val encodedCustom = BankCard.encodeCustomFields(customFieldsList)

                    val finalBankName = customBankName.ifBlank { detectedBank.name }
                    val cardToSave = (initialCard ?: BankCard(
                        cardNumber = cleanCardNumber,
                        bankName = finalBankName,
                        bankCode = if (cleanCardNumber.length >= 6) cleanCardNumber.substring(0, 6) else "",
                        cardHolderName = cardHolderName,
                        iban = cleanIban,
                        expiryYear = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryYear),
                        expiryMonth = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryMonth),
                        cvv2 = TextPreprocessor.convertPersianArabicDigitsToEnglish(cvv2),
                        colorStartHex = detectedBank.colorStartHex,
                        colorEndHex = detectedBank.colorEndHex,
                        accountNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(accountNumber),
                        notes = notes,
                        customFieldsJson = encodedCustom
                    )).copy(
                        cardNumber = cleanCardNumber,
                        bankName = finalBankName,
                        bankCode = if (cleanCardNumber.length >= 6) cleanCardNumber.substring(0, 6) else "",
                        cardHolderName = cardHolderName,
                        iban = cleanIban,
                        expiryYear = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryYear),
                        expiryMonth = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryMonth),
                        cvv2 = TextPreprocessor.convertPersianArabicDigitsToEnglish(cvv2),
                        colorStartHex = detectedBank.colorStartHex,
                        colorEndHex = detectedBank.colorEndHex,
                        accountNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(accountNumber),
                        notes = notes,
                        customFieldsJson = encodedCustom
                    )

                    onSave(cardToSave)
                },
                enabled = cardNumber.isNotBlank() && cardNumberError == null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("ذخیره کارت", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف")
            }
        },
        title = {
            Text(
                text = if (initialCard != null) "ویرایش کارت بانکی" else "افزودن کارت بانکی جدید",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Live Bank Preview Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(startColor, endColor)))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "شناسایی هوشمند بانک: ${detectedBank.name}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "رنگ برند و لوگوی کارت به صورت خودکار اعمال شد",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Card Number Input
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = cardNumber,
                        onValueChange = { cardNumber = it },
                        label = { Text("شماره ۱۶ رقمی کارت") },
                        placeholder = { Text("6037991812345678") },
                        singleLine = true,
                        isError = cardNumberError != null,
                        supportingText = {
                            cardNumberError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Editable Bank Name
                OutlinedTextField(
                    value = customBankName,
                    onValueChange = { customBankName = it },
                    label = { Text("نام بانک (قابل ویرایش)") },
                    placeholder = { Text("مثال: ملی، سامان، رسالت...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Card Holder Name
                OutlinedTextField(
                    value = cardHolderName,
                    onValueChange = { cardHolderName = it },
                    label = { Text("نام صاحب کارت") },
                    placeholder = { Text("مثال: علی محمدی") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // IBAN Input (شماره شبا)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = iban,
                        onValueChange = { iban = it },
                        label = { Text("شماره شبا (IBAN)") },
                        placeholder = { Text("IR120170000000123456789012") },
                        singleLine = true,
                        isError = ibanError != null,
                        supportingText = {
                            ibanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Expiry Date (Year / Month) & CVV2 Row
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = expiryMonth,
                            onValueChange = { expiryMonth = it },
                            label = { Text("ماه انقضا") },
                            placeholder = { Text("08") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = expiryYear,
                            onValueChange = { expiryYear = it },
                            label = { Text("سال انقضا") },
                            placeholder = { Text("06 یا 1406") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = cvv2,
                            onValueChange = { cvv2 = it },
                            label = { Text("CVV2") },
                            placeholder = { Text("382") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Account Number
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = accountNumber,
                        onValueChange = { accountNumber = it },
                        label = { Text("شماره حساب (اختیاری)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("یادداشت (اختیاری)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Dynamic Custom Fields Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "فیلدهای اختصاصی دلخواه:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = { customFieldsList.add(Pair("", "")) }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("افزودن فیلد جدید")
                        }
                    }

                    customFieldsList.indices.forEach { index ->
                        val (fieldLabel, fieldValue) = customFieldsList[index]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = fieldLabel,
                                onValueChange = { newLabel ->
                                    customFieldsList[index] = Pair(newLabel, fieldValue)
                                },
                                label = { Text("عنوان فیلد") },
                                placeholder = { Text("مثال: رمز دوم") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )

                            OutlinedTextField(
                                value = fieldValue,
                                onValueChange = { newValue ->
                                    customFieldsList[index] = Pair(fieldLabel, newValue)
                                },
                                label = { Text("مقدار") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { customFieldsList.removeAt(index) }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove Field", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    )
}
