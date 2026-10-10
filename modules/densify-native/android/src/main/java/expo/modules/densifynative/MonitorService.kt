package expo.modules.densifynative

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {
  private var watcher: Job? = null
  private var floatJob: Job? = null
  private var floating: FloatingPanel? = null
  private var netCb: ConnectivityManager.NetworkCallback? = null
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
      ACTION_STOP -> { shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
    }
    startForeground(Notifs.ID_MONITOR, build("Densify is running", "Tap an action to change DPI."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    Hub.scope.launch {
      if (!Hub.adb.isConnected) Hub.adb.connect()
      Hub.engine.start()
    }
    watchWifi()
    startFloating()
    watcher?.cancel()
    watcher = Hub.scope.launch {
      Hub.engine.state.collect { s ->
        val c = Hub.engine.config()
        val title = when {
          s.activePkg != null -> "Playing · ${s.appliedDpi} DPI"
          c.automation -> "Densify is watching ${c.profiles.size} game${if (c.profiles.size == 1) "" else "s"}"
          else -> "Densify is ready"
        }
        val text = s.errTitle ?: "Default ${c.defaultDpi} DPI"
        getSystemService(NotificationManager::class.java).notify(Notifs.ID_MONITOR, build(title, text))
      }
    }
    return START_STICKY
  }

  /** Shows or hides the floating panel according to the user's setting and what is happening on screen. */
  private fun startFloating() {
    val panel = floating ?: FloatingPanel(this).also { floating = it }
    floatJob?.cancel()
    floatJob = Hub.scope.launch {
      while (isActive) {
        val c = Hub.engine.config()
        val s = Hub.engine.state.value
        val key = s.activePkg ?: "always"
        val forced = SystemClock.elapsedRealtime() < Hub.floatForceUntil
        val wanted = forced || c.floating == "always" || (c.floating == "gaming" && s.activePkg != null)
        val dismissed = panel.dismissedKey
        if (dismissed != null && dismissed != key) panel.dismissedKey = null
        if (wanted && Settings.canDrawOverlays(this@MonitorService) && (forced || panel.dismissedKey != key)) panel.show() else panel.hide()
        delay(1_500)
      }
    }
  }

  /** Reconnects Wireless Debugging by itself when Wi-Fi comes back, so DPI switching keeps working after a network change. */
  private fun watchWifi() {
    if (netCb != null) return
    val cm = getSystemService(ConnectivityManager::class.java) ?: return
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        if (!Hub.engine.config().autoReconnect) return
        Hub.scope.launch {
          delay(1_500)
          runCatching { Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1) }
          if (!Hub.adb.isConnected) Hub.adb.connect()
        }
      }
    }
    val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    runCatching { cm.registerNetworkCallback(req, cb); netCb = cb }
  }

  private fun shutdown() {
    Hub.engine.stop()
    watcher?.cancel()
    floatJob?.cancel()
    floating?.hide()
    netCb?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
    netCb = null
  }

  override fun onDestroy() {
    watcher?.cancel()
    floatJob?.cancel()
    floating?.hide()
    netCb?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
    netCb = null
    super.onDestroy()
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
