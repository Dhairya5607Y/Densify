package expo.modules.densifynative

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

data class BundleCfg(val hz: Boolean, val perf: Boolean, val dnd: Boolean, val rot: Boolean, val guard: Boolean, val bright: Boolean, val awake: Boolean)
data class Profile(val pkg: String, val name: String, val dpi: Int, val auto: Boolean, val restore: Boolean, val bundle: BundleCfg)
data class Cfg(
  val defaultDpi: Int = 0,
  val automation: Boolean = true,
  val lowBattery: Boolean = true,
  val lowBatteryPercent: Int = 50,
  val quick: List<Int> = emptyList(),
  val profiles: List<Profile> = emptyList(),
) {
  companion object {
    fun parse(raw: String): Cfg = runCatching {
      val o = JSONObject(raw)
      val ps = o.optJSONArray("profiles")
      val q = o.optJSONArray("quick")
      Cfg(
        o.optInt("defaultDpi", 0), o.optBoolean("automation", true), o.optBoolean("lowBattery", true), o.optInt("lowBatteryPercent", 50),
        (0 until (q?.length() ?: 0)).map { q!!.getInt(it) },
        (0 until (ps?.length() ?: 0)).mapNotNull { i ->
          runCatching {
            val p = ps!!.getJSONObject(i); val b = p.getJSONObject("bundle")
            Profile(p.getString("pkg"), p.getString("name"), p.getInt("dpi"), p.optBoolean("auto", true), p.optBoolean("restore", true),
              BundleCfg(b.optBoolean("hz"), b.optBoolean("perf"), b.optBoolean("dnd"), b.optBoolean("rot"), b.optBoolean("guard"), b.optBoolean("bright"), b.optBoolean("awake")))
          }.getOrNull()
        }
      )
    }.getOrDefault(Cfg())
  }
}

data class EngineState(val running: Boolean = false, val appliedDpi: Int? = null, val activePkg: String? = null, val errTitle: String? = null, val errHint: String? = null)

/**
 * One coroutine owns all state. game->game applies directly; leaving a game waits GRACE_MS then restores
 * the DPI and every setting the profile changed, from a persisted snapshot (so it also recovers after a crash).
 */
class Engine(private val app: Context) {
  private val _state = MutableStateFlow(EngineState())
  val state: StateFlow<EngineState> = _state
  private var job: Job? = null
  private var active: Profile? = null
  private var leftAt = 0L
  private var failedPkg: String? = null
  private var pendingRestore = false
  private var batteryNotified = false
  private var raw = ""
  private var cfg = Cfg()

  fun config(): Cfg {
    val r = Hub.prefs.getString("engine", "") ?: ""
    if (r != raw) { raw = r; cfg = Cfg.parse(r) }
    return cfg
  }

  fun start() {
    if (job?.isActive == true) return
    pendingRestore = Hub.prefs.contains("snap")
    _state.update { it.copy(running = true) }
    job = Hub.scope.launch {
      while (isActive) {
        if (!Hub.detector.hasAccess()) {
          _state.update { it.copy(errTitle = "Usage Access is off.", errHint = "Allow Usage Access so Densify can see which game is open.") }
        } else tick()
        delay(if (Hub.detector.screenOn) 1_500 else 6_000)
      }
    }
  }

  fun stop() { job?.cancel(); _state.update { it.copy(running = false) } }

  private suspend fun tick() {
    val c = config()
    val fg = Hub.detector.poll()
    val p = if (c.automation) c.profiles.firstOrNull { it.pkg == fg && it.auto } else null
    if (p != null) {
      leftAt = 0
      if (failedPkg == p.pkg) return
      if (active?.pkg != p.pkg || active?.dpi != p.dpi) apply(p, c)
    } else {
      failedPkg = null
      val a = active
      if (a == null) {
        if (pendingRestore) { pendingRestore = false; restoreAll(c) }
        return
      }
      val now = SystemClock.elapsedRealtime()
      if (leftAt == 0L) { leftAt = now; return }
      if (now - leftAt >= GRACE_MS) { if (a.restore) restoreAll(c) else { active = null; leftAt = 0 } }
    }
  }

