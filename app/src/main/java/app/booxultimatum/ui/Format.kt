package app.booxultimatum.ui

import android.content.Context
import android.text.format.DateFormat
import app.booxultimatum.R
import java.text.NumberFormat
import java.util.Date

object Format {
    private val whole: NumberFormat get() = NumberFormat.getIntegerInstance()

    fun mah(context: Context, value: Double) = context.getString(R.string.unit_mah, whole.format(value))

    fun percent(context: Context, fraction: Double) = context.getString(R.string.unit_percent, whole.format(fraction * 100))

    fun duration(context: Context, millis: Long): String {
        val minutes = millis / 60_000
        val days = minutes / (60 * 24)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> context.getString(R.string.duration_dh, days, hours)
            hours > 0 -> context.getString(R.string.duration_hm, hours, mins)
            else -> context.getString(R.string.duration_m, mins)
        }
    }

    fun clock(context: Context, epochMillis: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMillis))
}
