package expo.modules.densifynative

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.conscrypt.Conscrypt
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdbState(val name: String, val message: String? = null)
data class Density(val physical: Int, val ovr: Int?) { val effective: Int get() = ovr ?: physical }

/** Physical panel size and the current override (null when the size is not overridden). */
data class ScreenSize(val physW: Int, val physH: Int, val ovrW: Int?, val ovrH: Int?) {
  val overrideText: String? get() = if (ovrW != null && ovrH != null) "${ovrW}x${ovrH}" else null
}

/** Process-wide singletons shared by the JS module, the services and the tile. */
object Hub {
  lateinit var app: Context
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  lateinit var adb: AdbHub
  lateinit var detector: ForegroundDetector
  lateinit var engine: Engine
  private var ready = false
  private val logBuf = ArrayDeque<String>()

  /** Elapsed-realtime deadline until which the floating panel is forced visible (used by the in-app preview). */
  @Volatile var floatForceUntil = 0L

  val prefs get() = app.getSharedPreferences("densify", Context.MODE_PRIVATE)

  @Synchronized fun init(ctx: Context) {
    if (ready) return
    app = ctx.applicationContext
    Security.insertProviderAt(Conscrypt.newProvider(), 1)
    adb = AdbHub(app)
    detector = ForegroundDetector(app)
    engine = Engine(app)
    ready = true
  }

  fun maxHz(): Float {
    val dm = app.getSystemService(DisplayManager::class.java)
    val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 60f
    return d.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
  }

  @Synchronized fun log(msg: String) {
    val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    logBuf.addLast("$stamp  $msg")
    while (logBuf.size > 200) logBuf.removeFirst()
  }

  @Synchronized fun logs(): List<String> = logBuf.toList()
  @Synchronized fun clearLogs() { logBuf.clear() }
}

class AdbHub(private val ctx: Context) {
  private val mgr = DensifyAdbManager(ctx)
  private val lock = Mutex()
  private val _state = MutableStateFlow(AdbState("disconnected"))
  val state: StateFlow<AdbState> = _state
  @Volatile private var rootOk = false

  private val rootMode: Boolean get() = Hub.engine.config().mode == "root"
  val isConnected: Boolean get() = if (rootMode) rootOk else runCatching { mgr.isConnected }.getOrDefault(false)

  suspend fun pair(port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching { check(mgr.pair("127.0.0.1", port, code)) { "Android rejected the pairing code." } }
  }

  suspend fun connect(): Result<Unit> = lock.withLock {
    withContext(Dispatchers.IO) {
      if (rootMode) return@withContext connectRoot()
      if (Build.VERSION.SDK_INT < 30) return@withContext fail("Wireless Debugging needs Android 11 or newer.")
      if (isConnected) { _state.value = AdbState("connected"); return@withContext Result.success(Unit) }
      _state.value = AdbState("connecting")
      try {
        if (mgr.connectTls(ctx, 6_000)) { _state.value = AdbState("connected"); Hub.log("Connected over Wireless Debugging"); Result.success(Unit) }
        else fail("Couldn't reach Wireless Debugging. Turn it on and make sure you're on Wi-Fi.")
      } catch (t: Throwable) { fail(t.message ?: "Connection failed.") }
    }
  }

  private fun connectRoot(): Result<Unit> {
    _state.value = AdbState("connecting")
    val out = runRoot("id").getOrNull().orEmpty()
    return if (out.contains("uid=0")) {
      rootOk = true
      _state.value = AdbState("connected")
      Hub.log("Connected with root")
      Result.success(Unit)
    } else {
      rootOk = false
      fail("Root access was denied or isn't available on this device.")
    }
  }

  private fun runRoot(cmd: String): Result<String> = runCatching {
    val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    p.waitFor()
    text
  }

  private fun fail(msg: String): Result<Unit> {
    _state.value = AdbState("error", msg)
    Hub.log("Connection problem: $msg")
    return Result.failure(IllegalStateException(msg))
  }

