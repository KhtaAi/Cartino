package com.example.util

import com.example.data.model.CustomPasswordField
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.Writer
import java.util.UUID

enum class BitwardenFormat {
    JSON,
    CSV
}

data class BitwardenIntermediateItem(
    val folderId: String? = null,
    val folderName: String? = null,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val website: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val customFields: List<CustomPasswordField> = emptyList()
)

data class BitwardenParseResult(
    val items: List<BitwardenIntermediateItem>,
    val skippedCount: Int
)

data class BitwardenFolder(
    val id: String,
    val name: String
)

object BitwardenPasswordParser {

    /**
     * Parses a Bitwarden export stream, automatically detecting JSON vs CSV.
     * Rejects ZIP archives and encrypted JSON files with explicit Persian error messages (Rule 15.6 Fail-Loud).
     */
    fun parseBitwardenStream(inputStream: InputStream): BitwardenParseResult {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("فایل انتخاب‌شده خالی است یا حاوی ردیف معتبری نیست")
        }

        // Detect ZIP magic bytes: PK\x03\x04 (0x50, 0x4B, 0x03, 0x04)
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        ) {
            throw IllegalArgumentException("فایل‌های فشرده (ZIP) پشتیبانی نمی‌شوند. لطفاً فایل استخراج‌شدهٔ JSON یا CSV را انتخاب کنید.")
        }

        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1)
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("فایل انتخاب‌شده خالی است یا حاوی ردیف معتبری نیست")
        }

        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseBitwardenJson(trimmed)
        } else {
            parseBitwardenCsv(trimmed)
        }
    }

    /**
     * Parses Bitwarden unencrypted JSON export format.
     */
    fun parseBitwardenJson(jsonString: String): BitwardenParseResult {
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("فرمت JSON فایل نامعتبر است: ${e.message}")
        }

        // Rule: If encrypted is true in root, stop with explicit Persian error
        if (root.optBoolean("encrypted", false)) {
            throw IllegalArgumentException("فرمت رمزنگاری‌شدهٔ بیتواردن فعلاً پشتیبانی نمی‌شود؛ لطفاً از خروجی معمولی .json یا .csv استفاده کنید.")
        }

        // Build folder id -> folder name map
        val folderMap = mutableMapOf<String, String>()
        val foldersArray = root.optJSONArray("folders")
        if (foldersArray != null) {
            for (i in 0 until foldersArray.length()) {
                val fObj = foldersArray.optJSONObject(i) ?: continue
                val fId = fObj.optString("id", "").trim()
                val fName = fObj.optString("name", "").trim()
                if (fId.isNotBlank() && fName.isNotBlank()) {
                    folderMap[fId] = fName
                }
            }
        }

        val itemsArray = root.optJSONArray("items") ?: return BitwardenParseResult(emptyList(), 0)
        val resultItems = mutableListOf<BitwardenIntermediateItem>()
        var skippedCount = 0

        for (i in 0 until itemsArray.length()) {
            val itemObj = itemsArray.optJSONObject(i)
            if (itemObj == null) {
                skippedCount++
                continue
            }

            // Only process type 1 (Login) items; skip note/card/identity (types 2, 3, 4)
            val itemType = itemObj.optInt("type", -1)
            if (itemType != 1) {
                skippedCount++
                continue
            }

            val rawName = itemObj.optString("name", "").trim()
            val notes = if (itemObj.isNull("notes")) "" else itemObj.optString("notes", "")
            val isFavorite = itemObj.optBoolean("favorite", false)
            val folderId = if (itemObj.isNull("folderId")) null else itemObj.optString("folderId", "").trim().takeIf { it.isNotBlank() }
            val folderName = if (folderId != null) folderMap[folderId] else null

            val loginObj = itemObj.optJSONObject("login")
            val username = if (loginObj == null || loginObj.isNull("username")) "" else loginObj.optString("username", "")
            val password = if (loginObj == null || loginObj.isNull("password")) "" else loginObj.optString("password", "")
            val totp = if (loginObj == null || loginObj.isNull("totp")) "" else loginObj.optString("totp", "").trim()

            var website = ""
            val urisArray = loginObj?.optJSONArray("uris")
            if (urisArray != null && urisArray.length() > 0) {
                val firstUriObj = urisArray.optJSONObject(0)
                website = if (firstUriObj == null || firstUriObj.isNull("uri")) "" else firstUriObj.optString("uri", "").trim()
            }

            val title = when {
                rawName.isNotBlank() -> rawName
                username.isNotBlank() -> username
                website.isNotBlank() -> website
                else -> "بدون نام"
            }

            // Skip item if totally blank
            if (rawName.isBlank() && username.isBlank() && password.isBlank() && website.isBlank()) {
                skippedCount++
                continue
            }

            val customFields = mutableListOf<CustomPasswordField>()
            if (totp.isNotBlank()) {
                customFields.add(CustomPasswordField(label = "totp", value = totp, isHidden = true))
            }

            val fieldsArray = itemObj.optJSONArray("fields")
            if (fieldsArray != null) {
                for (f in 0 until fieldsArray.length()) {
                    val fObj = fieldsArray.optJSONObject(f) ?: continue
                    val fName = fObj.optString("name", "").trim()
                    val fVal = fObj.optString("value", "")
                    val fType = fObj.optInt("type", 0) // 1 = hidden, 0 = text
                    if (fName.isNotBlank()) {
                        if (fName.equals("totp", ignoreCase = true) && customFields.any { it.label.equals("totp", ignoreCase = true) }) {
                            continue
                        }
                        customFields.add(
                            CustomPasswordField(
                                label = fName,
                                value = fVal,
                                isHidden = (fType == 1)
                            )
                        )
                    }
                }
            }

            resultItems.add(
                BitwardenIntermediateItem(
                    folderId = folderId,
                    folderName = folderName,
                    title = title,
                    username = username,
                    password = password,
                    website = website,
                    notes = notes,
                    isFavorite = isFavorite,
                    customFields = customFields
                )
            )
        }

        return BitwardenParseResult(resultItems, skippedCount)
    }

    /**
     * Parses Bitwarden CSV export format.
     */
    fun parseBitwardenCsv(csvContent: String): BitwardenParseResult {
        val rows = PasswordCsvParser.readCsvRows(csvContent.reader())
        if (rows.isEmpty()) {
            return BitwardenParseResult(emptyList(), 0)
        }

        val headerRow = rows[0]
        val headerMap = headerRow.mapIndexed { index, col ->
            col.trim().removePrefix("\uFEFF").lowercase() to index
        }.toMap()

        val folderIdx = headerMap["folder"] ?: -1
        val favIdx = headerMap["favorite"] ?: -1
        val typeIdx = headerMap["type"] ?: -1
        val nameIdx = headerMap["name"] ?: -1
        val notesIdx = headerMap["notes"] ?: -1
        val fieldsIdx = headerMap["fields"] ?: -1
        val uriIdx = headerMap["login_uri"] ?: headerMap["uri"] ?: headerMap["url"] ?: -1
        val usernameIdx = headerMap["login_username"] ?: headerMap["username"] ?: -1
        val passwordIdx = headerMap["login_password"] ?: headerMap["password"] ?: -1
        val totpIdx = headerMap["login_totp"] ?: headerMap["totp"] ?: -1

        val resultItems = mutableListOf<BitwardenIntermediateItem>()
        var skippedCount = 0

        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue

            // Only process type "login" or "1"
            if (typeIdx != -1 && typeIdx < row.size) {
                val typeVal = row[typeIdx].trim().lowercase()
                if (typeVal != "login" && typeVal != "1") {
                    skippedCount++
                    continue
                }
            }

            fun getCol(idx: Int): String = if (idx != -1 && idx < row.size) row[idx].trim() else ""

            val folderVal = getCol(folderIdx)
            val favVal = getCol(favIdx).lowercase()
            val isFavorite = favVal == "1" || favVal == "true"
            val rawName = getCol(nameIdx)
            val notes = getCol(notesIdx)
            val uri = getCol(uriIdx)
            val username = getCol(usernameIdx)
            val password = getCol(passwordIdx)
            val totp = getCol(totpIdx)

            val title = when {
                rawName.isNotBlank() -> rawName
                username.isNotBlank() -> username
                uri.isNotBlank() -> uri
                else -> "بدون نام"
            }

            if (rawName.isBlank() && username.isBlank() && password.isBlank() && uri.isBlank()) {
                skippedCount++
                continue
            }

            val customFields = mutableListOf<CustomPasswordField>()
            if (totp.isNotBlank()) {
                customFields.add(CustomPasswordField(label = "totp", value = totp, isHidden = true))
            }

            val fieldsText = if (fieldsIdx != -1 && fieldsIdx < row.size) row[fieldsIdx] else ""
            if (fieldsText.isNotBlank()) {
                val lines = fieldsText.split("\n", "\r")
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotBlank()) {
                        val colonIdx = trimmedLine.indexOf(':')
                        if (colonIdx != -1) {
                            val fLabel = trimmedLine.substring(0, colonIdx).trim()
                            val fValue = trimmedLine.substring(colonIdx + 1).trim()
                            if (fLabel.isNotBlank()) {
                                if (fLabel.equals("totp", ignoreCase = true) && customFields.any { it.label.equals("totp", ignoreCase = true) }) {
                                    continue
                                }
                                customFields.add(CustomPasswordField(label = fLabel, value = fValue, isHidden = false))
                            }
                        } else {
                            customFields.add(CustomPasswordField(label = trimmedLine, value = "", isHidden = false))
                        }
                    }
                }
            }

            resultItems.add(
                BitwardenIntermediateItem(
                    folderId = null,
                    folderName = folderVal.takeIf { it.isNotBlank() },
                    title = title,
                    username = username,
                    password = password,
                    website = uri,
                    notes = notes,
                    isFavorite = isFavorite,
                    customFields = customFields
                )
            )
        }

        return BitwardenParseResult(resultItems, skippedCount)
    }

    /**
     * Writes Bitwarden standard JSON export format.
     */
    fun writeBitwardenJson(
        writer: Writer,
        folders: List<BitwardenFolder>,
        items: List<BitwardenIntermediateItem>
    ) {
        val rootObj = JSONObject()
        rootObj.put("encrypted", false)

        val foldersArray = JSONArray()
        val folderNameToIdMap = mutableMapOf<String, String>()
        for (folder in folders) {
            val fObj = JSONObject()
            fObj.put("id", folder.id)
            fObj.put("name", folder.name)
            foldersArray.put(fObj)
            folderNameToIdMap[folder.name] = folder.id
        }
        rootObj.put("folders", foldersArray)

        val itemsArray = JSONArray()
        for (item in items) {
            val itemObj = JSONObject()
            itemObj.put("id", UUID.randomUUID().toString())
            itemObj.put("organizationId", JSONObject.NULL)

            val resolvedFolderId = item.folderId ?: item.folderName?.let { folderNameToIdMap[it] }
            if (resolvedFolderId != null) {
                itemObj.put("folderId", resolvedFolderId)
            } else {
                itemObj.put("folderId", JSONObject.NULL)
            }

            itemObj.put("type", 1)
            itemObj.put("reprompt", 0)
            itemObj.put("name", item.title)
            itemObj.put("notes", if (item.notes.isNotBlank()) item.notes else JSONObject.NULL)
            itemObj.put("favorite", item.isFavorite)

            val loginObj = JSONObject()
            val urisArray = JSONArray()
            if (item.website.isNotBlank()) {
                val uObj = JSONObject()
                uObj.put("match", JSONObject.NULL)
                uObj.put("uri", item.website)
                urisArray.put(uObj)
            }
            loginObj.put("uris", urisArray)
            loginObj.put("username", if (item.username.isNotBlank()) item.username else JSONObject.NULL)
            loginObj.put("password", if (item.password.isNotBlank()) item.password else JSONObject.NULL)

            val totpField = item.customFields.firstOrNull { it.label.trim().equals("totp", ignoreCase = true) }
            if (totpField != null && totpField.value.isNotBlank()) {
                loginObj.put("totp", totpField.value)
            } else {
                loginObj.put("totp", JSONObject.NULL)
            }
            itemObj.put("login", loginObj)

            val fieldsArray = JSONArray()
            for (field in item.customFields) {
                if (field.label.trim().equals("totp", ignoreCase = true)) {
                    continue
                }
                val fObj = JSONObject()
                fObj.put("name", field.label)
                fObj.put("value", field.value)
                fObj.put("type", if (field.isHidden) 1 else 0)
                fieldsArray.put(fObj)
            }
            itemObj.put("fields", fieldsArray)

            itemsArray.put(itemObj)
        }
        rootObj.put("items", itemsArray)

        writer.write(rootObj.toString(2))
        writer.flush()
    }

    /**
     * Writes Bitwarden standard CSV export format according to RFC 4180.
     */
    fun writeBitwardenCsv(
        writer: Writer,
        items: List<BitwardenIntermediateItem>
    ) {
        writer.write("folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n")
        for (item in items) {
            val folder = escapeCsv(item.folderName ?: "")
            val favorite = if (item.isFavorite) "1" else "0"
            val type = "login"
            val name = escapeCsv(item.title)
            val notes = escapeCsv(item.notes)

            val totpField = item.customFields.firstOrNull { it.label.trim().equals("totp", ignoreCase = true) }
            val loginTotp = escapeCsv(totpField?.value ?: "")

            val otherFields = item.customFields.filter { !it.label.trim().equals("totp", ignoreCase = true) }
            val fieldsText = otherFields.joinToString("\n") { "${it.label}: ${it.value}" }
            val fieldsEscaped = escapeCsv(fieldsText)

            val reprompt = "0"
            val loginUri = escapeCsv(item.website)
            val loginUsername = escapeCsv(item.username)
            val loginPassword = escapeCsv(item.password)

            writer.write("$folder,$favorite,$type,$name,$notes,$fieldsEscaped,$reprompt,$loginUri,$loginUsername,$loginPassword,$loginTotp\n")
        }
        writer.flush()
    }

    fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        return if (needsQuotes) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
