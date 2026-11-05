package expo.modules.densifynative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Lets other apps (Tasker, automation tools, adb shell) drive Densify.
 * Off by default. Every call must carry the token shown in Densify's settings.
 * Actions: SET_SW (int extra "sw", smallest width in dp), SET_DPI (int extra "dpi", raw density), RESTORE, TOGGLE_STRETCH.
 */
class ExternalReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    Hub.init(ctx)
    val p = Hub.prefs
    if (p.getString("externalApi", "0") != "1") return
    val token = p.getString("apiToken", "") ?: ""
    if (token.isEmpty() || intent.getStringExtra("token") != token) { Hub.log("External request rejected (bad token)"); return }
    val pending = goAsync()
    Hub.scope.launch {
      try {
        when (intent.action) {
          ACTION_SET_DPI -> {
            val dpi = intent.getIntExtra("dpi", 0)
            val r = Hub.engine.adjustDpi(dpi)
            Hub.log("External request: ${dpi} DPI ${if (r.isSuccess) "applied" else "failed"}")
          }
          ACTION_SET_SW -> {
            val sw = intent.getIntExtra("sw", 0)
            val r = Hub.engine.adjustSw(sw)
            Hub.log("External request: smallest width $sw ${if (r.isSuccess) "applied" else "failed"}")
          }
          ACTION_RESTORE -> { Hub.engine.restoreAll(); Hub.log("External request: restore") }
          ACTION_TOGGLE_STRETCH -> { Hub.engine.toggleStretch(); Hub.log("External request: toggle stretch") }
        }
      } finally { pending.finish() }
    }
  }

  companion object {
    const val ACTION_SET_DPI = "com.densify.app.action.SET_DPI"
    const val ACTION_SET_SW = "com.densify.app.action.SET_SW"
    const val ACTION_RESTORE = "com.densify.app.action.RESTORE"
    const val ACTION_TOGGLE_STRETCH = "com.densify.app.action.TOGGLE_STRETCH"
  }
}
