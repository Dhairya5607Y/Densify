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

data class BundleCfg(val hz: Boolean, val perf: Boolean, val dnd: Boolean, val rot: Boolean, val guard: Boolean, val bright: Boolean, val awake: Boolean)
/** Stretch for a game. w/h of 0 means "use the global stretch resolution". */
data class StretchCfg(val on: Boolean = false, val w: Int = 0, val h: Int = 0)
/** [sw] is the game's smallest width in dp, the same number Android shows under Developer options. It is turned into a density only when applied. */
data class Profile(val pkg: String, val name: String, val sw: Int, val auto: Boolean, val restore: Boolean, val bundle: BundleCfg, val stretch: StretchCfg = StretchCfg())
data class ScriptCfg(val name: String, val code: String, val trigger: String, val pkg: String, val on: Boolean)
/** What the optimizer does by itself when a game opens. Everything it changes is put back when the game closes. */
data class OptCfg(
  val ram: Boolean = false,
  val anim: Boolean = false,
  val net: Boolean = false,
  val gameMode: Boolean = false,
  /** Turn Android's battery saver off while playing. */
  val saver: Boolean = false,
  /** Turn adaptive battery off while playing. */
  val adaptive: Boolean = false,
  /** Game Mode render scale (0.5 to 0.9). 0 means off. */
  val scale: Float = 0f,
)

/** The colours and shape of the app's current theme, so the floating panel looks like the app. Colours are ARGB. */
data class PanelTheme(
  val bg: Int = 0xFF0A0E13.toInt(),
  val sf: Int = 0xFF141A21.toInt(),
  val sf2: Int = 0xFF1E252D.toInt(),
  val bd: Int = 0xFF2A333D.toInt(),
  val tx: Int = 0xFFF2F5F8.toInt(),
  val mu: Int = 0xFF8B939E.toInt(),
  val ac: Int = 0xFF5CBDFF.toInt(),
  val on: Int = 0xFF04121F.toInt(),
  val r: Float = 20f,
  val rs: Float = 12f,
  val bw: Float = 1f,
  val mono: Boolean = false,
  val upper: Boolean = false,
)

val HUD_FIELDS = listOf("fps", "cpu", "temp", "ram", "ping")
private fun hudFieldsFrom(f: JSONObject?): Set<String> = if (f == null) HUD_FIELDS.toSet() else HUD_FIELDS.filter { f.optBoolean(it, true) }.toSet()

private fun themeFrom(o: JSONObject?): PanelTheme {
  if (o == null) return PanelTheme()
  val d = PanelTheme()
  fun c(k: String, def: Int): Int = if (o.has(k)) o.optLong(k).toInt() else def
  return PanelTheme(
    bg = c("bg", d.bg), sf = c("sf", d.sf), sf2 = c("sf2", d.sf2), bd = c("bd", d.bd), tx = c("tx", d.tx), mu = c("mu", d.mu), ac = c("ac", d.ac), on = c("on", d.on),
    r = o.optDouble("r", d.r.toDouble()).toFloat(), rs = o.optDouble("rs", d.rs.toDouble()).toFloat(), bw = o.optDouble("bw", d.bw.toDouble()).toFloat(),
    mono = o.optBoolean("mono", false), upper = o.optBoolean("upper", false),
  )
}

