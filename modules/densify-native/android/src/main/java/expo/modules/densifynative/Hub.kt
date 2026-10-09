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

data class AdbState(val name: String, val message: String? = null)
data class Density(val physical: Int, val override: Int?) { val effective: Int get() = override ?: physical }

/** Process-wide singletons shared by the JS module, the services and the tile. */
object Hub {
  lateinit var app: Context
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  lateinit var adb: AdbHub
  lateinit var detector: ForegroundDetector
  lateinit var engine: Engine
  private var ready = false
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
}

class AdbHub(private val ctx: Context) {
  private val mgr = DensifyAdbManager(ctx)
  private val lock = Mutex()
  private val _state = MutableStateFlow(AdbState("disconnected"))
  val state: StateFlow<AdbState> = _state
  val isConnected: Boolean get() = runCatching { mgr.isConnected }.getOrDefault(false)

  suspend fun pair(port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching { check(mgr.pair("127.0.0.1", port, code)) { "Android rejected the pairing code." } }
  }

  suspend fun connect(): Result<Unit> = lock.withLock {
    withContext(Dispatchers.IO) {
      if (Build.VERSION.SDK_INT < 30) return@withContext fail("Wireless Debugging needs Android 11 or newer.")
      if (isConnected) { _state.value = AdbState("connected"); return@withContext Result.success(Unit) }
      _state.value = AdbState("connecting")
      try {
        if (mgr.connectTls(ctx, 6_000)) { _state.value = AdbState("connected"); Result.success(Unit) }
        else fail("Couldn't reach Wireless Debugging. Turn it on and make sure you're on Wi-Fi.")
      } catch (t: Throwable) { fail(t.message ?: "Connection failed.") }
    }
  }

  private fun fail(msg: String): Result<Unit> {
    _state.value = AdbState("error", msg)
    return Result.failure(IllegalStateException(msg))
  }

  suspend fun exec(cmd: String): Result<String> {
    if (!isConnected) { val c = connect(); if (c.isFailure) return Result.failure(c.exceptionOrNull()!!) }
    val first = run(cmd)
    if (first.isSuccess) return first
    _state.value = AdbState("disconnected")
    val c = connect()
    if (c.isFailure) return Result.failure(c.exceptionOrNull()!!)
    val second = run(cmd)
    if (second.isFailure) _state.value = AdbState("error", "Connection lost. Check your Wireless Debugging connection.")
    return second
  }

  private suspend fun run(cmd: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching { mgr.openStream("shell:$cmd").use { it.openInputStream().bufferedReader().readText() } }
  }

  suspend fun getSetting(ns: String, key: String): String = exec("settings get $ns $key").getOrNull()?.trim().orEmpty().ifEmpty { "null" }

  fun disconnect() { runCatching { mgr.disconnect() }; _state.value = AdbState("disconnected") }

  suspend fun readDensity(): Result<Density> = exec("wm density").mapCatching { out ->
    val phys = Regex("Physical density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toInt() ?: error("Couldn't read the current DPI.")
    val ovr = Regex("Override density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toInt()
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
    return Result.success(Unit)
  }
}
