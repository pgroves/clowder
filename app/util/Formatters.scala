package util

import java.text.SimpleDateFormat
import java.util.Date

/**
 * Formatters
 */
object Formatters {

  val ellipsis = "..."

  def ellipsize(s: String, max: Int): String = {
    if (s.length <= max) return s
    s.substring(0, max - ellipsis.length) + ellipsis
  }

  /**
   * Returns a string that describes the time elapsed since timestamp
   * @param timestamp the timestamp to be calculated
   * @return a string of time elapsed
   */
  def humanReadableTimeSince(timestamp: Date): String = {
    var elapsed = (new Date().getTime() - timestamp.getTime()) / 1000
    if (elapsed < 1) {
      "never"
    } else if (elapsed < 60) {
      "less than a minute"
    } else if (elapsed < 120) {
      "1 minute"
    } else if (elapsed < 3600) {
      Math.floor(elapsed / 60).toInt + " minutes"
    } else if (elapsed < 86400) {
      Math.floor(elapsed / 3600).toInt + " hours"
    } else if (elapsed < 432000) {
      Math.floor(elapsed / 86400).toInt + " days"
    } else {
      var df = new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm")
      df.format(timestamp)
    }
  }

  def iso8601(date: Date): String = {
    val formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX")
    formatter.format(date)
  }

  def iso8601(date: String): Date = {
    val formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX")
    formatter.parse(date)
  }
}
