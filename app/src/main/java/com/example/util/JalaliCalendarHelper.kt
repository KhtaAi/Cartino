package com.example.util

import java.util.Calendar
import java.util.GregorianCalendar

data class JalaliDate(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String = String.format("%04d/%02d/%02d", year, month, day)
}

object JalaliCalendarHelper {

    val jalaliMonthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند"
    )

    fun getCurrentJalaliDate(): JalaliDate {
        val cal = Calendar.getInstance()
        val gYear = cal.get(Calendar.YEAR)
        val gMonth = cal.get(Calendar.MONTH) + 1
        val gDay = cal.get(Calendar.DAY_OF_MONTH)
        return gregorianToJalali(gYear, gMonth, gDay)
    }

    fun gregorianToJalali(gYear: Int, gMonth: Int, gDay: Int): JalaliDate {
        val gDaysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gy = gYear - 1600
        var gm = gMonth - 1
        var gd = gDay - 1

        var gDayNo = 365 * gy + (gy + 3) / 4 - (gy + 99) / 100 + (gy + 399) / 400
        for (i in 0 until gm) {
            gDayNo += gDaysInMonth[i + 1]
        }
        if (gm > 1 && ((gYear % 4 == 0 && gYear % 100 != 0) || (gYear % 400 == 0))) {
            gDayNo++
        }
        gDayNo += gd

        var jDayNo = gDayNo - 79
        val jNp = jDayNo / 12053
        jDayNo %= 12053

        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461

        if (jDayNo >= 366) {
            jy += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }

        val jm: Int
        val jd: Int
        if (jDayNo < 186) {
            jm = 1 + jDayNo / 31
            jd = 1 + (jDayNo % 31)
        } else {
            jm = 7 + (jDayNo - 186) / 30
            jd = 1 + ((jDayNo - 186) % 30)
        }
        return JalaliDate(jy, jm, jd)
    }

    fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
        val jy1 = jy - 979
        val jm1 = jm - 1
        val jd1 = jd - 1

        var jDayNo = 365 * jy1 + (jy1 / 33) * 8 + ((jy1 % 33) + 3) / 4
        for (i in 0 until jm1) {
            jDayNo += if (i < 6) 31 else 30
        }
        jDayNo += jd1

        var gDayNo = jDayNo + 79

        var gy = 1600 + 400 * (gDayNo / 146097)
        gDayNo %= 146097

        var leap = true
        if (gDayNo >= 36525) {
            gDayNo--
            gy += 100 * (gDayNo / 36524)
            gDayNo %= 36524
            if (gDayNo >= 365) {
                gDayNo++
            } else {
                leap = false
            }
        }

        gy += 4 * (gDayNo / 1461)
        gDayNo %= 1461

        if (gDayNo >= 366) {
            leap = false
            gDayNo--
            gy += gDayNo / 365
            gDayNo %= 365
        }

        val gDaysInMonth = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        var gd = 0
        var i = 1
        while (i <= 12) {
            if (gDayNo < gDaysInMonth[i]) {
                gm = i
                gd = gDayNo + 1
                break
            }
            gDayNo -= gDaysInMonth[i]
            i++
        }
        return Triple(gy, gm, gd)
    }

    fun jalaliMonthLength(jy: Int, jm: Int): Int {
        val nextJy = if (jm == 12) jy + 1 else jy
        val nextJm = if (jm == 12) 1 else jm + 1

        val (gY1, gM1, gD1) = jalaliToGregorian(jy, jm, 1)
        val (gY2, gM2, gD2) = jalaliToGregorian(nextJy, nextJm, 1)

        val cal1 = GregorianCalendar(gY1, gM1 - 1, gD1)
        val cal2 = GregorianCalendar(gY2, gM2 - 1, gD2)

        val diffInMillis = cal2.timeInMillis - cal1.timeInMillis
        return (diffInMillis / (1000 * 60 * 60 * 24)).toInt()
    }

    fun dayOfWeekOfFirstOfJalaliMonth(jy: Int, jm: Int): Int {
        val (gY, gM, gD) = jalaliToGregorian(jy, jm, 1)
        val cal = GregorianCalendar(gY, gM - 1, gD)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        return dayOfWeek % 7
    }

    fun monthsUntilCardExpiry(expiryYearStr: String, expiryMonthStr: String): Int? {
        val cleanYear = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryYearStr).trim()
        val cleanMonth = TextPreprocessor.convertPersianArabicDigitsToEnglish(expiryMonthStr).trim()

        val month = cleanMonth.toIntOrNull() ?: return null
        if (month !in 1..12) return null

        var year = cleanYear.toIntOrNull() ?: return null
        if (year < 100) {
            year += 1400
        }

        val currentJalali = getCurrentJalaliDate()
        val cardTotalMonths = year * 12 + month
        val currentTotalMonths = currentJalali.year * 12 + currentJalali.month

        return cardTotalMonths - currentTotalMonths
    }

    fun monthsUntilJalaliDate(dateStr: String): Int? {
        if (dateStr.isBlank()) return null
        val cleanDate = TextPreprocessor.convertPersianArabicDigitsToEnglish(dateStr).trim()
        val parts = cleanDate.split("-", "/")
        if (parts.size < 3) return null

        var year = parts[0].trim().toIntOrNull() ?: return null
        val month = parts[1].trim().toIntOrNull() ?: return null
        val day = parts[2].trim().toIntOrNull() ?: return null

        if (month !in 1..12 || day !in 1..31) return null
        if (year < 100) year += 1400

        val currentJalali = getCurrentJalaliDate()
        var diffMonths = (year * 12 + month) - (currentJalali.year * 12 + currentJalali.month)
        if (day < currentJalali.day) {
            diffMonths -= 1
        }
        return diffMonths
    }

    fun getCardExpiryStatus(expiryYearStr: String, expiryMonthStr: String): Int? {
        val diffMonths = monthsUntilCardExpiry(expiryYearStr, expiryMonthStr) ?: return null
        return if (diffMonths in 0..2) diffMonths else null
    }
}
