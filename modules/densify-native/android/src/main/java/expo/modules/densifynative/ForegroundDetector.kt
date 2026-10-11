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

  /** The app currently in front, as of the last poll. */
  val foreground: String? get() = current

  val screenOn: Boolean get() = pm.isInteractive

  fun hasAccess(): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    val mode = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
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
