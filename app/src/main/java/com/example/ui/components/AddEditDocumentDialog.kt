package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
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
                DocumentFieldSwipeRow(
                    label = "کد ملی ۱۰ رقمی",
                    currentSide = defaultPlacements["nationalCode"] ?: "FRONT",
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

                // Document Number + Side Placement
                DocumentFieldSwipeRow(
                    label = "شماره مدرک / سریال",
                    currentSide = defaultPlacements["documentNumber"] ?: "FRONT",
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

                // Dates: Issue Date & Expiry Date
                DocumentFieldSwipeRow(
                    label = "تاریخ صدور",
                    currentSide = defaultPlacements["issueDate"] ?: "FRONT",
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

                DocumentFieldSwipeRow(
                    label = "تاریخ انقضا / اعتبار",
                    currentSide = defaultPlacements["expiryDate"] ?: "FRONT",
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
                DocumentFieldSwipeRow(
                    label = "توضیحات و یادداشت (اختیاری)",
                    currentSide = defaultPlacements["notes"] ?: "BACK",
                    onSideSelected = { defaultPlacements["notes"] = it }
                ) {
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
                        DocumentFieldSwipeRow(
                            label = if (field.label.isNotBlank()) "فیلد سفارشی: ${field.label}" else "فیلد سفارشی شماره ${index + 1}",
                            currentSide = field.side,
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
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("اطلاعات فیلد سفارشی", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        IconButton(
                                            onClick = { customFields.removeAt(index) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "حذف فیلد", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
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
        }
    )
}

@Composable
fun DocumentFieldSwipeRow(
    label: String,
    currentSide: String,
    onSideSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val maxOffsetDp = 100.dp
    val density = LocalDensity.current
    val maxOffsetPx = with(density) { maxOffsetDp.toPx() }

    val animatedOffset by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "swipeOffset"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (currentSide == "FRONT") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, if (currentSide == "FRONT") MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                modifier = Modifier.clickable {
                    val nextSide = if (currentSide == "FRONT") "BACK" else "FRONT"
                    onSideSelected(nextSide)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (currentSide == "FRONT") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (currentSide == "FRONT") "روی کارت" else "پشت کارت",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (currentSide == "FRONT") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset > maxOffsetPx / 3f) {
                                swipeOffset = maxOffsetPx
                            } else if (swipeOffset < -maxOffsetPx / 3f) {
                                swipeOffset = -maxOffsetPx
                            } else {
                                swipeOffset = 0f
                            }
                        },
                        onDragCancel = { swipeOffset = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(-maxOffsetPx, maxOffsetPx)
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                    color = if (currentSide == "FRONT") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(maxOffsetDp)
                        .clickable {
                            onSideSelected("FRONT")
                            swipeOffset = 0f
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentSide == "FRONT") {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = "روی کارت",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (currentSide == "FRONT") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                    color = if (currentSide == "BACK") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(maxOffsetDp)
                        .clickable {
                            onSideSelected("BACK")
                            swipeOffset = 0f
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentSide == "BACK") {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = "پشت کارت",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (currentSide == "BACK") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                content()
            }
        }
    }
}
