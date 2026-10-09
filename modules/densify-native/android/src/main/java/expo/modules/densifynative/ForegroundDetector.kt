package expo.modules.densifynative

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process

/** Event-based foreground detection, polled by [Engine] at a relaxed interval. */
class ForegroundDetector(private val ctx: Context) {
  private val usm = ctx.getSystemService(UsageStatsManager::class.java)
  private val pm = ctx.getSystemService(PowerManager::class.java)
  private var lastQuery = System.currentTimeMillis() - 60_000
  private var current: String? = null

  val screenOn: Boolean get() = pm.isInteractive

  fun hasAccess(): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
  }

  fun poll(): String? {
    if (!screenOn) { current = null; lastQuery = System.currentTimeMillis(); return null }
    val now = System.currentTimeMillis()
    val events = runCatching { usm.queryEvents(lastQuery, now) }.getOrNull() ?: return current
    lastQuery = now
    val e = UsageEvents.Event()
    while (events.hasNextEvent()) {
      events.getNextEvent(e)
      when (e.eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> current = e.packageName
        UsageEvents.Event.ACTIVITY_PAUSED -> if (current == e.packageName) current = null
      }
    }
    return current
  }
}