  private suspend fun apply(p: Profile, c: Cfg) {
    applyBundle(p.bundle)
    val r = Hub.adb.setDensity(p.dpi)
    if (r.isSuccess) {
      active = p
      _state.update { it.copy(appliedDpi = p.dpi, activePkg = p.pkg, errTitle = null, errHint = null) }
      maybeWarnBattery(c)
    } else {
      failedPkg = p.pkg
      _state.update { it.copy(errTitle = "Couldn't apply ${p.dpi} DPI.", errHint = "Check your Wireless Debugging connection.") }
      restoreAll(c)
    }
  }

  suspend fun restoreAll(c: Cfg = config()): Result<Int> {
    restoreBundle()
    val d = c.defaultDpi
    if (d <= 0) {
      _state.update { it.copy(errTitle = "No default DPI set.", errHint = "Detect or enter your default DPI in Settings first.") }
      return Result.failure(IllegalStateException("No default DPI"))
    }
    val r = Hub.adb.setDensity(d)
    if (r.isSuccess) { active = null; leftAt = 0; batteryNotified = false; _state.update { it.copy(appliedDpi = d, activePkg = null, errTitle = null, errHint = null) } }
    else _state.update { it.copy(errTitle = "Couldn't restore $d DPI.", errHint = "Check your Wireless Debugging connection, then retry.") }
    return r
  }

  private suspend fun applyBundle(b: BundleCfg) {
    val snap = JSONObject(Hub.prefs.getString("snap", "{}") ?: "{}")
    suspend fun put(ns: String, k: String, v: String) {
      val key = "$ns/$k"
      if (!snap.has(key)) snap.put(key, Hub.adb.getSetting(ns, k))
      Hub.adb.exec("settings put $ns $k $v")
    }
    if (b.hz) { val v = String.format(Locale.US, "%.1f", Hub.maxHz()); put("system", "peak_refresh_rate", v); put("system", "min_refresh_rate", v) }
    if (b.rot) put("system", "accelerometer_rotation", "0")
    if (b.dnd) put("global", "heads_up_notifications_enabled", "0")
    if (b.bright) put("system", "screen_brightness_mode", "0")
    if (b.awake) put("system", "screen_off_timeout", "1800000")
    if (b.perf && Hub.adb.perfSupported()) { snap.put("perf", "1"); Hub.adb.exec("cmd power set-fixed-performance-mode-enabled true") }
    // b.guard (touch guard) is intentionally not applied yet: no reliable cross-device mechanism.
    Hub.prefs.edit().putString("snap", snap.toString()).apply()
  }

  private suspend fun restoreBundle() {
    val raw = Hub.prefs.getString("snap", null) ?: return
    val snap = JSONObject(raw)
    for (key in snap.keys().asSequence().toList()) {
      if (key == "perf") { Hub.adb.exec("cmd power set-fixed-performance-mode-enabled false"); continue }
      val parts = key.split("/")
      val v = snap.getString(key)
      if (v == "null") Hub.adb.exec("settings delete ${parts[0]} ${parts[1]}") else Hub.adb.exec("settings put ${parts[0]} ${parts[1]} $v")
    }
    Hub.prefs.edit().remove("snap").apply()
  }

  private fun maybeWarnBattery(c: Cfg) {
    if (!c.lowBattery || batteryNotified) return
    val s = batteryInfo(app)
    if (!s.charging && s.level < c.lowBatteryPercent) {
      batteryNotified = true
      Notifs.post(app, Notifs.ID_BATTERY, "Battery at ${s.level}%", "Games drain faster at this level. Plug in to keep your session going.")
    }
  }

  companion object { const val GRACE_MS = 2_000L }
}

data class BatteryInfo(val level: Int, val charging: Boolean, val tempC: Float)
fun batteryInfo(ctx: Context): BatteryInfo {
  val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
  val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
  val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
  return BatteryInfo(if (level < 0) 100 else level * 100 / scale, plugged, temp)
}
