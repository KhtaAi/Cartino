package com.example

import com.example.data.model.BankCard
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.data.model.PasswordEntry
import com.example.data.model.PasswordGroup
import com.example.util.BackupPayload
import com.example.util.SecurityManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class BackupPayloadTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun backupPayload_roundTripWithPasswordsEncryptionAndDecryption() {
        val password = "BackupSecretPassword987!".toCharArray()

        val sampleGroup = PasswordGroup(
            id = "grp-test-1",
            name = "حساب‌های اداری",
            parentGroupId = null,
            createdAt = 1700000000000L
        )

        val sampleEntry = PasswordEntry(
            id = "entry-test-1",
            groupId = "grp-test-1",
            title = "سامانه اداری",
            username = "user_admin",
            password = "SecretPassword!@#",
            website = "https://example.com/portal",
            notes = "یادداشت امنیتی برای تست بک‌آپ",
            isFavorite = true
        )

        val sampleCard = BankCard(
            id = "card-test-1",
            cardNumber = "6037991122334455",
            bankName = "بانک ملی ایران",
            bankCode = "017",
            cardHolderName = "کاربر تست",
            iban = "IR123456789012345678901234",
            expiryYear = "06",
            expiryMonth = "10",
            cvv2 = "123",
            colorStartHex = "#1E3A8A",
            colorEndHex = "#3B82F6"
        )

        val sampleDoc = IdentityDocument(
            id = "doc-test-1",
            title = "کارت ملی هوشمند",
            docType = DocumentType.NATIONAL_CARD,
            nationalCode = "0012345678"
        )

        val originalPayload = BackupPayload(
            version = 2,
            totpSecret = "JBSWY3DPEHPK3PXP",
            totpRequired = true,
            cards = listOf(sampleCard),
            documents = listOf(sampleDoc),
            passwordGroups = listOf(sampleGroup),
            passwordEntries = listOf(sampleEntry)
        )

        // Serialize to JSON
        val adapter = moshi.adapter(BackupPayload::class.java)
        val originalJson = adapter.toJson(originalPayload)

        // Encrypt with SecurityManager (AES-256 Argon2id)
        val encryptedBase64 = SecurityManager.encryptData(originalJson, password)
        assertTrue(encryptedBase64.isNotBlank())

        // Decrypt with SecurityManager
        val decryptedJson = SecurityManager.decryptData(encryptedBase64, password)
        assertEquals(originalJson, decryptedJson)

        // Deserialize back to BackupPayload
        val restoredPayload = adapter.fromJson(decryptedJson)
        assertNotNull(restoredPayload)
        assertEquals(2, restoredPayload!!.version)
        assertEquals("JBSWY3DPEHPK3PXP", restoredPayload.totpSecret)
        assertTrue(restoredPayload.totpRequired)
        assertEquals(1, restoredPayload.cards.size)
        assertEquals("6037991122334455", restoredPayload.cards[0].cardNumber)
        assertEquals(1, restoredPayload.documents.size)
        assertEquals("کارت ملی هوشمند", restoredPayload.documents[0].title)
        assertEquals(1, restoredPayload.passwordGroups.size)
        assertEquals("حساب‌های اداری", restoredPayload.passwordGroups[0].name)
        assertEquals("grp-test-1", restoredPayload.passwordGroups[0].id)
        assertEquals(1, restoredPayload.passwordEntries.size)
        assertEquals("سامانه اداری", restoredPayload.passwordEntries[0].title)
        assertEquals("SecretPassword!@#", restoredPayload.passwordEntries[0].password)
        assertEquals("grp-test-1", restoredPayload.passwordEntries[0].groupId)
    }

    @Test
    fun legacyBackupV1_deserializesGracefullyWithEmptyPasswords() {
        // Sample JSON from legacy v1 backup without passwordGroups and passwordEntries
        val legacyJson = """
            {
              "version": 1,
              "totpSecret": "JBSWY3DPEHPK3PXP",
              "timestamp": 1720000000000,
              "totpRequired": false,
              "cards": [
                {
                  "id": "c1",
                  "cardNumber": "6104337788990011",
                  "bankName": "بانک ملت",
                  "bankCode": "012",
                  "cardHolderName": "علی رضایی",
                  "iban": "IR987654321098765432109876",
                  "expiryYear": "05",
                  "expiryMonth": "08",
                  "cvv2": "456",
                  "colorStartHex": "#E11D48",
                  "colorEndHex": "#BE123C"
                }
              ],
              "documents": [
                {
                  "id": "d1",
                  "title": "گواهینامه",
                  "docType": "DRIVING_LICENSE",
                  "nationalCode": "1234567890"
                }
              ]
            }
        """.trimIndent()

        val adapter = moshi.adapter(BackupPayload::class.java)
        val parsed = adapter.fromJson(legacyJson)

        assertNotNull(parsed)
        assertEquals(1, parsed!!.version)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.totpSecret)
        assertFalse(parsed.totpRequired)
        assertEquals(1, parsed.cards.size)
        assertEquals("بانک ملت", parsed.cards[0].bankName)
        assertEquals(1, parsed.documents.size)
        assertEquals("گواهینامه", parsed.documents[0].title)
        // Verify backwards compatibility: missing password fields default to empty lists
        assertNotNull(parsed.passwordGroups)
        assertTrue(parsed.passwordGroups.isEmpty())
        assertNotNull(parsed.passwordEntries)
        assertTrue(parsed.passwordEntries.isEmpty())
    }
}
