package niel.kro.penik.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "в сети" / "был(а) в сети <when>", mirroring the web client's formatPresence(). */
fun formatPresence(online: Boolean, lastSeenUnixSeconds: Long): String {
    if (online) return "в сети"
    if (lastSeenUnixSeconds <= 0) return ""

    val seen = Calendar.getInstance().apply { timeInMillis = lastSeenUnixSeconds * 1000 }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeenUnixSeconds * 1000))

    val isToday = seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        seen.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    if (isToday) return "был(а) в сети сегодня в $time"

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = seen.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        seen.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "был(а) в сети вчера в $time"

    val date = SimpleDateFormat("d MMMM", Locale("ru")).format(Date(lastSeenUnixSeconds * 1000))
    return "был(а) в сети $date в $time"
}
