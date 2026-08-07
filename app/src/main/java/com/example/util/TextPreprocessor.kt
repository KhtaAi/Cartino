package com.example.util

import java.util.regex.Pattern

data class ParsedCardData(
    val cardNumber: String? = null,
    val iban: String? = null,
    val expiryYear: String? = null,
    val expiryMonth: String? = null,
    val cvv2: String? = null,
    val rawText: String = ""
)

object TextPreprocessor {

    /**
     * Converts all Persian (۰-۹) and Arabic (٠-٩) digits in a string to English digits (0-9).
     */
    fun convertPersianArabicDigitsToEnglish(input: String): String {
        val builder = StringBuilder()
        for (ch in input) {
            when (ch) {
                '۰', '٠' -> builder.append('0')
                '۱', '١' -> builder.append('1')
                '۲', '٢' -> builder.append('2')
                '۳', '٣' -> builder.append('3')
                '۴', '٤' -> builder.append('4')
                '۵', '٥' -> builder.append('5')
                '۶', '٦' -> builder.append('6')
                '۷', '٧' -> builder.append('7')
                '۸', '٨' -> builder.append('8')
                '۹', '٩' -> builder.append('9')
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Fixes common OCR character confusions in numeric or IBAN contexts.
     * E.g., 'O'/'o' -> '0', 'I'/'l'/'|'/'!' -> '1', 'S'/'s' -> '5', 'B' -> '8'.
     */
    private fun sanitizeOcrNumericText(input: String): String {
        val converted = convertPersianArabicDigitsToEnglish(input)
        return converted
            .replace('O', '0')
            .replace('o', '0')
            .replace('Q', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('|', '1')
            .replace('!', '1')
            .replace('S', '5')
            .replace('s', '5')
            .replace('B', '8')
    }

    /**
     * Applies Regex logic and Luhn/MOD-97 algorithms to extract card number, IBAN, expiry date, and CVV2.
     */
    fun extractCardDataFromText(rawText: String): ParsedCardData {
        val normalizedText = convertPersianArabicDigitsToEnglish(rawText)

        var cardNumber: String? = null
        var iban: String? = null
        var expiryYear: String? = null
        var expiryMonth: String? = null
        var cvv2: String? = null

        // 1. Extract Card Number: 16 digits pattern (optionally space, dash, or dot separated)
        val cardRegex = Pattern.compile("(?:\\b|(?<=\\D))(\\d{4}[-\\s\\._]?\\d{4}[-\\s\\._]?\\d{4}[-\\s\\._]?\\d{4})(?:\\b|(?=\\D))")
        val cardMatcher = cardRegex.matcher(normalizedText)
        while (cardMatcher.find()) {
            val candidate = cardMatcher.group(1)?.replace("-", "")?.replace(" ", "")?.replace(".", "")?.replace("_", "") ?: ""
            if (candidate.length == 16 && IranianBankHelper.validateLuhn(candidate)) {
                cardNumber = candidate
                break
            }
        }

        // Fallback 1b: Try sanitizing OCR errors on contiguous digit-like sequences
        if (cardNumber == null) {
            val sanitized = sanitizeOcrNumericText(rawText)
            val digitsOnly = sanitized.replace(Regex("[^0-9]"), "")
            if (digitsOnly.length >= 16) {
                for (i in 0..(digitsOnly.length - 16)) {
                    val sub = digitsOnly.substring(i, i + 16)
                    if (IranianBankHelper.validateLuhn(sub)) {
                        cardNumber = sub
                        break
                    }
                }
            }
        }

        // 2. Extract IBAN (شماره شبا): Starts with IR (or OCR mistranslated 1R, lR, |R, !R) followed by 24 digits
        // Or numbers following "شبا" or "شماره شبا"
        val ibanText = sanitizeOcrNumericTextForIban(normalizedText)
        
        // Pattern A: IR/1R/lR followed by 24 digits
        val ibanRegex = Pattern.compile("(?i)\\b(?:IR|1R|lR|\\|R|!R)[-\\s]?(\\d{2})[-\\s]?(\\d{4})[-\\s]?(\\d{4})[-\\s]?(\\d{4})[-\\s]?(\\d{4})[-\\s]?(\\d{4})[-\\s]?(\\d{2})\\b")
        val ibanMatcher = ibanRegex.matcher(ibanText)
        if (ibanMatcher.find()) {
            val digitsGroup = (1..7).mapNotNull { ibanMatcher.group(it) }.joinToString("")
            val fullCandidate = "IR$digitsGroup"
            if (fullCandidate.length == 26 && IranianBankHelper.validateIranianIban(fullCandidate)) {
                iban = fullCandidate
            }
        }

        // Pattern B: Persian Keyword "شبا" or "شماره شبا" followed by 24 to 26 digits/characters
        if (iban == null) {
            val keywordsIbanRegex = Pattern.compile("(?:شبا|شماره\\s*شبا|IBAN|IBA1N)\\s*[:=]?\\s*([IR\\d\\s\\-\\.]{24,32})", Pattern.CASE_INSENSITIVE)
            val kwMatcher = keywordsIbanRegex.matcher(normalizedText)
            if (kwMatcher.find()) {
                val rawMatch = kwMatcher.group(1) ?: ""
                val cleanDigits = sanitizeOcrNumericText(rawMatch).replace(Regex("[^0-9]"), "")
                if (cleanDigits.length == 24) {
                    val candidate = "IR$cleanDigits"
                    if (IranianBankHelper.validateIranianIban(candidate)) {
                        iban = candidate
                    }
                } else if (cleanDigits.length == 26 && rawMatch.uppercase().contains("IR")) {
                    val candidate = "IR${cleanDigits.takeLast(24)}"
                    if (IranianBankHelper.validateIranianIban(candidate)) {
                        iban = candidate
                    }
                }
            }
        }

        // Pattern C: Any 24 contiguous digits anywhere in text that form a valid Iranian IBAN when prefixed with IR
        if (iban == null) {
            val sanitizedIbanText = sanitizeOcrNumericText(normalizedText)
            val allDigits = sanitizedIbanText.replace(Regex("[^0-9]"), "")
            if (allDigits.length >= 24) {
                for (i in 0..(allDigits.length - 24)) {
                    val candidateDigits = allDigits.substring(i, i + 24)
                    // Skip if these 24 digits are just part of the 16 digit card number
                    if (cardNumber != null && candidateDigits.contains(cardNumber)) continue

                    val fullIban = "IR$candidateDigits"
                    if (IranianBankHelper.validateIranianIban(fullIban)) {
                        iban = fullIban
                        break
                    }
                }
            }
        }

        // 3. Extract Expiry Date: YY/MM or YYYY/MM with Jalali calendar (1300/1400 range or 2-digit years 00..30)
        val expiryRegex = Pattern.compile("(?:انقضا|انقضاء|تاریخ|EXP|EXPIRY)?\\s*[:=]?\\s*\\b((?:13|14)?\\d{2})[\\/\\.\\-](0[1-9]|1[0-2])\\b", Pattern.CASE_INSENSITIVE)
        val expiryMatcher = expiryRegex.matcher(normalizedText)
        if (expiryMatcher.find()) {
            expiryYear = expiryMatcher.group(1)
            expiryMonth = expiryMatcher.group(2)
        } else {
            // Alternative: MM/YY format sometimes found on cards
            val altExpiryRegex = Pattern.compile("\\b(0[1-9]|1[0-2])[\\/\\.\\-]((?:13|14)?\\d{2})\\b")
            val altMatcher = altExpiryRegex.matcher(normalizedText)
            if (altMatcher.find()) {
                expiryMonth = altMatcher.group(1)
                expiryYear = altMatcher.group(2)
            }
        }

        // Clean up 4-digit Jalali year to 2-digit if needed (e.g. 1403 -> 03)
        if (expiryYear != null && expiryYear.length == 4 && expiryYear.startsWith("14")) {
            expiryYear = expiryYear.substring(2)
        }

        // 4. Extract CVV2: 3 or 4 digits labeled with CVV2, CVV, C.V.V, کد امنیتی, سی وی وی, etc.
        val cvv2LabeledRegex = Pattern.compile("(?:CVV2?|C\\.V\\.V\\.?2?|کد\\s*امنیتی|سی\\s*وی\\s*وی|رمز\\s*دوم|CV2?|کد\\s*2)\\s*[:=]?\\s*(\\d{3,4})\\b", Pattern.CASE_INSENSITIVE)
        val cvv2Matcher = cvv2LabeledRegex.matcher(normalizedText)
        if (cvv2Matcher.find()) {
            cvv2 = cvv2Matcher.group(1)
        } else {
            // Fallback: Standalone 3 or 4 digits that are NOT part of expiry date, card number, or year
            val standaloneDigitRegex = Pattern.compile("\\b(\\d{3,4})\\b")
            val digitMatcher = standaloneDigitRegex.matcher(normalizedText)
            val cardDigits = cardNumber ?: ""
            while (digitMatcher.find()) {
                val candidate = digitMatcher.group(1) ?: ""
                val isYear = candidate == expiryYear || candidate == "1402" || candidate == "1403" || candidate == "1404" || candidate == "1405" || candidate == "1406"
                val isMonth = candidate == expiryMonth || candidate == "0$expiryMonth"
                val isCardPart = cardDigits.contains(candidate)
                val isBin = cardDigits.take(6).startsWith(candidate)

                if (!isYear && !isMonth && !isCardPart && !isBin) {
                    cvv2 = candidate
                    break
                }
            }
        }

        return ParsedCardData(
            cardNumber = cardNumber,
            iban = iban,
            expiryYear = expiryYear,
            expiryMonth = expiryMonth,
            cvv2 = cvv2,
            rawText = rawText
        )
    }

    private fun sanitizeOcrNumericTextForIban(input: String): String {
        return input
            .replace("1R", "IR")
            .replace("lR", "IR")
            .replace("|R", "IR")
            .replace("!R", "IR")
            .replace("iR", "IR")
    }
}

