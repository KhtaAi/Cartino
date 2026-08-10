package com.example.util

import android.content.Context
import com.example.data.model.BankCard
import com.example.data.model.IdentityDocument
import kotlin.math.abs

data class ExpiringItem(
    val id: String,
    val title: String,
    val detail: String,
    val expiryYear: Int,
    val expiryMonth: Int,
    val expiryDay: Int = 1
)

object ExpiryReminderManager {
    private const val PREFS_NAME = "cartino_expiry_prefs"
    private const val KEY_STATE_PREFIX = "state_"
    private const val KEY_SNOOZE_DATE_PREFIX = "snooze_date_"

    const val STATE_DISMISSED = "DISMISSED"
    const val STATE_SNOOZED = "SNOOZED"

    private fun getPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun dismissItems(context: Context, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val editor = getPrefs(context).edit()
        itemIds.forEach { id ->
            editor.putString("$KEY_STATE_PREFIX$id", STATE_DISMISSED)
            editor.remove("$KEY_SNOOZE_DATE_PREFIX$id")
        }
        editor.apply()
    }

    fun snoozeItems(context: Context, itemIds: List<String>, snoozeUntilJalali: String) {
        if (itemIds.isEmpty()) return
        val editor = getPrefs(context).edit()
        itemIds.forEach { id ->
            editor.putString("$KEY_STATE_PREFIX$id", STATE_SNOOZED)
            editor.putString("$KEY_SNOOZE_DATE_PREFIX$id", snoozeUntilJalali.trim())
        }
        editor.apply()
    }

    fun clearItemState(context: Context, itemId: String) {
        if (itemId.isBlank()) return
        getPrefs(context).edit()
            .remove("$KEY_STATE_PREFIX$itemId")
            .remove("$KEY_SNOOZE_DATE_PREFIX$itemId")
            .apply()
    }

    fun getDueExpiringItems(
        cards: List<BankCard>,
        documents: List<IdentityDocument>,
        context: Context
    ): List<ExpiringItem> {
        val currentJalali = JalaliCalendarHelper.getCurrentJalaliDate()
        val todayStr = currentJalali.toString()
        val prefs = getPrefs(context)

        val result = mutableListOf<ExpiringItem>()

        cards.forEach { card ->
            val remaining = JalaliCalendarHelper.monthsUntilCardExpiry(card.expiryYear, card.expiryMonth)
            if (remaining != null && remaining <= card.reminderMonthsBefore) {
                val itemId = "card_${card.id}"
                val state = prefs.getString("$KEY_STATE_PREFIX$itemId", null)
                val snoozeDate = prefs.getString("$KEY_SNOOZE_DATE_PREFIX$itemId", null)

                var isDue = true
                if (state == STATE_DISMISSED) {
                    isDue = false
                } else if (state == STATE_SNOOZED && !snoozeDate.isNullOrBlank()) {
                    if (snoozeDate > todayStr) {
                        isDue = false
                    }
                }

                if (isDue) {
                    val holder = card.cardHolderName.trim()
                    val last4 = if (card.cardNumber.length >= 4) card.cardNumber.takeLast(4) else card.cardNumber
                    val cardSub = if (holder.isNotBlank()) holder else last4
                    val cardTitle = "کارت ${card.bankName} ($cardSub)"
                    val detail = when {
                        remaining < 0 -> "${abs(remaining)} ماه پیش منقضی شده"
                        remaining == 0 -> "این ماه منقضی می‌شود"
                        else -> "$remaining ماه دیگر منقضی می‌شود"
                    }

                    var y = TextPreprocessor.convertPersianArabicDigitsToEnglish(card.expiryYear).trim().toIntOrNull() ?: currentJalali.year
                    if (y < 100) y += 1400
                    val m = TextPreprocessor.convertPersianArabicDigitsToEnglish(card.expiryMonth).trim().toIntOrNull() ?: currentJalali.month

                    result.add(
                        ExpiringItem(
                            id = itemId,
                            title = cardTitle,
                            detail = detail,
                            expiryYear = y,
                            expiryMonth = m,
                            expiryDay = 1
                        )
                    )
                }
            }
        }

        documents.forEach { doc ->
            if (doc.expiryDate.isNotBlank()) {
                val remaining = JalaliCalendarHelper.monthsUntilJalaliDate(doc.expiryDate)
                if (remaining != null && remaining <= doc.reminderMonthsBefore) {
                    val itemId = "doc_${doc.id}"
                    val state = prefs.getString("$KEY_STATE_PREFIX$itemId", null)
                    val snoozeDate = prefs.getString("$KEY_SNOOZE_DATE_PREFIX$itemId", null)

                    var isDue = true
                    if (state == STATE_DISMISSED) {
                        isDue = false
                    } else if (state == STATE_SNOOZED && !snoozeDate.isNullOrBlank()) {
                        if (snoozeDate > todayStr) {
                            isDue = false
                        }
                    }

                    if (isDue) {
                        val docTitle = "مدرک ${doc.title}"
                        val detail = when {
                            remaining < 0 -> "${abs(remaining)} ماه پیش منقضی شده"
                            remaining == 0 -> "این ماه منقضی می‌شود"
                            else -> "$remaining ماه دیگر منقضی می‌شود"
                        }

                        var expY = currentJalali.year
                        var expM = currentJalali.month
                        var expD = currentJalali.day
                        val parts = TextPreprocessor.convertPersianArabicDigitsToEnglish(doc.expiryDate).trim().split("-", "/")
                        if (parts.size >= 3) {
                            val py = parts[0].toIntOrNull()
                            val pm = parts[1].toIntOrNull()
                            val pd = parts[2].toIntOrNull()
                            if (py != null && pm != null && pd != null) {
                                expY = if (py < 100) py + 1400 else py
                                expM = pm
                                expD = pd
                            }
                        }

                        result.add(
                            ExpiringItem(
                                id = itemId,
                                title = docTitle,
                                detail = detail,
                                expiryYear = expY,
                                expiryMonth = expM,
                                expiryDay = expD
                            )
                        )
                    }
                }
            }
        }

        return result
    }
}
