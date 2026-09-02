package com.example

import com.example.data.model.CustomPasswordField
import com.example.util.BitwardenFolder
import com.example.util.BitwardenIntermediateItem
import com.example.util.BitwardenPasswordParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitwardenPasswordParserTest {

    @Test
    fun bitwardenJson_parseLoginItems_correctlyExtractsAllFields() {
        val json = """
        {
          "encrypted": false,
          "folders": [
            {"id": "folder-1", "name": "شبکه‌های اجتماعی"},
            {"id": "folder-2", "name": "کارهای بانکی"}
          ],
          "items": [
            {
              "id": "item-1",
              "type": 1,
              "name": "توییتر من",
              "notes": "یادداشت تست",
              "favorite": true,
              "folderId": "folder-1",
              "login": {
                "uris": [{"match": null, "uri": "https://twitter.com"}],
                "username": "user123",
                "password": "secretPassword!",
                "totp": "JBSWY3DPEHPK3PXP"
              },
              "fields": [
                {"name": "Security Question", "value": "Tehran", "type": 0},
                {"name": "PIN", "value": "9876", "type": 1}
              ]
            },
            {
              "id": "item-2",
              "type": 2,
              "name": "Secure Note Item",
              "notes": "Should be skipped"
            }
          ]
        }
        """.trimIndent()

        val stream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        val result = BitwardenPasswordParser.parseBitwardenStream(stream)

        assertEquals(1, result.items.size)
        assertEquals(1, result.skippedCount) // Type 2 is skipped

        val item = result.items[0]
        assertEquals("folder-1", item.folderId)
        assertEquals("شبکه‌های اجتماعی", item.folderName)
        assertEquals("توییتر من", item.title)
        assertEquals("user123", item.username)
        assertEquals("secretPassword!", item.password)
        assertEquals("https://twitter.com", item.website)
        assertEquals("یادداشت تست", item.notes)
        assertTrue(item.isFavorite)

        // Fields should include totp + 2 custom fields
        assertEquals(3, item.customFields.size)
        assertTrue(item.customFields.any { it.label == "totp" && it.value == "JBSWY3DPEHPK3PXP" && it.isHidden })
        assertTrue(item.customFields.any { it.label == "Security Question" && it.value == "Tehran" && !it.isHidden })
        assertTrue(item.customFields.any { it.label == "PIN" && it.value == "9876" && it.isHidden })
    }

    @Test
    fun bitwardenJson_failsLoudOnEncryptedFlag() {
        val encryptedJson = """
        {
          "encrypted": true,
          "folders": [],
          "items": []
        }
        """.trimIndent()

        val stream = ByteArrayInputStream(encryptedJson.toByteArray(Charsets.UTF_8))
        try {
            BitwardenPasswordParser.parseBitwardenStream(stream)
            fail("Expected exception for encrypted Bitwarden JSON")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("فرمت رمزنگاری‌شدهٔ بیتواردن") == true)
        }
    }

    @Test
    fun bitwarden_failsLoudOnZipMagicBytes() {
        // PK\x03\x04
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        val stream = ByteArrayInputStream(zipBytes)
        try {
            BitwardenPasswordParser.parseBitwardenStream(stream)
            fail("Expected exception for ZIP bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ZIP") == true)
        }
    }

    @Test
    fun bitwardenCsv_roundTripExportAndImport() {
        val items = listOf(
            BitwardenIntermediateItem(
                folderName = "پروژه‌ها",
                title = "GitHub Enterprise",
                username = "dev_lead",
                password = "P@ssw0rd,with,comma\"and quotes",
                website = "https://github.com",
                notes = "یادداشت\nچندخطی",
                isFavorite = true,
                customFields = listOf(
                    CustomPasswordField("totp", "MYSECRETOTP", true),
                    CustomPasswordField("API Key", "xyz-12345", false)
                )
            )
        )

        val writer = StringWriter()
        BitwardenPasswordParser.writeBitwardenCsv(writer, items)
        val csvOutput = writer.toString()

        val stream = ByteArrayInputStream(csvOutput.toByteArray(Charsets.UTF_8))
        val parsedResult = BitwardenPasswordParser.parseBitwardenStream(stream)

        assertEquals(1, parsedResult.items.size)
        val parsed = parsedResult.items[0]
        assertEquals("پروژه‌ها", parsed.folderName)
        assertEquals("GitHub Enterprise", parsed.title)
        assertEquals("dev_lead", parsed.username)
        assertEquals("P@ssw0rd,with,comma\"and quotes", parsed.password)
        assertEquals("https://github.com", parsed.website)
        assertEquals("یادداشت\nچندخطی", parsed.notes)
        assertTrue(parsed.isFavorite)

        assertTrue(parsed.customFields.any { it.label == "totp" && it.value == "MYSECRETOTP" })
        assertTrue(parsed.customFields.any { it.label == "API Key" && it.value == "xyz-12345" })
    }

    @Test
    fun bitwardenJson_roundTripExportAndImport() {
        val folders = listOf(
            BitwardenFolder(id = "fid-10", name = "سرورها")
        )
        val items = listOf(
            BitwardenIntermediateItem(
                folderId = "fid-10",
                folderName = "سرورها",
                title = "Root SSH",
                username = "root",
                password = "MySecurePassword99#",
                website = "192.168.1.100",
                notes = "Production Server 1",
                isFavorite = true,
                customFields = listOf(
                    CustomPasswordField("totp", "AUTHCODE123", true),
                    CustomPasswordField("Port", "2222", false)
                )
            )
        )

        val writer = StringWriter()
        BitwardenPasswordParser.writeBitwardenJson(writer, folders, items)
        val jsonOutput = writer.toString()

        val stream = ByteArrayInputStream(jsonOutput.toByteArray(Charsets.UTF_8))
        val parsedResult = BitwardenPasswordParser.parseBitwardenStream(stream)

        assertEquals(1, parsedResult.items.size)
        val parsed = parsedResult.items[0]
        assertEquals("fid-10", parsed.folderId)
        assertEquals("سرورها", parsed.folderName)
        assertEquals("Root SSH", parsed.title)
        assertEquals("root", parsed.username)
        assertEquals("MySecurePassword99#", parsed.password)
        assertEquals("192.168.1.100", parsed.website)
        assertEquals("Production Server 1", parsed.notes)
        assertTrue(parsed.isFavorite)

        assertTrue(parsed.customFields.any { it.label == "totp" && it.value == "AUTHCODE123" })
        assertTrue(parsed.customFields.any { it.label == "Port" && it.value == "2222" })
    }
}