data class Cfg(
  /** The smallest width (dp) to go back to after a game. 0 means "the phone's own". */
  val defaultSw: Int = 0,
  val automation: Boolean = true,
  val lowBattery: Boolean = true,
  val lowBatteryPercent: Int = 50,
  val floating: String = "off",
  val autoReconnect: Boolean = true,
  val mode: String = "wireless",
  /** Quick values, in smallest width (dp). */
  val quick: List<Int> = emptyList(),
  val profiles: List<Profile> = emptyList(),
  val scripts: List<ScriptCfg> = emptyList(),
  /** Keep the adb link on a local port so it survives Wi-Fi being switched off. */
  val bridge: Boolean = false,
  /** Live stats overlay: "off", "gaming" or "always", its corner (tl, tr, bl, br) and which numbers it shows. */
  val hud: String = "off",
  val hudPos: String = "tr",
  val hudFields: Set<String> = HUD_FIELDS.toSet(),
  /** Record FPS, CPU, temperature and RAM while a configured game is open. */
  val record: Boolean = true,
  val opt: OptCfg = OptCfg(),
  /** The floating panel follows this. The stats overlay deliberately does not. */
  val theme: PanelTheme = PanelTheme(),
) {
  companion object {
    fun parse(raw: String): Cfg = runCatching {
      val o = JSONObject(raw)
      val ps = o.optJSONArray("profiles")
      val q = o.optJSONArray("quick")
      val sc = o.optJSONArray("scripts")
      Cfg(
        defaultSw = o.optInt("defaultSw", 0),
        automation = o.optBoolean("automation", true),
        lowBattery = o.optBoolean("lowBattery", true),
        lowBatteryPercent = o.optInt("lowBatteryPercent", 50),
        floating = o.optString("floating", "off"),
        autoReconnect = o.optBoolean("autoReconnect", true),
        mode = o.optString("mode", "wireless"),
        quick = (0 until (q?.length() ?: 0)).map { q!!.getInt(it) },
        profiles = (0 until (ps?.length() ?: 0)).mapNotNull { i ->
          runCatching {
            val p = ps!!.getJSONObject(i); val b = p.getJSONObject("bundle")
            val s = p.optJSONObject("stretch")
            Profile(p.getString("pkg"), p.getString("name"), p.getInt("sw"), p.optBoolean("auto", true), p.optBoolean("restore", true),
              BundleCfg(b.optBoolean("hz"), b.optBoolean("perf"), b.optBoolean("dnd"), b.optBoolean("rot"), b.optBoolean("guard"), b.optBoolean("bright"), b.optBoolean("awake")),
              if (s == null) StretchCfg() else StretchCfg(s.optBoolean("on"), s.optInt("w", 0), s.optInt("h", 0)))
          }.getOrNull()
        },
        scripts = (0 until (sc?.length() ?: 0)).mapNotNull { i ->
          runCatching {
            val s = sc!!.getJSONObject(i)
            ScriptCfg(s.getString("name"), s.getString("code"), s.getString("trigger"), s.optString("pkg", ""), s.optBoolean("on", true))
          }.getOrNull()
        },
        bridge = o.optBoolean("bridge", false),
        hud = o.optString("hud", "off"),
        hudPos = o.optString("hudPos", "tr"),
        hudFields = hudFieldsFrom(o.optJSONObject("hudFields")),
        record = o.optBoolean("record", true),
        opt = o.optJSONObject("opt")?.let {
          OptCfg(
            ram = it.optBoolean("ram"), anim = it.optBoolean("anim"), net = it.optBoolean("net"), gameMode = it.optBoolean("gameMode"),
            saver = it.optBoolean("saver"), adaptive = it.optBoolean("adaptive"), scale = it.optDouble("scale", 0.0).toFloat(),
          )
        } ?: OptCfg(),
        theme = themeFrom(o.optJSONObject("theme")),
      )
    }.getOrDefault(Cfg())
  }
}

