package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomDocField
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.util.IranianBankHelper
import com.example.util.TextPreprocessor
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditDocumentDialog(
    initialDocument: IdentityDocument? = null,
    onDismiss: () -> Unit,
    onSave: (IdentityDocument) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember { mutableStateOf(initialDocument?.title ?: "") }
    var selectedType by remember { mutableStateOf(initialDocument?.docType ?: DocumentType.NATIONAL_CARD) }
    var nationalCode by remember { mutableStateOf(initialDocument?.nationalCode ?: "") }
    var documentNumber by remember { mutableStateOf(initialDocument?.documentNumber ?: "") }
    var issueDate by remember { mutableStateOf(initialDocument?.issueDate ?: "") }
    var expiryDate by remember { mutableStateOf(initialDocument?.expiryDate ?: "") }
    var notes by remember { mutableStateOf(initialDocument?.notes ?: "") }

    // Placements for default fields (key -> "FRONT" or "BACK")
    val defaultPlacements = remember {
        mutableStateMapOf<String, String>().apply {
            val initialMap = initialDocument?.getFieldPlacements() ?: mapOf(
                "nationalCode" to "FRONT",
                "documentNumber" to "FRONT",
                "issueDate" to "FRONT",
                "expiryDate" to "FRONT",
                "notes" to "BACK"
            )
            putAll(initialMap)
        }
    }

    // Dynamic custom fields list
    val customFields = remember {
        mutableStateListOf<CustomDocField>().apply {
            initialDocument?.getCustomFields()?.let { addAll(it) }
        }
    }

    var isDropdownExpanded by remember { mutableStateOf(false) }
    var nationalCodeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(nationalCode) {
        val cleanCode = TextPreprocessor.convertPersianArabicDigitsToEnglish(nationalCode)
        if (cleanCode.isNotBlank()) {
            nationalCodeError = if (IranianBankHelper.validateNationalCode(cleanCode)) null else "کد ملی ۱۰ رقمی معتبر نیست"
        } else {
            nationalCodeError = null
        }
    }

    val moshi = remember { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sheet Title
            Text(
                text = if (initialDocument != null) "ویرایش مدرک شناسایی" else "افزودن مدرک شناسایی جدید",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Document Type Dropdown (ExposedDropdownMenuBox full width)
            ExposedDropdownMenuBox(
                expanded = isDropdownExpanded,
                onExpandedChange = { isDropdownExpanded = !isDropdownExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedType.titleFa,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("نوع مدرک", style = MaterialTheme.typography.bodyMedium) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    DocumentType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.titleFa) },
                            onClick = {
                                selectedType = type
                                if (title.isBlank()) title = type.titleFa
                                isDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Document Title Input (OutlinedTextField full width)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("عنوان مدرک", style = MaterialTheme.typography.bodyMedium) },
                placeholder = { Text("مثال: کارت ملی هوشمند") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Main Fields Section Title
            Text(
                text = "فیلدهای اصلی مدرک:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // National Code
            DocumentFieldItem(
                label = "کد ملی ۱۰ رقمی",
                side = defaultPlacements["nationalCode"] ?: "FRONT",
                onSideSelected = { defaultPlacements["nationalCode"] = it }
            ) {
                OutlinedTextField(
                    value = nationalCode,
                    onValueChange = { nationalCode = it },
                    placeholder = { Text("0012345678") },
                    singleLine = true,
                    isError = nationalCodeError != null,
                    supportingText = {
                        nationalCodeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Document Number
            DocumentFieldItem(
                label = "شماره مدرک / سریال",
                side = defaultPlacements["documentNumber"] ?: "FRONT",
                onSideSelected = { defaultPlacements["documentNumber"] = it }
            ) {
                OutlinedTextField(
                    value = documentNumber,
                    onValueChange = { documentNumber = it },
                    placeholder = { Text("شماره یا سریال مدرک (اختیاری)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Issue Date
            DocumentFieldItem(
                label = "تاریخ صدور",
                side = defaultPlacements["issueDate"] ?: "FRONT",
                onSideSelected = { defaultPlacements["issueDate"] = it }
            ) {
                OutlinedTextField(
                    value = issueDate,
                    onValueChange = { issueDate = it },
                    placeholder = { Text("1398/04/15") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Expiry Date
            DocumentFieldItem(
                label = "تاریخ انقضا / اعتبار",
                side = defaultPlacements["expiryDate"] ?: "FRONT",
                onSideSelected = { defaultPlacements["expiryDate"] = it }
            ) {
                OutlinedTextField(
                    value = expiryDate,
                    onValueChange = { expiryDate = it },
                    placeholder = { Text("1408/04/15") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Notes
            DocumentFieldItem(
                label = "توضیحات و یادداشت (اختیاری)",
                side = defaultPlacements["notes"] ?: "BACK",
                onSideSelected = { defaultPlacements["notes"] = it }
            ) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("توضیحات اضافی...") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Custom Fields Section
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "فیلدهای اختصاصی سفارشی:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(
                    onClick = {
                        customFields.add(CustomDocField(id = UUID.randomUUID().toString(), label = "", value = "", side = "BACK"))
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("افزودن فیلد جدید", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            if (customFields.isEmpty()) {
                Text(
                    text = "هیچ فیلد اختصاصی اضافه نشده است.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                customFields.forEachIndexed { index, field ->
                    DocumentFieldItem(
                        label = if (field.label.isNotBlank()) "فیلد سفارشی: ${field.label}" else "فیلد سفارشی شماره ${index + 1}",
                        side = field.side,
                        onSideSelected = { newSide ->
                            customFields[index] = customFields[index].copy(side = newSide)
                        }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("اطلاعات فیلد سفارشی", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                    IconButton(
                                        onClick = { customFields.removeAt(index) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "حذف فیلد",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = field.label,
                                    onValueChange = { newLabel ->
                                        customFields[index] = customFields[index].copy(label = newLabel)
                                    },
                                    label = { Text("نام فیلد (مثال: نام پدر / گروه خونی)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = field.value,
                                    onValueChange = { newValue ->
                                        customFields[index] = customFields[index].copy(value = newValue)
                                    },
                                    label = { Text("مقدار فیلد") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val cleanNationalCode = TextPreprocessor.convertPersianArabicDigitsToEnglish(nationalCode).trim()

                        // Serialize custom fields
                        val customFieldsType = Types.newParameterizedType(List::class.java, CustomDocField::class.java)
                        val customFieldsAdapter = moshi.adapter<List<CustomDocField>>(customFieldsType)
                        val customFieldsJsonString = customFieldsAdapter.toJson(customFields.filter { it.label.isNotBlank() && it.value.isNotBlank() })

                        // Serialize field placements
                        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                        val mapAdapter = moshi.adapter<Map<String, String>>(mapType)
                        val fieldPlacementsJsonString = mapAdapter.toJson(defaultPlacements.toMap())

                        val docToSave = (initialDocument ?: IdentityDocument(
                            title = title.ifBlank { selectedType.titleFa },
                            docType = selectedType,
                            nationalCode = cleanNationalCode,
                            documentNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(documentNumber),
                            issueDate = TextPreprocessor.convertPersianArabicDigitsToEnglish(issueDate),
                            expiryDate = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryDate),
                            notes = notes,
                            customFieldsJson = customFieldsJsonString,
                            fieldPlacementsJson = fieldPlacementsJsonString
                        )).copy(
                            title = title.ifBlank { selectedType.titleFa },
                            docType = selectedType,
                            nationalCode = cleanNationalCode,
                            documentNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(documentNumber),
                            issueDate = TextPreprocessor.convertPersianArabicDigitsToEnglish(issueDate),
                            expiryDate = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryDate),
                            notes = notes,
                            customFieldsJson = customFieldsJsonString,
                            fieldPlacementsJson = fieldPlacementsJsonString
                        )

                        onSave(docToSave)
                    },
                    enabled = nationalCodeError == null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("ذخیره مدرک", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("انصراف", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentFieldItem(
    label: String,
    side: String,
    onSideSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = side == "FRONT",
                    onClick = { onSideSelected("FRONT") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {}
                ) {
                    Text("روی کارت", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                SegmentedButton(
                    selected = side == "BACK",
                    onClick = { onSideSelected("BACK") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {}
                ) {
                    Text("پشت کارت", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        content()
    }
}
