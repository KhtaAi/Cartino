package com.example.util

data class IranianBank(
    val name: String,
    val binPrefixes: List<String>,
    val colorStartHex: String,
    val colorEndHex: String,
    val logoSymbol: String
)

object IranianBankHelper {

    val defaultBank = IranianBank(
        name = "بانک عضو شتاب",
        binPrefixes = emptyList(),
        colorStartHex = "#1E293B",
        colorEndHex = "#334155",
        logoSymbol = "CARD"
    )

    val banks = listOf(
        IranianBank(
            name = "بانک ملی ایران",
            binPrefixes = listOf("603799", "170019", "603769"),
            colorStartHex = "#1E3A8A",
            colorEndHex = "#0284C7",
            logoSymbol = "MELLI"
        ),
        IranianBank(
            name = "بانک ملت",
            binPrefixes = listOf("610433", "991975"),
            colorStartHex = "#991B1B",
            colorEndHex = "#DC2626",
            logoSymbol = "MELLAT"
        ),
        IranianBank(
            name = "بانک سامان",
            binPrefixes = listOf("621986"),
            colorStartHex = "#0284C7",
            colorEndHex = "#06B6D4",
            logoSymbol = "SAMAN"
        ),
        IranianBank(
            name = "بانک پارسیان",
            binPrefixes = listOf("622106", "627884", "639194"),
            colorStartHex = "#1E1B4B",
            colorEndHex = "#991B1B",
            logoSymbol = "PARSIAN"
        ),
        IranianBank(
            name = "بانک پاسارگاد",
            binPrefixes = listOf("502229", "639347"),
            colorStartHex = "#18181B",
            colorEndHex = "#EAB308",
            logoSymbol = "PASARGAD"
        ),
        IranianBank(
            name = "بانک سپه",
            binPrefixes = listOf("589210", "627381", "639599", "636949"),
            colorStartHex = "#B45309",
            colorEndHex = "#F59E0B",
            logoSymbol = "SEPAH"
        ),
        IranianBank(
            name = "بانک صادرات ایران",
            binPrefixes = listOf("603769", "019000"),
            colorStartHex = "#1E40AF",
            colorEndHex = "#3B82F6",
            logoSymbol = "SADERAT"
        ),
        IranianBank(
            name = "بانک تجارت",
            binPrefixes = listOf("627353", "585983"),
            colorStartHex = "#0F172A",
            colorEndHex = "#2563EB",
            logoSymbol = "TEJARAT"
        ),
        IranianBank(
            name = "بانک کشاورزی",
            binPrefixes = listOf("603770", "639217"),
            colorStartHex = "#15803D",
            colorEndHex = "#22C55E",
            logoSymbol = "KESHAVARZI"
        ),
        IranianBank(
            name = "بانک مسکن",
            binPrefixes = listOf("628023"),
            colorStartHex = "#C2410C",
            colorEndHex = "#F97316",
            logoSymbol = "MASKAN"
        ),
        IranianBank(
            name = "بانک اقتصاد نوین",
            binPrefixes = listOf("627412"),
            colorStartHex = "#6B21A8",
            colorEndHex = "#C084FC",
            logoSymbol = "EQTESHAD"
        ),
        IranianBank(
            name = "بانک سینا",
            binPrefixes = listOf("639346"),
            colorStartHex = "#3730A3",
            colorEndHex = "#818CF8",
            logoSymbol = "SINA"
        ),
        IranianBank(
            name = "بانک آینده",
            binPrefixes = listOf("636214"),
            colorStartHex = "#78350F",
            colorEndHex = "#D97706",
            logoSymbol = "AYANDEH"
        ),
        IranianBank(
            name = "بانک شهر",
            binPrefixes = listOf("502806", "504706"),
            colorStartHex = "#881337",
            colorEndHex = "#F43F5E",
            logoSymbol = "SHAHR"
        ),
        IranianBank(
            name = "بانک گردشگری",
            binPrefixes = listOf("505416"),
            colorStartHex = "#9F1239",
            colorEndHex = "#FB7185",
            logoSymbol = "GARDESHGARI"
        ),
        IranianBank(
            name = "بانک قرض‌الحسنه مهر ایران",
            binPrefixes = listOf("606373"),
            colorStartHex = "#065F46",
            colorEndHex = "#10B981",
            logoSymbol = "MEHR_IRAN"
        ),
        IranianBank(
            name = "بانک دی",
            binPrefixes = listOf("502938"),
            colorStartHex = "#581C87",
            colorEndHex = "#A855F7",
            logoSymbol = "DEY"
        ),
        IranianBank(
            name = "بانک کارآفرین",
            binPrefixes = listOf("627488", "502910"),
            colorStartHex = "#0F766E",
            colorEndHex = "#2DD4BF",
            logoSymbol = "KARAFARIN"
        ),
        IranianBank(
            name = "بانک سرمایه",
            binPrefixes = listOf("639607"),
            colorStartHex = "#0E7490",
            colorEndHex = "#22D3EE",
            logoSymbol = "SARMAYAH"
        ),
        IranianBank(
            name = "بانک قرض‌الحسنه رسالت",
            binPrefixes = listOf("504172"),
            colorStartHex = "#0369A1",
            colorEndHex = "#38BDF8",
            logoSymbol = "RESALAT"
        ),
        IranianBank(
            name = "بانک توسعه تعاون",
            binPrefixes = listOf("502908"),
            colorStartHex = "#166534",
            colorEndHex = "#34D399",
            logoSymbol = "TOSEE_TAAVON"
        ),
        IranianBank(
            name = "پست بانک ایران",
            binPrefixes = listOf("627760"),
            colorStartHex = "#1E293B",
            colorEndHex = "#10B981",
            logoSymbol = "POST_BANK"
        ),
        IranianBank(
            name = "بانک صنعت و معدن",
            binPrefixes = listOf("627961"),
            colorStartHex = "#475569",
            colorEndHex = "#94A3B8",
            logoSymbol = "SANAT_MADAN"
        ),
        IranianBank(
            name = "بانک ایران زمین",
            binPrefixes = listOf("505785"),
            colorStartHex = "#064E3B",
            colorEndHex = "#A3E635",
            logoSymbol = "IRAN_ZAMIN"
        )
    )