data class EngineState(
  val running: Boolean = false,
  /** The smallest width (dp) the screen has right now, as far as Densify knows. */
  val appliedSw: Int? = null,
  val activePkg: String? = null,
  /** A configured game is on screen right now, even if the width could not be applied. Drives the floating panel. */
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
 * the smallest width, the screen size and every setting the profile changed, from a persisted snapshot (so it also recovers after a crash).
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
  /**
   * After the width or screen size changes (by hand or by a profile) most games relaunch their window, and for a moment no game is in front.
   * Until this time that gap is not read as "left the game", so the engine never restores over the user's change.
   */
  @Volatile private var holdUntil = 0L
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
    // A leftover snapshot or "game active" marker means the last session ended without a restore (crash, killed app, reboot).
    pendingRestore = Hub.prefs.contains("snap") || Hub.prefs.contains("gameActive")
    _state.update { it.copy(running = true) }
    job = Hub.scope.launch {
      checkBoot()
      while (isActive) {
        // One bad tick must never end the loop: a dead loop means no changes and no floating panel until the app is reopened.
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
    // Densify itself is never a game, so opening the app can never change the width.
    val front = if (fg != app.packageName) c.profiles.firstOrNull { it.pkg == fg } else null
    if (_state.value.frontPkg != front?.pkg) _state.update { it.copy(frontPkg = front?.pkg) }
    val p = if (c.automation && front != null && front.auto) front else null
    if (p != null) {
      leftAt = 0
      val nowMs = SystemClock.elapsedRealtime()
      // After a failed attempt wait a few seconds, then try again instead of giving up until the game is reopened.
      if (failedPkg == p.pkg) { if (nowMs - failedAt < 8_000) return; failedPkg = null }
      // A game must stay in front for a moment before anything changes, so a brief flash of its window doesn't trigger it.
      if (candPkg != p.pkg) { candPkg = p.pkg; candAt = nowMs }
      if (active?.pkg != p.pkg && nowMs - candAt < 700) return
      if (active?.pkg != p.pkg || active?.sw != p.sw) apply(p, c)
    } else {
      failedPkg = null
      candPkg = null
      val a = active
      if (a == null) {
        if (pendingRestore) { pendingRestore = false; restoreAll(c) }
        else if (!stuckChecked && Hub.adb.isConnected) { stuckChecked = true; recoverStuckWidth(c) }
        return
      }
      val now = SystemClock.elapsedRealtime()
      if (now < holdUntil) { leftAt = 0; return }
      if (leftAt == 0L) { leftAt = now; return }
      // Leaving the game for any reason (home, recents, another app, screen off) always returns the width and size to the default.
      if (now - leftAt >= GRACE_MS) restoreAll(c)
    }
  }

  /** A game width left behind by an earlier failed restore is put back to the default once, when Densify starts. */
  private suspend fun recoverStuckWidth(c: Cfg) {
    val d = Hub.adb.readDisplay().getOrNull() ?: return
    if (d.ovrDpi != null && d.sw != c.defaultSw && c.profiles.any { it.sw == d.sw }) {
      Hub.log("Found smallest width ${d.sw} left over from a game. Restoring.")
      restoreAll(c)
    }
  }

  private suspend fun apply(p: Profile, c: Cfg) {
    Hub.log("${p.name} (${p.pkg}) in front. Applying smallest width ${p.sw}${if (p.stretch.on) " and stretch" else ""}")
    applyBundle(p.bundle)
    runCatching { applyOptimizer(c.opt, p.pkg) }.onFailure { Hub.log("Optimizer hiccup: ${it.message}") }
    // The size goes first: smallest width depends on the screen size, so the width is worked out against the final size.
    if (p.stretch.on) applyStretch(p.stretch) else if (_state.value.stretched) restoreSizeOnly()
    val r = Hub.adb.setSmallestWidth(p.sw)
    if (r.isSuccess) {
      active = p
      holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
      Hub.prefs.edit().putString("gameActive", p.pkg).apply()
      _state.update { it.copy(appliedSw = r.getOrNull(), activePkg = p.pkg, errTitle = null, errHint = null) }
      maybeWarnBattery(c)
      runScripts("start", p.pkg)
    } else {
      failedPkg = p.pkg
      failedAt = SystemClock.elapsedRealtime()
      Hub.log("Couldn't apply smallest width ${p.sw}: ${r.exceptionOrNull()?.message}")
      _state.update { it.copy(errTitle = "Couldn't apply smallest width ${p.sw}.", errHint = "Check your connection.") }
      restoreAll(c)
    }
  }

  /**
   * Goes back to the default smallest width. A default of 0, or one that already equals the phone's own, uses Android's own reset,
   * so the phone ends up exactly as it shipped. Anything else is applied as a smallest width.
   */
  private suspend fun restoreDefaultWidth(c: Cfg): Result<Int> {
    val d = Hub.adb.readDisplay().getOrNull()
    val want = c.defaultSw
    if (want <= 0 || d == null || d.physSw == want) {
      val r = Hub.adb.resetDensity()
      if (r.isFailure) return Result.failure(r.exceptionOrNull() ?: IllegalStateException("The system did not reset the density."))
      return Result.success(Hub.adb.readDisplay().getOrNull()?.sw ?: d?.physSw ?: 0)
    }
    return Hub.adb.setSmallestWidth(want)
  }

  suspend fun restoreAll(c: Cfg = config()): Result<Int> {
    val prev = active
    restoreBundle()
    val r = restoreDefaultWidth(c)
    if (r.isSuccess) {
      val sw = r.getOrDefault(0)
      active = null; leftAt = 0; holdUntil = 0; batteryNotified = false
      Hub.prefs.edit().remove("gameActive").apply()
      _state.update { it.copy(appliedSw = sw, activePkg = null, stretched = false, errTitle = null, errHint = null) }
      Hub.log("Restored smallest width $sw")
      if (prev != null) runScripts("exit", prev.pkg)
    } else {
      _state.update { it.copy(errTitle = "Couldn't restore the default smallest width.", errHint = "Check your connection, then retry.") }
    }
    return r
  }

  /**
   * The one way to change the width by hand (floating panel, notification, tile, app). The engine learns the new value, so no
   * screen keeps showing the old one, and it will not restore over the change while the game relaunches its window.
   */
  suspend fun adjustSw(sw: Int): Result<Int> {
    val target = sw.coerceIn(SW_MIN, SW_MAX)
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    val r = Hub.adb.setSmallestWidth(target)
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    if (r.isSuccess) {
      _state.update { it.copy(appliedSw = r.getOrNull()) }
      Hub.log("Smallest width set to ${r.getOrNull()} by hand")
    }
    return r
  }

  /** For callers that think in raw density (the external API's SET_DPI and the binder API). Everything else uses [adjustSw]. */
  suspend fun adjustDpi(dpi: Int): Result<Int> {
    val target = dpi.coerceIn(72, 1000)
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    val r = Hub.adb.setDensity(target)
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    if (r.isSuccess) {
      val sw = Hub.adb.readDisplay().getOrNull()?.sw
      if (sw != null) _state.update { it.copy(appliedSw = sw) }
      Hub.log("Density set to $target by hand")
    }
    return r
  }

  /** Reads the real width and size again after something outside the engine changed them (terminal, scripts, other apps). */
  suspend fun syncFromDevice() {
    val d = Hub.adb.readDisplay().getOrNull()
    if (d != null && d.ovrW == null) {
      val snap = readSnap()
      if (snap.has("size")) { snap.remove("size"); writeSnap(snap) }
    }
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    _state.update { it.copy(appliedSw = d?.sw ?: it.appliedSw, stretched = if (d != null) d.ovrW != null else it.stretched) }
  }

  /** One-tap RAM clean from the app or the floating panel. Returns a short sentence to show. */
  suspend fun optimizeNow(): String = Optimizer.cleanRam()

  /**
   * Runs a screen-size change and then puts the smallest width back to what it was. Without this, changing the size to a
   * shape with a shorter side would quietly change the width the user chose.
   */
  private suspend fun <T> keepingWidth(block: suspend () -> T): T {
    val before = Hub.adb.readDisplay().getOrNull()?.sw
    val out = block()
    if (before != null && before > 0) {
      val now = Hub.adb.readDisplay().getOrNull()?.sw
      if (now != null && now != before) {
        val r = Hub.adb.setSmallestWidth(before)
        if (r.isSuccess) _state.update { it.copy(appliedSw = r.getOrNull()) }
      }
    }
    return out
  }

  /** Floating-panel action: stretch on/off for the game that is open right now. */
  suspend fun toggleStretch(): Boolean {
    if (_state.value.stretched) { keepingWidth { restoreSizeOnly() }; return false }
    val st = active?.stretch
    val (w, h) = if (st != null && st.on && st.w > 0 && st.h > 0) Pair(st.w, st.h) else globalSize()
    return stretchNow(w, h)
  }

  fun globalSize(): Pair<Int, Int> = Pair(Hub.prefs.getInt("stretchW", 0), Hub.prefs.getInt("stretchH", 0))

  /** Applies a screen size right now (from the app, the floating panel or a broadcast) and remembers it as the global size. */
  suspend fun stretchNow(w: Int, h: Int): Boolean {
    if (w !in 320..4096 || h !in 320..4096) { Hub.log("Stretch skipped: ${w}x$h is out of range"); return false }
    Hub.prefs.edit().putInt("stretchW", w).putInt("stretchH", h).apply()
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    val ok = keepingWidth { applyStretch(w, h) }
    holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    return ok
  }

  /** Puts the screen size back to what it was before stretching (or to the system default). */
  suspend fun unstretch() {
    keepingWidth {
      val snap = readSnap()
      if (snap.has("size")) restoreSizeOnly() else { Hub.adb.resetSize(); _state.update { it.copy(stretched = false) } }
    }
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

  /** The optimizer's per-game changes. Each one is recorded in the snapshot first, so closing the game puts everything back. */
  private suspend fun applyOptimizer(o: OptCfg, pkg: String) {
    if (!o.ram && !o.anim && !o.net && !o.gameMode && !o.saver && !o.adaptive && o.scale <= 0f) return
    val snap = readSnap()
    suspend fun put(ns: String, k: String, v: String) {
      val key = "$ns/$k"
      if (!snap.has(key)) snap.put(key, Hub.adb.getSetting(ns, k))
      Hub.adb.exec("settings put $ns $k $v")
    }
    if (o.ram) Hub.log(Optimizer.cleanRam())
    if (o.anim) {
      put("global", "window_animation_scale", "0.5")
      put("global", "transition_animation_scale", "0.5")
      put("global", "animator_duration_scale", "0.5")
    }
    if (o.net) {
      // Fewer background radio scans means fewer ping spikes mid-match.
      put("global", "wifi_scan_always_enabled", "0")
      put("global", "ble_scan_always_enabled", "0")
      val had = snap.has("lowlat")
      val ll = had || Optimizer.lowLatencyWifi(true)
      if (ll && !had) snap.put("lowlat", "1")
      Hub.log(if (ll) "Network boost on: low-latency Wi-Fi and fewer radio scans" else "Network boost: this phone has no low-latency Wi-Fi switch, only the scan changes were applied")
    }
    // Battery saver and adaptive battery both hold the CPU back, so they are switched off while playing and put back afterwards.
    if (o.saver) put("global", "low_power", "0")
    if (o.adaptive) put("global", "adaptive_battery_management_enabled", "0")
    if (o.gameMode && !snap.has("gm") && Optimizer.setGameMode(pkg, true)) snap.put("gm", pkg)
    if (o.scale > 0f && !snap.has("gs") && Optimizer.setRenderScale(pkg, o.scale)) snap.put("gs", pkg)
    writeSnap(snap)
  }

  private suspend fun restoreBundle() {
    val raw = Hub.prefs.getString("snap", null) ?: return
    val snap = JSONObject(raw)
    for (key in snap.keys().asSequence().toList()) {
      if (key == "perf") { Hub.adb.exec("cmd power set-fixed-performance-mode-enabled false"); continue }
      if (key == "lowlat") { Optimizer.lowLatencyWifi(false); continue }
      if (key == "gm") { Optimizer.setGameMode(snap.getString(key), false); continue }
      if (key == "gs") { Optimizer.resetRenderScale(snap.getString(key)); continue }
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

  companion object {
    const val GRACE_MS = 1_200L
    /** How long after a width or size change the engine ignores a game briefly leaving the screen. */
    const val HOLD_MS = 6_000L
    /** Sanity limits for a smallest width in dp. Android's own density limits (72 to 1000) apply on top of these. */
    const val SW_MIN = 100
    const val SW_MAX = 2400
  }
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
