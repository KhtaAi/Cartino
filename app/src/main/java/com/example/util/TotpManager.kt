package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TotpManager {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generateSecret(): String {
        val randomBytes = ByteArray(20)
        SecureRandom().nextBytes(randomBytes)
        return base32Encode(randomBytes)
    }

    fun base32Encode(bytes: ByteArray): String {
        var buffer = 0
        var bitsLeft = 0
        val result = StringBuilder()
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = (buffer shr bitsLeft) and 0x1F
                result.append(BASE32_ALPHABET[index])
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(BASE32_ALPHABET[index])
        }
        return result.toString()
    }

    fun base32Decode(base32: String): ByteArray {
        val clean = base32.uppercase(Locale.US)
            .replace("=", "")
            .replace(" ", "")
            .replace("-", "")
        var buffer = 0
        var bitsLeft = 0
        val result = ByteArrayOutputStream()
        for (char in clean) {
            val value = BASE32_ALPHABET.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                result.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return result.toByteArray()
    }

    fun generateTotp(secretBase32: String, timestampSeconds: Long = System.currentTimeMillis() / 1000): String {
        return try {
            val keyBytes = base32Decode(secretBase32)
            if (keyBytes.isEmpty()) return ""
            val timeStep = timestampSeconds / 30
            val timeBytes = ByteBuffer.allocate(8).putLong(timeStep).array()

            val mac = Mac.getInstance("HmacSHA1")
            val keySpec = SecretKeySpec(keyBytes, "HmacSHA1")
            mac.init(keySpec)
            val hmac = mac.doFinal(timeBytes)

            val offset = hmac[hmac.size - 1].toInt() and 0x0F
            val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
                    ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                    (hmac[offset + 3].toInt() and 0xFF)
            val otp = binary % 1000000
            String.format(Locale.US, "%06d", otp)
        } catch (e: Exception) {
            ""
        }
    }

    fun verifyTotp(secretBase32: String, code: String, window: Int = 1): Boolean {
        val cleanCode = code.trim().filter { it.isDigit() }
        if (cleanCode.length != 6) return false
        val currentSeconds = System.currentTimeMillis() / 1000
        for (i in -window..window) {
            val targetSeconds = currentSeconds + (i * 30)
            val expectedCode = generateTotp(secretBase32, targetSeconds)
            if (expectedCode.isNotEmpty() && expectedCode == cleanCode) {
                return true
            }
        }
        return false
    }

    fun getQrCodeUri(secret: String, label: String = "Cartino:Backup", issuer: String = "Cartino"): String {
        return "otpauth://totp/$label?secret=$secret&issuer=$issuer&digits=6&period=30"
    }

    fun generateQrCodeBitmap(contents: String, width: Int = 512, height: Int = 512): Bitmap {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(contents, BarcodeFormat.QR_CODE, width, height)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
