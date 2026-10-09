package expo.modules.densifynative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    Hub.init(ctx)
    if (Hub.prefs.getBoolean("monitoring", false) && Hub.prefs.getBoolean("restoreOnBoot", true)) {
      // Re-enable Wireless Debugging when WRITE_SECURE_SETTINGS was granted during setup.
      runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1) }
      MonitorService.start(ctx)
    }
  }
}
