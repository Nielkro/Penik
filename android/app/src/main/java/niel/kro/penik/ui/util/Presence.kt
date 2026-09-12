package niel.kro.penik.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "в сети" / "был(а) в сети <when>", mirroring the web client's formatPresence(). */
fun formatPresence(online: Boolean, lastSeenUnixSeconds: Long): String {
    if (online) return "в сети"
    if (lastSeenUnixSeconds <= 0) return ""

    val seenMillis = lastSeenUnixSeconds * 1000
    val nowMillis = System.currentTimeMillis()
    // Within the last minute — show "just now" instead of a clock time.
    if (nowMillis - seenMillis < 60_000) return "был(а) только что"

    val seen = Calendar.getInstance().apply { timeInMillis = seenMillis }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(seenMillis))

    val isSameYear = seen.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val isToday = isSameYear && seen.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    if (isToday) return "был(а) в $time"

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = seen.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        seen.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "был(а) вчера в $time"

    val dayBeforeYesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -2) }
    val isDayBeforeYesterday = seen.get(Calendar.YEAR) == dayBeforeYesterday.get(Calendar.YEAR) &&
        seen.get(Calendar.DAY_OF_YEAR) == dayBeforeYesterday.get(Calendar.DAY_OF_YEAR)
    if (isDayBeforeYesterday) return "был(а) позавчера в $time"

    val ruLocale = Locale.forLanguageTag("ru")
    val date = if (isSameYear) {
        SimpleDateFormat("d MMM", ruLocale).format(Date(seenMillis))
    } else {
        SimpleDateFormat("d MMM yyyy", ruLocale).format(Date(seenMillis))
    }
    return "был(а) $date в $time"
}
