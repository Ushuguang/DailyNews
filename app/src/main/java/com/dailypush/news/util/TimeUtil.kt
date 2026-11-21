package com.dailypush.news.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtil {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.getDefault())

    /** 下一次推送的时刻（今天还没到就是今天，否则明天） */
    fun nextOccurrence(hour: Int, minute: Int): ZonedDateTime {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    fun formatDateTime(time: Long): String {
        if (time <= 0) return "—"
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), ZoneId.systemDefault())
            .format(DATE) + " " + TIME.format(
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), ZoneId.systemDefault())
            )
    }

    fun formatTime(time: Long): String {
        if (time <= 0) return "—"
        return TIME.format(
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), ZoneId.systemDefault())
        )
    }

    fun formatClock(hour: Int, minute: Int): String = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    /** 距今多久 */
    fun relative(millis: Long): String {
        if (millis <= 0) return "—"
        val diff = System.currentTimeMillis() - millis
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }

    /** 归到哪一天 */
    fun dayLabel(millis: Long, fetchedAt: Long): String {
        val zone = ZoneId.systemDefault()
        val time = if (millis > 0) millis else fetchedAt
        val date = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> date.format(DATE)
        }
    }
}
