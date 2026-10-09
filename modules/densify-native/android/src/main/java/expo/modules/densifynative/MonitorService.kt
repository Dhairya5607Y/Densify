package expo.modules.densifynative

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MonitorService : Service() {
  private var watcher: Job? = null
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Hub.init(this)
    when (intent?.action) {
      ACTION_RESTORE -> { Hub.scope.launch { Hub.engine.restoreAll() }; return START_STICKY }
      ACTION_UP, ACTION_DOWN -> {
        val delta = if (intent.action == ACTION_UP) 10 else -10
        Hub.scope.launch {
          val cur = Hub.adb.readDensity().getOrNull()?.effective ?: return@launch
          Hub.adb.setDensity((cur + delta).coerceIn(72, 1000))
        }
        return START_STICKY
      }
      ACTION_STOP -> { Hub.engine.stop(); watcher?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
    }
    startForeground(Notifs.ID_MONITOR, build("Densify is watching your games", "Tap an action to change DPI."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    Hub.scope.launch {
      if (!Hub.adb.isConnected) Hub.adb.connect()
      Hub.engine.start()
    }
    watcher?.cancel()
    watcher = Hub.scope.launch {
      Hub.engine.state.collect { s ->
        val c = Hub.engine.config()
        val title = if (s.activePkg != null) "Playing · ${s.appliedDpi} DPI" else "Densify is watching ${c.profiles.size} game${if (c.profiles.size == 1) "" else "s"}"
        val text = s.errTitle ?: "Default ${c.defaultDpi} DPI"
        getSystemService(NotificationManager::class.java).notify(Notifs.ID_MONITOR, build(title, text))
      }
    }
    return START_STICKY
  }

  private fun build(title: String, text: String) = Notifs.builder(this).setContentTitle(title).setContentText(text).setOngoing(true)
    .addAction(Notifs.action(this, "−10", ACTION_DOWN, 11))
    .addAction(Notifs.action(this, "+10", ACTION_UP, 12))
    .addAction(Notifs.action(this, "Restore", ACTION_RESTORE, 13)).build()

  companion object {
    const val ACTION_RESTORE = "expo.modules.densifynative.RESTORE"
    const val ACTION_UP = "expo.modules.densifynative.UP"
    const val ACTION_DOWN = "expo.modules.densifynative.DOWN"
    const val ACTION_STOP = "expo.modules.densifynative.STOP"
    fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, MonitorService::class.java)) }
    fun stop(ctx: Context) { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP)) }
  }
}
