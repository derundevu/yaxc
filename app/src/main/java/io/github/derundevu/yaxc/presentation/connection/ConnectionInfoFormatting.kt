package io.github.derundevu.yaxc.presentation.connection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.derundevu.yaxc.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun formatDaysLeftShort(expireAtEpochSeconds: Long?): String? {
    val expireAt = expireAtEpochSeconds?.takeIf { it > 0L } ?: return null
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    if (expireAt <= now.epochSecond) {
        return stringResource(R.string.mainSubscriptionExpired)
    }
    val expireDate = Instant.ofEpochSecond(expireAt).atZone(zone).toLocalDate()
    val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), expireDate)
    return when {
        daysLeft <= 0L -> stringResource(R.string.mainSubscriptionToday)
        else -> stringResource(R.string.mainSubscriptionDaysShort, daysLeft)
    }
}

@Composable
fun formatDaysLeftFull(expireAtEpochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    if (expireAtEpochSeconds <= now.epochSecond) {
        return stringResource(R.string.mainSubscriptionExpired)
    }
    val expireDate = Instant.ofEpochSecond(expireAtEpochSeconds).atZone(zone).toLocalDate()
    val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), expireDate)
    return when {
        daysLeft <= 0L -> stringResource(R.string.mainSubscriptionToday)
        else -> stringResource(R.string.mainSubscriptionDaysFull, daysLeft)
    }
}

@Composable
fun formatAutoUpdateShort(hours: Int): String {
    return stringResource(R.string.mainSubscriptionAutoUpdateShort, hours)
}

@Composable
fun formatAutoUpdateFull(hours: Int): String {
    return stringResource(R.string.mainSubscriptionAutoUpdateFull, hours)
}

fun formatExpiryDateTime(expireAtEpochSeconds: Long): String {
    return Instant.ofEpochSecond(expireAtEpochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
        )
}

fun formatBytesCompact(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    val locale = Locale.getDefault()
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val formatted = when {
        value >= 100 || unitIndex == 0 -> value.toLong().toString()
        value >= 10 -> String.format(locale, "%.1f", value)
        else -> String.format(locale, "%.2f", value)
    }
    return "$formatted ${units[unitIndex]}"
}
