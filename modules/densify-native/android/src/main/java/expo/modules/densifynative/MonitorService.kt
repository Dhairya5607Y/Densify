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
import android.os.Build
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
  private var linkJob: Job? = null
  private var floating: FloatingPanel? = null
  private var hud: PerfHud? = null
  private var netCb: ConnectivityManager.NetworkCallback? = null
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Hub.init(this)
    when (intent?.action) {
      ACTION_RESTORE -> { Hub.scope.launch { Hub.engine.restoreAll() }; return START_STICKY }
      ACTION_UP, ACTION_DOWN -> {
        val delta = if (intent.action == ACTION_UP) 10 else -10
        Hub.scope.launch {
          // Smallest width, as in Developer options: +10 gives more room (smaller UI), -10 gives bigger UI.
          val cur = Hub.adb.readDisplay().getOrNull()?.sw ?: Hub.engine.state.value.appliedSw ?: return@launch
          Hub.engine.adjustSw(cur + delta)
        }
        return START_STICKY
      }
      ACTION_STOP -> { shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
    }
    val first = build("Densify is running", "Tap an action to change smallest width.")
    if (Build.VERSION.SDK_INT >= 34) startForeground(Notifs.ID_MONITOR, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(Notifs.ID_MONITOR, first)
    Hub.scope.launch {
      if (!Hub.adb.isConnected) Hub.adb.connect(quiet = true)
      // Learn the phone's own density as early as possible: it is the safe value to restore to.
      if (Hub.adb.isConnected) Hub.engine.syncFromDevice()
      Hub.engine.start()
    }
    watchWifi()
    startLinkKeeper()
    Perf.start()
    startFloating()
    watcher?.cancel()
    watcher = Hub.scope.launch {
      Hub.engine.state.collect { s ->
        val c = Hub.engine.config()
        val title = when {
          s.activePkg != null -> "Playing · SW ${s.appliedSw ?: "?"}"
          c.automation -> "Densify is watching ${c.profiles.size} game${if (c.profiles.size == 1) "" else "s"}"
          else -> "Densify is ready"
        }
        val text = s.errTitle ?: if (c.defaultSw > 0) "Default smallest width ${c.defaultSw}" else "Default: phone's own width"
        getSystemService(NotificationManager::class.java).notify(Notifs.ID_MONITOR, build(title, text))
      }
    }
    return START_STICKY
  }

  /** Keeps the adb link alive for good: after the one-time setup nothing should ever ask the user again. */
  private fun startLinkKeeper() {
    linkJob?.cancel()
    linkJob = Hub.scope.launch {
      while (isActive) {
        runCatching { Hub.adb.keepAlive(Hub.engine.config().autoReconnect) }
        delay(20_000)
      }
    }
  }

  /** Shows or hides the floating panel according to the user's setting and what is happening on screen. */
  private fun startFloating() {
    val panel = floating ?: FloatingPanel(this).also { floating = it }
    val overlay = hud ?: PerfHud(this).also { hud = it }
    floatJob?.cancel()
    floatJob = Hub.scope.launch {
      while (isActive) {
        runCatching {
          runCatching { ShizukuHost.tick(this@MonitorService, Hub.detector.foreground) }
          val c = Hub.engine.config()
          val s = Hub.engine.state.value
          // A configured game on screen, whether or not its DPI could be applied. It leaves the moment the game does.
          val game = s.frontPkg
          val key = game ?: "always"
          val forced = SystemClock.elapsedRealtime() < Hub.floatForceUntil
          val wanted = forced || c.floating == "always" || (c.floating == "gaming" && game != null)
          val dismissed = panel.dismissedKey
          if (dismissed != null && dismissed != key) panel.dismissedKey = null
          val canDraw = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MonitorService)
          if (wanted && canDraw && (forced || panel.dismissedKey != key)) panel.show(startOpen = forced) else panel.hide()
          // The live stats readout is separate from the panel, so it can stay on while the panel is closed.
          val hudWanted = c.hud == "always" || (c.hud == "gaming" && game != null)
          if (hudWanted && canDraw) { Perf.acquire("hud"); overlay.show(c.hudPos, c.hudFields) } else { Perf.release("hud"); overlay.hide() }
          syncRecNotif()
          panel.refreshTheme(c.theme)
          // If the engine loop ever died, bring it back.
          Hub.engine.ensureRunning()
        }
        delay(if (forced0()) 500 else 800)
      }
    }
  }

  /** A small "Start recording" notification that is shown while idle, so recording can be started without opening anything. */
  private var idleShown = false
  private fun syncRecNotif() {
    val want = RecState.state == "idle" && RecCfg.load().notif
    if (want == idleShown) return
    idleShown = want
    val nm = getSystemService(NotificationManager::class.java)
    if (!want) { nm.cancel(Notifs.ID_REC_IDLE); return }
    val n = Notifs.builder(this, Notifs.CHANNEL_REC).setContentTitle("Screen recorder").setContentText("Tap Start to record the screen.")
      .setOngoing(true).addAction(Notifs.recStartAction(this, "Start", 20)).build()
    runCatching { nm.notify(Notifs.ID_REC_IDLE, n) }
  }

  private fun forced0() = SystemClock.elapsedRealtime() < Hub.floatForceUntil

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
          if (!Hub.adb.isConnected) Hub.adb.connect(quiet = true)
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
    linkJob?.cancel()
    floating?.hide()
    hud?.hide()
    runCatching { getSystemService(NotificationManager::class.java).cancel(Notifs.ID_REC_IDLE) }
    idleShown = false
    Perf.release("hud")
    netCb?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
    netCb = null
  }

  override fun onDestroy() {
    watcher?.cancel()
    floatJob?.cancel()
    linkJob?.cancel()
    floating?.hide()
    hud?.hide()
    netCb?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
    netCb = null
    super.onDestroy()
  }

  private fun build(title: String, text: String) = Notifs.builder(this).setContentTitle(title).setContentText(text).setOngoing(true)
    .addAction(Notifs.action(this, "SW −10", ACTION_DOWN, 11))
    .addAction(Notifs.action(this, "SW +10", ACTION_UP, 12))
    .addAction(Notifs.action(this, "Restore", ACTION_RESTORE, 13)).build()

  companion object {
    const val ACTION_RESTORE = "expo.modules.densifynative.RESTORE"
    const val ACTION_UP = "expo.modules.densifynative.UP"
    const val ACTION_DOWN = "expo.modules.densifynative.DOWN"
    const val ACTION_STOP = "expo.modules.densifynative.STOP"
    fun start(ctx: Context) { val i = Intent(ctx, MonitorService::class.java); if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
    fun stop(ctx: Context) { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP)) }
  }
}
