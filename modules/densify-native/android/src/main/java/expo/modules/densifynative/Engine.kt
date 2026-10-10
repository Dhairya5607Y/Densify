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
import kotlin.math.abs
import kotlin.math.roundToInt

data class BundleCfg(val hz: Boolean, val perf: Boolean, val dnd: Boolean, val rot: Boolean, val guard: Boolean, val bright: Boolean, val awake: Boolean)
data class StretchCfg(val on: Boolean = false, val mode: String = "ratio", val ratio: String = "4:3", val w: Int = 0, val h: Int = 0)
data class Profile(val pkg: String, val name: String, val dpi: Int, val auto: Boolean, val restore: Boolean, val bundle: BundleCfg, val stretch: StretchCfg = StretchCfg())
data class ScriptCfg(val name: String, val code: String, val trigger: String, val pkg: String, val on: Boolean)
data class Cfg(
  val defaultDpi: Int = 0,
  val automation: Boolean = true,
  val lowBattery: Boolean = true,
  val lowBatteryPercent: Int = 50,
  val floating: String = "off",
  val autoReconnect: Boolean = true,
  val mode: String = "wireless",
  val quick: List<Int> = emptyList(),
  val profiles: List<Profile> = emptyList(),
  val scripts: List<ScriptCfg> = emptyList(),
) {
  companion object {
    fun parse(raw: String): Cfg = runCatching {
      val o = JSONObject(raw)
      val ps = o.optJSONArray("profiles")
      val q = o.optJSONArray("quick")
      val sc = o.optJSONArray("scripts")
      Cfg(
        o.optInt("defaultDpi", 0), o.optBoolean("automation", true), o.optBoolean("lowBattery", true), o.optInt("lowBatteryPercent", 50),
        o.optString("floating", "off"), o.optBoolean("autoReconnect", true), o.optString("mode", "wireless"),
        (0 until (q?.length() ?: 0)).map { q!!.getInt(it) },
        (0 until (ps?.length() ?: 0)).mapNotNull { i ->
          runCatching {
            val p = ps!!.getJSONObject(i); val b = p.getJSONObject("bundle")
            val s = p.optJSONObject("stretch")
            Profile(p.getString("pkg"), p.getString("name"), p.getInt("dpi"), p.optBoolean("auto", true), p.optBoolean("restore", true),
              BundleCfg(b.optBoolean("hz"), b.optBoolean("perf"), b.optBoolean("dnd"), b.optBoolean("rot"), b.optBoolean("guard"), b.optBoolean("bright"), b.optBoolean("awake")),
              if (s == null) StretchCfg() else StretchCfg(s.optBoolean("on"), s.optString("mode", "ratio"), s.optString("ratio", "4:3"), s.optInt("w", 0), s.optInt("h", 0)))
          }.getOrNull()
        },
        (0 until (sc?.length() ?: 0)).mapNotNull { i ->
          runCatching {
            val s = sc!!.getJSONObject(i)
            ScriptCfg(s.getString("name"), s.getString("code"), s.getString("trigger"), s.optString("pkg", ""), s.optBoolean("on", true))
          }.getOrNull()
        },
      )
    }.getOrDefault(Cfg())
  }
}

data class EngineState(
  val running: Boolean = false,
  val appliedDpi: Int? = null,
  val activePkg: String? = null,
  val stretched: Boolean = false,
  val errTitle: String? = null,
  val errHint: String? = null,
)

/** Works out the screen size for a stretch profile, in the same orientation as the panel's natural size. */
fun stretchTarget(s: ScreenSize, st: StretchCfg): Pair<Int, Int>? {
  if (st.mode == "custom") {
    return if (st.w in 320..4096 && st.h in 320..4096) Pair(st.w, st.h) else null
  }
  val parts = st.ratio.split(":")
  val a = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
  val b = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
  if (a <= 0.0 || b <= 0.0) return null
  val ratio = maxOf(a, b) / minOf(a, b)
  val short = minOf(s.physW, s.physH)
  val long = maxOf(s.physW, s.physH)
  var ns: Int
  var nl: Int
  if (short * ratio <= long) { ns = short; nl = (short * ratio).roundToInt() }
  else { nl = long; ns = (long / ratio).roundToInt() }
  ns = (ns / 2) * 2
  nl = (nl / 2) * 2
  if (ns < 320 || nl < 320) return null
  return if (s.physW < s.physH) Pair(ns, nl) else Pair(nl, ns)
}