  suspend fun exec(cmd: String): Result<String> {
    if (!isConnected) { val c = connect(); if (c.isFailure) return Result.failure(c.exceptionOrNull()!!) }
    val first = run(cmd)
    if (first.isSuccess) return first
    _state.value = AdbState("disconnected")
    rootOk = false
    val c = connect()
    if (c.isFailure) return Result.failure(c.exceptionOrNull()!!)
    val second = run(cmd)
    if (second.isFailure) _state.value = AdbState("error", "Connection lost. Check your connection.")
    return second
  }

  private suspend fun run(cmd: String): Result<String> = withContext(Dispatchers.IO) {
    if (rootMode) runRoot(cmd)
    else runCatching { mgr.openStream("shell:$cmd").use { it.openInputStream().bufferedReader().readText() } }
  }

  suspend fun getSetting(ns: String, key: String): String = exec("settings get $ns $key").getOrNull()?.trim().orEmpty().ifEmpty { "null" }

  fun disconnect() { runCatching { mgr.disconnect() }; rootOk = false; _state.value = AdbState("disconnected") }

  suspend fun readDensity(): Result<Density> = exec("wm density").mapCatching { out ->
    val phys = Regex("Physical density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toInt() ?: error("Couldn't read the current DPI.")
    val ovr = Regex("Override density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toInt()
    Hub.prefs.edit().putInt("physDpi", phys).apply()
    Density(phys, ovr)
  }

  suspend fun setDensity(dpi: Int): Result<Int> {
    if (dpi < 72 || dpi > 1000) return Result.failure(IllegalArgumentException("Invalid DPI: $dpi"))
    val out = exec("wm density $dpi").getOrElse { return Result.failure(it) }
    if (out.contains("Error", true) || out.contains("Exception", true)) return Result.failure(IllegalStateException(out.trim()))
    val now = readDensity().getOrElse { return Result.failure(it) }
    return if (now.effective == dpi) Result.success(dpi)
    else Result.failure(IllegalStateException("System reports ${now.effective} DPI instead of $dpi."))
  }

  suspend fun readSize(): Result<ScreenSize> = exec("wm size").mapCatching { out ->
    val phys = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(out) ?: error("Couldn't read the screen size.")
    val ovr = Regex("Override size:\\s*(\\d+)x(\\d+)").find(out)
    ScreenSize(
      phys.groupValues[1].toInt(), phys.groupValues[2].toInt(),
      ovr?.groupValues?.get(1)?.toInt(), ovr?.groupValues?.get(2)?.toInt(),
    )
  }

  suspend fun setSize(w: Int, h: Int): Result<Unit> {
    if (w < 320 || h < 320 || w > 4096 || h > 4096) return Result.failure(IllegalArgumentException("Invalid screen size: ${w}x$h"))
    val out = exec("wm size ${w}x$h").getOrElse { return Result.failure(it) }
    if (out.contains("Error", true) || out.contains("Exception", true)) return Result.failure(IllegalStateException(out.trim()))
    val now = readSize().getOrElse { return Result.failure(it) }
    return if (now.ovrW == w && now.ovrH == h) { Hub.log("Screen size set to ${w}x$h"); Result.success(Unit) }
    else Result.failure(IllegalStateException("The system did not accept ${w}x$h."))
  }

  suspend fun resetSize(): Result<Unit> {
    val out = exec("wm size reset").getOrElse { return Result.failure(it) }
    if (out.contains("Error", true) || out.contains("Exception", true)) return Result.failure(IllegalStateException(out.trim()))
    Hub.log("Screen size reset")
    return Result.success(Unit)
  }

  private var perf: Boolean? = null
  suspend fun perfSupported(): Boolean {
    perf?.let { return it }
    val v = exec("cmd power help").getOrNull()?.contains("set-fixed-performance-mode-enabled") ?: return false
    perf = v
    return v
  }

  suspend fun grantSelf(): Result<Unit> {
    val pkg = ctx.packageName
    val a = exec("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
    if (a.isFailure) return Result.failure(a.exceptionOrNull()!!)
    val b = exec("appops set $pkg GET_USAGE_STATS allow")
    if (b.isFailure) return Result.failure(b.exceptionOrNull()!!)
    // Best effort: lets the floating panel appear without a trip to Settings.
    exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
    return Result.success(Unit)
  }
}
