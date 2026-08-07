package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("انصراف", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

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
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("ذخیره مدرک", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        },
        dismissButton = null,
        title = {
            Text(
                text = if (initialDocument != null) "ویرایش مدرک شناسایی" else "افزودن مدرک شناسایی جدید",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = isDropdownExpanded,
                    onExpandedChange = { isDropdownExpanded = !isDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedType.titleFa,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("نوع مدرک") },
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

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان مدرک") },
                    placeholder = { Text("مثال: کارت ملی هوشمند") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Section Title
                Text("فیلدهای اصلی مدرک:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)

                // National Code Input + Side Placement
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("کد ملی ۱۰ رقمی", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        CompactSideSelector(
                            currentSide = defaultPlacements["nationalCode"] ?: "FRONT",
                            onSideSelected = { defaultPlacements["nationalCode"] = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
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

                // Document Number + Side Placement
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("شماره مدرک / سریال", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        CompactSideSelector(
                            currentSide = defaultPlacements["documentNumber"] ?: "FRONT",
                            onSideSelected = { defaultPlacements["documentNumber"] = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = documentNumber,
                        onValueChange = { documentNumber = it },
                        placeholder = { Text("شماره یا سریال مدرک (اختیاری)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Dates: Issue Date & Expiry Date
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("تاریخ صدور", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        CompactSideSelector(
                            currentSide = defaultPlacements["issueDate"] ?: "FRONT",
                            onSideSelected = { defaultPlacements["issueDate"] = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = issueDate,
                        onValueChange = { issueDate = it },
                        placeholder = { Text("1398/04/15") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("تاریخ انقضا / اعتبار", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        CompactSideSelector(
                            currentSide = defaultPlacements["expiryDate"] ?: "FRONT",
                            onSideSelected = { defaultPlacements["expiryDate"] = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = expiryDate,
                        onValueChange = { expiryDate = it },
                        placeholder = { Text("1408/04/15") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Notes
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("توضیحات و یادداشت (اختیاری)", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        CompactSideSelector(
                            currentSide = defaultPlacements["notes"] ?: "BACK",
                            onSideSelected = { defaultPlacements["notes"] = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        placeholder = { Text("توضیحات اضافی...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Section: Custom Fields
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("فیلدهای اختصاصی سفارشی:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
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
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    customFields.forEachIndexed { index, field ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("فیلد سفارشی شماره ${index + 1}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CompactSideSelector(
                                            currentSide = field.side,
                                            onSideSelected = { newSide ->
                                                customFields[index] = customFields[index].copy(side = newSide)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { customFields.removeAt(index) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "حذف فیلد", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
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
        }
    )
}

@Composable
fun CompactSideSelector(
    currentSide: String,
    onSideSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier.clip(RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isFront = currentSide == "FRONT"
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isFront) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.clickable { onSideSelected("FRONT") }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FlipToFront,
                        contentDescription = null,
                        tint = if (isFront) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        "روی کارت",
                        fontSize = 10.sp,
                        fontWeight = if (isFront) FontWeight.Bold else FontWeight.Normal,
                        color = if (isFront) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val isBack = currentSide == "BACK"
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isBack) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.clickable { onSideSelected("BACK") }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FlipToBack,
                        contentDescription = null,
                        tint = if (isBack) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        "پشت کارت",
                        fontSize = 10.sp,
                        fontWeight = if (isBack) FontWeight.Bold else FontWeight.Normal,
                        color = if (isBack) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