/**
 * One coroutine owns all state. game->game applies directly; leaving a game waits GRACE_MS then restores
 * the DPI, the screen size and every setting the profile changed, from a persisted snapshot (so it also recovers after a crash).
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
      checkBoot()
      while (isActive) {
        if (!Hub.detector.hasAccess()) {
          _state.update { it.copy(errTitle = "Usage Access is off.", errHint = "Allow Usage Access so Densify can see which game is open.") }
        } else tick()
        delay(if (!Hub.detector.screenOn) 6_000 else if (active != null) 700L else 1_500L)
      }
    }
  }

  fun stop() { job?.cancel(); _state.update { it.copy(running = false) } }

  /** Runs "boot" scripts once per device boot, the first time the engine starts after it. */
  private suspend fun checkBoot() {
    val stamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val last = Hub.prefs.getLong("bootStamp", 0L)
    if (abs(stamp - last) > 60_000) {
      Hub.prefs.edit().putLong("bootStamp", stamp).apply()
      if (config().scripts.any { it.on && it.trigger == "boot" }) {
        if (!Hub.adb.isConnected) Hub.adb.connect()
        runScripts("boot", null)
      }
    }
  }

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
      // Leaving the game for any reason (home, recents, another app, screen off) always returns DPI and size to the default.
      if (now - leftAt >= GRACE_MS) restoreAll(c)
    }
  }

  private suspend fun apply(p: Profile, c: Cfg) {
    Hub.log("${p.name} opened. Applying ${p.dpi} DPI${if (p.stretch.on) " and stretch" else ""}")
    applyBundle(p.bundle)
    if (p.stretch.on) applyStretch(p.stretch) else if (_state.value.stretched) restoreSizeOnly()
    val r = Hub.adb.setDensity(p.dpi)
    if (r.isSuccess) {
      active = p
      _state.update { it.copy(appliedDpi = p.dpi, activePkg = p.pkg, errTitle = null, errHint = null) }
      maybeWarnBattery(c)
      runScripts("start", p.pkg)
    } else {
      failedPkg = p.pkg
      Hub.log("Couldn't apply ${p.dpi} DPI: ${r.exceptionOrNull()?.message}")
      _state.update { it.copy(errTitle = "Couldn't apply ${p.dpi} DPI.", errHint = "Check your connection.") }
      restoreAll(c)
    }
  }

  suspend fun restoreAll(c: Cfg = config()): Result<Int> {
    val prev = active
    restoreBundle()
    val d = c.defaultDpi
    if (d <= 0) {
      _state.update { it.copy(errTitle = "No default DPI set.", errHint = "Detect or enter your default DPI in Settings first.") }
      return Result.failure(IllegalStateException("No default DPI"))
    }
    val r = Hub.adb.setDensity(d)
    if (r.isSuccess) {
      active = null; leftAt = 0; batteryNotified = false
      _state.update { it.copy(appliedDpi = d, activePkg = null, stretched = false, errTitle = null, errHint = null) }
      Hub.log("Restored $d DPI")
      if (prev != null) runScripts("exit", prev.pkg)
    } else {
      _state.update { it.copy(errTitle = "Couldn't restore $d DPI.", errHint = "Check your connection, then retry.") }
    }
    return r
  }

  /** Floating-panel action: stretch on/off for the game that is open right now. */
  suspend fun toggleStretch(): Boolean {
    val a = active ?: return false
    if (!a.stretch.on) return false
    return if (_state.value.stretched) { restoreSizeOnly(); false } else applyStretch(a.stretch)
  }

  private fun readSnap(): JSONObject = JSONObject(Hub.prefs.getString("snap", "{}") ?: "{}")
  private fun writeSnap(s: JSONObject) { Hub.prefs.edit().putString("snap", s.toString()).apply() }

  private suspend fun applyStretch(st: StretchCfg): Boolean {
    val cur = Hub.adb.readSize().getOrNull() ?: return false
    val snap = readSnap()
    if (!snap.has("size")) { snap.put("size", cur.overrideText ?: "reset"); writeSnap(snap) }
    val target = stretchTarget(cur, st)
    if (target == null) {
      Hub.log("Stretch skipped: invalid target")
      return false
    }
    val r = Hub.adb.setSize(target.first, target.second)
    if (r.isSuccess) _state.update { it.copy(stretched = true) }
    else Hub.log("Stretch failed: ${r.exceptionOrNull()?.message}")
    return r.isSuccess
  }

  private suspend fun restoreSizeOnly() {
    val snap = readSnap()
    if (!snap.has("size")) { _state.update { it.copy(stretched = false) }; return }
    val v = snap.getString("size")
    snap.remove("size")
    writeSnap(snap)
    if (v == "reset") Hub.adb.resetSize() else Hub.adb.exec("wm size $v")
    _state.update { it.copy(stretched = false) }
  }

  private suspend fun applyBundle(b: BundleCfg) {
    val snap = readSnap()
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
    writeSnap(snap)
  }

  private suspend fun restoreBundle() {
    val raw = Hub.prefs.getString("snap", null) ?: return
    val snap = JSONObject(raw)
    for (key in snap.keys().asSequence().toList()) {
      if (key == "perf") { Hub.adb.exec("cmd power set-fixed-performance-mode-enabled false"); continue }
      if (key == "size") {
        val v = snap.getString(key)
        if (v == "reset") Hub.adb.resetSize() else Hub.adb.exec("wm size $v")
        continue
      }
      val parts = key.split("/")
      if (parts.size != 2) continue
      val v = snap.getString(key)
      if (v == "null") Hub.adb.exec("settings delete ${parts[0]} ${parts[1]}") else Hub.adb.exec("settings put ${parts[0]} ${parts[1]} $v")
    }
    Hub.prefs.edit().remove("snap").apply()
    _state.update { it.copy(stretched = false) }
  }

  private suspend fun runScripts(trigger: String, pkg: String?) {
    for (s in config().scripts) {
      if (!s.on || s.trigger != trigger) continue
      if (s.pkg.isNotEmpty() && s.pkg != pkg) continue
      val r = Hub.adb.exec(s.code)
      Hub.log("Script \"${s.name}\" ${if (r.isSuccess) "ran" else "failed"} on $trigger")
    }
  }

  private fun maybeWarnBattery(c: Cfg) {
    if (!c.lowBattery || batteryNotified) return
    val s = batteryInfo(app)
    if (!s.charging && s.level < c.lowBatteryPercent) {
      batteryNotified = true
      Notifs.post(app, Notifs.ID_BATTERY, "Battery at ${s.level}%", "Games drain faster at this level. Plug in to keep your session going.")
    }
  }

  companion object { const val GRACE_MS = 1_200L }
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
