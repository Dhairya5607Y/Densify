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
/** Stretch for a game. w/h of 0 means "use the global stretch resolution". */
data class StretchCfg(val on: Boolean = false, val w: Int = 0, val h: Int = 0)
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
              if (s == null) StretchCfg() else StretchCfg(s.optBoolean("on"), s.optInt("w", 0), s.optInt("h", 0)))
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
  /** A configured game is on screen right now, even if DPI could not be applied. Drives the floating panel. */
  val frontPkg: String? = null,
  val stretched: Boolean = false,
  val errTitle: String? = null,
  val errHint: String? = null,
)

/** Parses "2160*1080", "2160x1080", "2160 × 1080" or "2160,1080" into a width/height pair, or null when invalid. */
fun parseSize(text: String): Pair<Int, Int>? {
  val m = Regex("^\\s*(\\d{3,4})\\s*[*xX×,:\\s]\\s*(\\d{3,4})\\s*$").find(text) ?: return null
  val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
  return if (w in 320..4096 && h in 320..4096) Pair(w, h) else null
}

/**
 * The size to apply, given as plain width x height. Android wants it in the panel's natural orientation,
 * so if the numbers were typed the other way round for this device they are swapped.
 */
fun stretchTarget(s: ScreenSize, w: Int, h: Int): Pair<Int, Int>? {
  if (w !in 320..4096 || h !in 320..4096) return null
  val portrait = s.physW < s.physH
  return if ((w < h) == portrait || w == h) Pair(w, h) else Pair(h, w)
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
  private var failedAt = 0L
  private var stuckChecked = false
  private var candPkg: String? = null
  private var candAt = 0L
  private var pendingRestore = false
  private var batteryNotified = false
  private var raw = ""
  private var cfg = Cfg()

  fun config(): Cfg {
    val r = Hub.prefs.getString("engine", "") ?: ""
    if (r != raw) { raw = r; cfg = Cfg.parse(r) }
    return cfg
  }

  @Synchronized fun start() {
    if (job?.isActive == true) return
    pendingRestore = Hub.prefs.contains("snap")
    _state.update { it.copy(running = true) }
    job = Hub.scope.launch {
      checkBoot()
      while (isActive) {
        // One bad tick must never end the loop: a dead loop means no DPI changes and no floating panel until the app is reopened.
        try {
          if (!Hub.detector.hasAccess()) {
            _state.update { it.copy(errTitle = "Usage Access is off.", errHint = "Allow Usage Access so Densify can see which game is open.") }
          } else tick()
        } catch (t: kotlinx.coroutines.CancellationException) { throw t
        } catch (t: Throwable) { Hub.log("Engine hiccup: ${t.message}") }
        delay(if (!Hub.detector.screenOn) 6_000 else if (active != null) 700L else 1_500L)
      }
    }
  }

  fun stop() { job?.cancel(); _state.update { it.copy(running = false) } }

  /** Called by the service now and then: brings the loop back if it ever died. */
  fun ensureRunning() { if (job?.isActive != true) { Hub.log("Engine restarted"); start() } }

  /** Runs "boot" scripts once per device boot, the first time the engine starts after it. */
  private suspend fun checkBoot() {
    val stamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val last = Hub.prefs.getLong("bootStamp", 0L)
    if (abs(stamp - last) > 60_000) {
      Hub.prefs.edit().putLong("bootStamp", stamp).apply()
      if (config().scripts.any { it.on && it.trigger == "boot" }) {
        if (!Hub.adb.isConnected) Hub.adb.connect(quiet = true)
        runScripts("boot", null)
      }
    }
  }

  private suspend fun tick() {
    val c = config()
    val fg = Hub.detector.poll()
    // Densify itself is never a game, so opening the app can never change DPI.
    val front = if (fg != app.packageName) c.profiles.firstOrNull { it.pkg == fg } else null
    if (_state.value.frontPkg != front?.pkg) _state.update { it.copy(frontPkg = front?.pkg) }
    val p = if (c.automation && front != null && front.auto) front else null
    if (p != null) {
      leftAt = 0
      val nowMs = SystemClock.elapsedRealtime()
      // After a failed attempt wait a few seconds, then try again instead of giving up until the game is reopened.
      if (failedPkg == p.pkg) { if (nowMs - failedAt < 8_000) return; failedPkg = null }
      // A game must stay in front for a moment before DPI changes, so a brief flash of its window doesn't trigger it.
      if (candPkg != p.pkg) { candPkg = p.pkg; candAt = nowMs }
      if (active?.pkg != p.pkg && nowMs - candAt < 700) return
      if (active?.pkg != p.pkg || active?.dpi != p.dpi) apply(p, c)
    } else {
      failedPkg = null
      candPkg = null
      val a = active
      if (a == null) {
        if (pendingRestore) { pendingRestore = false; restoreAll(c) }
        else if (!stuckChecked && Hub.adb.isConnected) { stuckChecked = true; recoverStuckDensity(c) }
        return
      }
      val now = SystemClock.elapsedRealtime()
      if (leftAt == 0L) { leftAt = now; return }
      // Leaving the game for any reason (home, recents, another app, screen off) always returns DPI and size to the default.
      if (now - leftAt >= GRACE_MS) restoreAll(c)
    }
  }

  /** A game DPI left behind by an earlier failed restore is put back to the default once, when Densify starts. */
  private suspend fun recoverStuckDensity(c: Cfg) {
    val d = Hub.adb.readDensity().getOrNull() ?: return
    if (d.ovr != null && c.profiles.any { it.dpi == d.ovr }) {
      Hub.log("Found ${d.ovr} DPI left over from a game. Restoring.")
      restoreAll(c)
    }
  }

  private suspend fun apply(p: Profile, c: Cfg) {
    Hub.log("${p.name} (${p.pkg}) in front. Applying ${p.dpi} DPI${if (p.stretch.on) " and stretch" else ""}")
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
      failedAt = SystemClock.elapsedRealtime()
      Hub.log("Couldn't apply ${p.dpi} DPI: ${r.exceptionOrNull()?.message}")
      _state.update { it.copy(errTitle = "Couldn't apply ${p.dpi} DPI.", errHint = "Check your connection.") }
      restoreAll(c)
    }
  }

  /**
   * The density to go back to. A stored default that is zero, or that equals one of the games' own DPI values,
   * is a leftover from a failed restore and not a real default, so the phone's own (base) density is used instead.
   */
  private suspend fun baseDpi(c: Cfg): Int? {
    val phys = Hub.prefs.getInt("physDpi", 0).takeIf { it > 0 } ?: Hub.adb.readDensity().getOrNull()?.physical
    val d = c.defaultDpi
    val bogus = d <= 0 || c.profiles.any { it.dpi == d }
    return if (bogus) null else if (phys != null && d == phys) null else d   // null = "reset to the phone's own density"
  }

  suspend fun restoreAll(c: Cfg = config()): Result<Int> {
    val prev = active
    restoreBundle()
    val target = baseDpi(c)
    val r = if (target == null) Hub.adb.resetDensity() else Hub.adb.setDensity(target)
    if (r.isSuccess) {
      val d = r.getOrDefault(target ?: 0)
      active = null; leftAt = 0; batteryNotified = false
      _state.update { it.copy(appliedDpi = d, activePkg = null, stretched = false, errTitle = null, errHint = null) }
      Hub.log("Restored $d DPI")
      if (prev != null) runScripts("exit", prev.pkg)
    } else {
      _state.update { it.copy(errTitle = "Couldn't restore the default DPI.", errHint = "Check your connection, then retry.") }
    }
    return r
  }

  /** Floating-panel action: stretch on/off for the game that is open right now. */
  suspend fun toggleStretch(): Boolean {
    if (_state.value.stretched) { restoreSizeOnly(); return false }
    val st = active?.stretch
    val (w, h) = if (st != null && st.on && st.w > 0 && st.h > 0) Pair(st.w, st.h) else globalSize()
    return stretchNow(w, h)
  }

  fun globalSize(): Pair<Int, Int> = Pair(Hub.prefs.getInt("stretchW", 0), Hub.prefs.getInt("stretchH", 0))

  /** Applies a screen size right now (from the app, the floating panel or a broadcast) and remembers it as the global size. */
  suspend fun stretchNow(w: Int, h: Int): Boolean {
    if (w !in 320..4096 || h !in 320..4096) { Hub.log("Stretch skipped: ${w}x$h is out of range"); return false }
    Hub.prefs.edit().putInt("stretchW", w).putInt("stretchH", h).apply()
    return applyStretch(w, h)
  }

  /** Puts the screen size back to what it was before stretching (or to the system default). */
  suspend fun unstretch() {
    val snap = readSnap()
    if (snap.has("size")) restoreSizeOnly() else { Hub.adb.resetSize(); _state.update { it.copy(stretched = false) } }
  }

  private fun readSnap(): JSONObject = JSONObject(Hub.prefs.getString("snap", "{}") ?: "{}")
  private fun writeSnap(s: JSONObject) { Hub.prefs.edit().putString("snap", s.toString()).apply() }

  private suspend fun applyStretch(st: StretchCfg): Boolean {
    val (gw, gh) = globalSize()
    return applyStretch(if (st.w > 0 && st.h > 0) st.w else gw, if (st.w > 0 && st.h > 0) st.h else gh)
  }

  private suspend fun applyStretch(w: Int, h: Int): Boolean {
    val cur = Hub.adb.readSize().getOrNull() ?: return false
    val target = stretchTarget(cur, w, h)
    if (target == null) {
      Hub.log("Stretch skipped: no valid resolution set")
      return false
    }
    val snap = readSnap()
    if (!snap.has("size")) { snap.put("size", cur.overrideText ?: "reset"); writeSnap(snap) }
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