    fun getBankByCardNumber(cardNumber: String): IranianBank {
        val cleanNumber = TextPreprocessor.convertPersianArabicDigitsToEnglish(cardNumber).replace(" ", "").replace("-", "")
        if (cleanNumber.length >= 6) {
            val prefix = cleanNumber.substring(0, 6)
            return banks.find { bank -> bank.binPrefixes.contains(prefix) } ?: defaultBank
        }
        return defaultBank
    }

    /**
     * Luhn Algorithm validation for 16-digit card numbers.
     */
    fun validateLuhn(cardNumber: String): Boolean {
        val digits = TextPreprocessor.convertPersianArabicDigitsToEnglish(cardNumber).replace(" ", "").replace("-", "")
        if (digits.length != 16 || !digits.all { it.isDigit() }) return false

        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) {
                    n = (n % 10) + 1
                }
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /**
     * MOD-97 Checksum validation for Iranian IBANs (IR + 24 digits).
     */
    fun validateIranianIban(iban: String): Boolean {
        val cleanIban = TextPreprocessor.convertPersianArabicDigitsToEnglish(iban).uppercase().replace(" ", "").replace("-", "")
        if (!cleanIban.startsWith("IR") || cleanIban.length != 26) return false

        val rearranged = cleanIban.substring(4) + "1827" + cleanIban.substring(2, 4) // I=18, R=27
        var remainder = 0
        for (i in rearranged.indices) {
            val digit = rearranged[i] - '0'
            remainder = (remainder * 10 + digit) % 97
        }
        return remainder == 1
    }

    /**
     * Iranian National ID Code (کد ملی) Validation algorithm.
     */
    fun validateNationalCode(code: String): Boolean {
        val clean = TextPreprocessor.convertPersianArabicDigitsToEnglish(code).trim()
        if (clean.length != 10 || !clean.all { it.isDigit() }) return false
        if (clean.all { it == clean[0] }) return false

        val controlDigit = clean[9] - '0'
        var sum = 0
        for (i in 0..8) {
            sum += (clean[i] - '0') * (10 - i)
        }
        val remainder = sum % 11
        return if (remainder < 2) {
            controlDigit == remainder
        } else {
            controlDigit == (11 - remainder)
        }
    }

    /**
     * Postal Code Validation (10 digits).
     */
    fun validatePostalCode(code: String): Boolean {
        val clean = TextPreprocessor.convertPersianArabicDigitsToEnglish(code).trim()
        return clean.length == 10 && clean.all { it.isDigit() } && !clean.startsWith("0")
    }

    /**
     * Formats 16-digit card number into 4-digit grouped display: 6037 9918 1234 5678
     */
    fun formatCardNumberDisplay(cardNumber: String): String {
        val clean = TextPreprocessor.convertPersianArabicDigitsToEnglish(cardNumber).replace(" ", "").replace("-", "")
        if (clean.length != 16) return cardNumber
        return "${clean.substring(0, 4)}  ${clean.substring(4, 8)}  ${clean.substring(8, 12)}  ${clean.substring(12, 16)}"
    }

    /**
     * Formats IBAN for clear Iranian display: IR47 0120 0000 0000 4239 8659 52
     * Standard Iranian card format: IR + 2 check digits, then 5 groups of 4 digits, then final 2 digits.
     */
    fun formatIbanDisplay(iban: String): String {
        return formatIban(iban)
    }
}

fun formatIban(raw: String): String {
    val clean = raw.filter { it.isLetterOrDigit() }.uppercase()
    if (clean.length == 26 && clean.startsWith("IR")) {
        val d = clean.substring(2)
        if (d.length == 24 && d.all { it.isDigit() }) {
            return buildString {
                append("IR").append(d, 0, 2)
                var i = 2
                while (i + 4 <= 22) { append(' ').append(d, i, i + 4); i += 4 }
                append(' ').append(d, 22, 24)
            }
        }
    }
    return clean
}
