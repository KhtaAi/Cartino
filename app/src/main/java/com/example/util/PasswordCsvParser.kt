package com.example.util

import com.example.data.model.PasswordEntry
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer
import java.util.UUID

data class ChromeCsvParseResult(
    val entries: List<PasswordEntry>,
    val skippedCount: Int
)

object PasswordCsvParser {

    /**
     * Reads all raw CSV rows from a reader according to RFC 4180.
     * Handles quoted fields containing commas, double quotes (""), and newlines.
     * Automatically ignores UTF-8 BOM (U+FEFF) at the beginning of the stream.
     */
    fun readCsvRows(reader: Reader): List<List<String>> {
        val bufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader)
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var isFirstChar = true

        var chInt = bufferedReader.read()
        while (chInt != -1) {
            val c = chInt.toChar()

            if (isFirstChar) {
                isFirstChar = false
                // Skip UTF-8 BOM if present
                if (c == '\uFEFF') {
                    chInt = bufferedReader.read()
                    continue
                }
            }

            if (inQuotes) {
                if (c == '"') {
                    bufferedReader.mark(1)
                    val nextInt = bufferedReader.read()
                    if (nextInt == '"'.code) {
                        // Escaped quote: "" -> "
                        currentField.append('"')
                    } else {
                        inQuotes = false
                        if (nextInt != -1) {
                            bufferedReader.reset()
                        }
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                    }
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                    '\r' -> {
                        bufferedReader.mark(1)
                        val nextInt = bufferedReader.read()
                        if (nextInt != '\n'.code && nextInt != -1) {
                            bufferedReader.reset()
                        }
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.isNotEmpty() && !isRowBlank(currentRow)) {
                            rows.add(ArrayList(currentRow))
                        }
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.isNotEmpty() && !isRowBlank(currentRow)) {
                            rows.add(ArrayList(currentRow))
                        }
                        currentRow.clear()
                    }
                    else -> {
                        currentField.append(c)
                    }
                }
            }
            chInt = bufferedReader.read()
        }

        // Process any trailing field/row
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            if (!isRowBlank(currentRow)) {
                rows.add(ArrayList(currentRow))
            }
        }

        return rows
    }

    private fun isRowBlank(row: List<String>): Boolean {
        return row.all { it.isBlank() }
    }

    /**
     * Parses standard Google Chrome passwords CSV.
     * Header columns name, url, username, password (and note if present) are recognized case-insensitively.
     * Sensitive fields are encrypted via entry.encrypted().
     * Invalid rows are skipped without halting the process, and reported in the result.
     */
    fun parseChromeCsv(reader: Reader, targetGroupId: String?): ChromeCsvParseResult {
        val rawRows = readCsvRows(reader)
        if (rawRows.isEmpty()) {
            return ChromeCsvParseResult(emptyList(), 0)
        }

        val headerRow = rawRows.first()
        var nameIndex = -1
        var urlIndex = -1
        var usernameIndex = -1
        var passwordIndex = -1
        var noteIndex = -1

        headerRow.forEachIndexed { index, rawHeader ->
            val col = rawHeader.trim().removePrefix("\uFEFF").lowercase()
            when (col) {
                "name", "title" -> nameIndex = index
                "url", "website" -> urlIndex = index
                "username", "user", "login" -> usernameIndex = index
                "password", "pass" -> passwordIndex = index
                "note", "notes" -> noteIndex = index
            }
        }

        val dataRows: List<List<String>>
        if (nameIndex == -1 && urlIndex == -1 && usernameIndex == -1 && passwordIndex == -1) {
            // Fallback default Chrome layout: name, url, username, password
            nameIndex = 0
            urlIndex = 1
            usernameIndex = 2
            passwordIndex = 3
            dataRows = rawRows
        } else {
            // Skip the header row
            dataRows = rawRows.drop(1)
        }

        val entries = mutableListOf<PasswordEntry>()
        var skippedCount = 0

        for (row in dataRows) {
            try {
                val rawTitle = if (nameIndex in row.indices) row[nameIndex].trim() else ""
                val rawUrl = if (urlIndex in row.indices) row[urlIndex].trim() else ""
                val rawUsername = if (usernameIndex in row.indices) row[usernameIndex] else ""
                val rawPassword = if (passwordIndex in row.indices) row[passwordIndex] else ""
                val rawNote = if (noteIndex in row.indices) row[noteIndex] else ""

                // If entire line has no identifying credentials, skip it
                if (rawTitle.isBlank() && rawUrl.isBlank() && rawUsername.isBlank() && rawPassword.isBlank()) {
                    skippedCount++
                    continue
                }

                val title = when {
                    rawTitle.isNotBlank() -> rawTitle
                    rawUrl.isNotBlank() -> rawUrl
                    rawUsername.isNotBlank() -> rawUsername
                    else -> "بدون عنوان"
                }

                val entry = PasswordEntry(
                    id = UUID.randomUUID().toString(),
                    groupId = targetGroupId,
                    title = title,
                    username = rawUsername,
                    password = rawPassword,
                    website = rawUrl,
                    notes = rawNote,
                    customFieldsJson = "[]",
                    isFavorite = false,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                ).encrypted()

                entries.add(entry)
            } catch (t: Throwable) {
                // Rule 15.6: Fail loud on cryptographic failure
                if (t is SecurityException || t is IllegalStateException && t.message?.contains("Android KeyStore") == true) {
                    throw t
                }
                skippedCount++
            }
        }

        return ChromeCsvParseResult(entries, skippedCount)
    }

    /**
     * Writes password entries to a CSV format matching Google Chrome Passwords export.
     * Header: name,url,username,password
     * Each entry is decrypted via entry.decrypted() and properly quoted if necessary.
     */
    fun writeChromeCsv(writer: Writer, entries: List<PasswordEntry>) {
        writer.write("name,url,username,password\n")
        for (entry in entries) {
            val dec = entry.decrypted()
            val name = escapeCsv(dec.title)
            val url = escapeCsv(dec.website)
            val user = escapeCsv(dec.username)
            val pass = escapeCsv(dec.password)
            writer.write("$name,$url,$user,$pass\n")
        }
        writer.flush()
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        return if (needsQuotes) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
