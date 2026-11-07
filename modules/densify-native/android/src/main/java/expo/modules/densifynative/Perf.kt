package expo.modules.densifynative

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One reading of the phone. A value of -1 means "this phone does not expose it". */
data class PerfSample(
  val at: Long = 0L,
  val pkg: String? = null,
  val fps: Int = -1,
  val cpu: Int = -1,
  val tempC: Float = -1f,
  val battC: Float = -1f,
  val ramPct: Int = -1,
  val ramFreeMb: Int = -1,
  val pingMs: Int = -1,
  val cpuMhz: Int = -1,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("at", at).put("pkg", pkg ?: JSONObject.NULL).put("fps", fps).put("cpu", cpu)
    .put("tempC", tempC.toDouble()).put("battC", battC.toDouble()).put("ramPct", ramPct)
    .put("ramFreeMb", ramFreeMb).put("pingMs", pingMs).put("cpuMhz", cpuMhz)
}

/** Running totals for one game session. */
private class Session(val pkg: String, val name: String, val start: Long) {
  private val fps = ArrayList<Int>()
  private var fpsStep = 1
  private var fpsSeen = 0
  var cpuSum = 0L; var cpuN = 0
  var tempSum = 0.0; var tempN = 0; var tempMax = 0f
  var ramSum = 0L; var ramN = 0
  var pingSum = 0L; var pingN = 0; var pingMin = Int.MAX_VALUE; var pingMax = 0
  var count = 0

  fun add(s: PerfSample) {
    count++
    if (s.fps > 0) {
      // Keep memory flat on very long sessions: past 3600 readings only every 2nd, 4th... is kept.
      fpsSeen++
      if (fpsSeen % fpsStep == 0) fps.add(s.fps)
      if (fps.size >= 3600) { fpsStep *= 2; val kept = ArrayList<Int>(); for (i in fps.indices step 2) kept.add(fps[i]); fps.clear(); fps.addAll(kept) }
    }
    if (s.cpu >= 0) { cpuSum += s.cpu; cpuN++ }
    if (s.tempC > 0f) { tempSum += s.tempC; tempN++; tempMax = max(tempMax, s.tempC) }
    if (s.ramPct >= 0) { ramSum += s.ramPct; ramN++ }
    if (s.pingMs >= 0) { pingSum += s.pingMs; pingN++; pingMin = min(pingMin, s.pingMs); pingMax = max(pingMax, s.pingMs) }
  }

  fun toJson(end: Long, live: Boolean): JSONObject {
    val sorted = fps.sorted()
    val avg = if (sorted.isEmpty()) -1 else sorted.average().roundToInt()
    val low1 = if (sorted.isEmpty()) -1 else sorted[(sorted.size * 0.01).toInt().coerceIn(0, sorted.size - 1)]
    return JSONObject()
      .put("pkg", pkg).put("name", name).put("start", start).put("durMs", end - start).put("samples", count).put("live", live)
      .put("fpsAvg", avg).put("fps1", low1).put("fpsMin", sorted.firstOrNull() ?: -1).put("fpsMax", sorted.lastOrNull() ?: -1)
      .put("cpuAvg", if (cpuN == 0) -1 else (cpuSum.toDouble() / cpuN).roundToInt())
      .put("tempAvg", if (tempN == 0) -1.0 else Math.round(tempSum / tempN * 10) / 10.0).put("tempMax", if (tempN == 0) -1.0 else tempMax.toDouble())
      .put("ramAvg", if (ramN == 0) -1 else (ramSum.toDouble() / ramN).roundToInt())
      .put("pingAvg", if (pingN == 0) -1 else (pingSum.toDouble() / pingN).roundToInt())
      .put("pingMin", if (pingN == 0) -1 else pingMin).put("pingMax", if (pingN == 0) -1 else pingMax)
  }
}

/**
 * Reads FPS, CPU load, temperature, RAM and ping over the adb link and keeps per-game averages.
 * It only samples while something needs the numbers (the overlay, the open floating panel, the Stats screen) or while a configured
 * game is open and recording is on, and it samples gently so the monitor itself never costs frames.
 */
object Perf {
  @Volatile var latest: PerfSample = PerfSample()
    private set

  private val consumers: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
  @Volatile private var lastAppPoll = 0L
  private var job: Job? = null

  private var prevTotal = 0L
  private var prevIdle = 0L
  private var tick = 0
  private var lastTemp = -1f
  private var lastBatt = -1f
  private var lastMhz = -1
  private var lastPing = -1

  private var layerPkg: String? = null
  private var layerName: String? = null
  private var layerMisses = 0
  private var layerRetryAt = 0L

  private var session: Session? = null
  private var frontGoneAt = 0L

  fun acquire(tag: String) { consumers.add(tag) }
  fun release(tag: String) { consumers.remove(tag) }
  /** The Stats screen calls this every second; if it stops (app closed), its claim lapses by itself. */
  fun noteAppPoll() { lastAppPoll = SystemClock.elapsedRealtime() }

  @Synchronized fun start() {
    if (job?.isActive == true) return
    job = Hub.scope.launch {
      while (isActive) {
        var wait = 2_500L
        try {
          val now = SystemClock.elapsedRealtime()
          if (consumers.contains("app") && now - lastAppPoll > 5_000) consumers.remove("app")
          val live = consumers.isNotEmpty()
          val cfg = Hub.engine.config()
          val front = Hub.engine.state.value.frontPkg
          val recording = cfg.record && front != null
          if (!Hub.detector.screenOn) {
            wait = 6_000L
            closeSession()
          } else if ((live || recording) && Hub.adb.isConnected) {
            sampleOnce(front, recording, cfg)
            wait = if (live) 1_000L else 2_500L
          } else if (front == null) {
            noteFrontGone()
          }
        } catch (t: kotlinx.coroutines.CancellationException) { throw t
        } catch (t: Throwable) { Hub.log("Monitor hiccup: ${t.message}") }
        delay(wait)
      }
    }
  }

  // ---------- sampling ----------

  private const val BASE_CMD = "head -n1 /proc/stat; echo @@M; grep -E '^(MemTotal|MemAvailable)' /proc/meminfo"
  private const val SLOW_CMD = "; echo @@T; grep -H . /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp 2>/dev/null; echo @@B; cat /sys/class/power_supply/battery/temp 2>/dev/null; echo @@F; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null"

  private suspend fun sampleOnce(front: String?, recording: Boolean, cfg: Cfg) {
    tick++
    val slow = tick % 3 == 1
    val out = Hub.adb.exec(BASE_CMD + (if (slow) SLOW_CMD else "")).getOrNull() ?: return
    val parts = splitSections(out)

    var cpu = -1
    parts["S"]?.lineSequence()?.firstOrNull { it.startsWith("cpu") }?.let { line ->
      val n = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
      if (n.size >= 4) {
        val idle = n[3] + (n.getOrNull(4) ?: 0L)
        val total = n.take(8).sum()
        if (prevTotal > 0 && total > prevTotal) cpu = (100.0 * (1.0 - (idle - prevIdle).toDouble() / (total - prevTotal))).roundToInt().coerceIn(0, 100)
        prevTotal = total; prevIdle = idle
      }
    }

    var ramPct = -1; var ramFree = -1
    parts["M"]?.let { m ->
      val total = Regex("MemTotal:\\s*(\\d+)").find(m)?.groupValues?.get(1)?.toLongOrNull()
      val avail = Regex("MemAvailable:\\s*(\\d+)").find(m)?.groupValues?.get(1)?.toLongOrNull()
      if (total != null && avail != null && total > 0) { ramPct = (100 - avail * 100 / total).toInt().coerceIn(0, 100); ramFree = (avail / 1024).toInt() }
    }

    if (slow) {
      parts["T"]?.let { lastTemp = pickCpuTemp(it) }
      parts["B"]?.trim()?.toFloatOrNull()?.let { lastBatt = normTemp(it) }
      parts["F"]?.let { f ->
        val mx = f.lineSequence().mapNotNull { it.trim().toLongOrNull() }.maxOrNull()
        if (mx != null) lastMhz = (mx / 1000).toInt()
      }
    }

    // When the phone hides its CPU sensors the battery temperature is the closest honest number.
    val temp = if (lastTemp > 0f) lastTemp else lastBatt

    val fgPkg = front ?: Hub.detector.foreground
    val fps = if (fgPkg != null && fgPkg != Hub.app.packageName) readFps(fgPkg) else -1

    if (tick % 5 == 0) lastPing = readPing()

    val s = PerfSample(System.currentTimeMillis(), fgPkg, fps, cpu, temp, lastBatt, ramPct, ramFree, lastPing, lastMhz)
    latest = s

    if (recording && front != null) record(front, s, cfg) else if (front == null) noteFrontGone()
  }

  /** Splits the combined command output into sections: S (cpu line), M (memory), T (thermal), B (battery), F (frequencies). */
  private fun splitSections(out: String): Map<String, String> {
    val m = HashMap<String, String>()
    var key = "S"
    val sb = StringBuilder()
    for (line in out.lineSequence()) {
      val t = line.trim()
      if (t.length == 3 && t.startsWith("@@")) { m[key] = sb.toString(); sb.setLength(0); key = t.substring(2) } else sb.append(line).append('\n')
    }
    m[key] = sb.toString()
    return m
  }

  private fun normTemp(v: Float): Float = when {
    v > 1000f -> v / 1000f
    v > 150f -> v / 10f
    else -> v
  }

  private val GOOD = Regex("cpu|soc|tsens|cluster|apss|big|little|mtktscpu|ap_therm|core", RegexOption.IGNORE_CASE)
  private val BAD = Regex("gpu|batt|skin|charg|usb|modem|wifi|pa_|xo|bms|camera|flash|quiet|display|case|pm\\d|npu|dsp|video|mdm", RegexOption.IGNORE_CASE)

  /** The hottest CPU/SoC thermal zone in a sensible range. Returns -1 when the phone exposes none. */
  private fun pickCpuTemp(raw: String): Float {
    val types = HashMap<String, String>()
    val temps = HashMap<String, Float>()
    for (line in raw.lineSequence()) {
      val i = line.indexOf(':')
      if (i < 0) continue
      val path = line.substring(0, i)
      val value = line.substring(i + 1).trim()
      val zone = path.substringBeforeLast('/')
      if (path.endsWith("/type")) types[zone] = value
      else if (path.endsWith("/temp")) value.toFloatOrNull()?.let { temps[zone] = normTemp(it) }
    }
    var best = -1f
    for ((zone, t) in temps) {
      val ty = types[zone] ?: continue
      if (!GOOD.containsMatchIn(ty) || BAD.containsMatchIn(ty)) continue
      if (t in 15f..125f) best = max(best, t)
    }
    return best
  }

  // ---------- FPS from SurfaceFlinger ----------

  private suspend fun readFps(pkg: String): Int {
    var name = if (layerPkg == pkg) layerName else null
    if (name == null) {
      val now = SystemClock.elapsedRealtime()
      if (layerPkg == pkg && now < layerRetryAt) return -1
      name = findLayer(pkg)
      layerPkg = pkg; layerName = name; layerMisses = 0
      if (name == null) { layerRetryAt = now + 10_000L; return -1 }
    }
    val fps = latencyFps(name)
    if (fps <= 0) { layerMisses++; if (layerMisses >= 4) { layerName = null; layerRetryAt = 0L } } else layerMisses = 0
    return fps
  }

  private suspend fun latencyFps(layer: String): Int {
    val out = Hub.adb.exec("dumpsys SurfaceFlinger --latency '" + layer.replace("'", "") + "'").getOrNull() ?: return -1
    return parseLatency(out)
  }

  /** A game draws into its own surface layer. Pick the one for this package that is actually producing frames. */
  private suspend fun findLayer(pkg: String): String? {
    val list = Hub.adb.exec("dumpsys SurfaceFlinger --list").getOrNull() ?: return null
    val all = list.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it.contains(pkg) }.toList()
    if (all.isEmpty()) return null
    val ordered = all.sortedBy { if (it.contains("SurfaceView", true)) 0 else 1 }.take(4)
    var best: String? = null
    var bestFps = 0
    for (c in ordered) {
      val f = latencyFps(c)
      if (f > bestFps) { bestFps = f; best = c }
    }
    return best ?: ordered.first()
  }

  /** Frames per second over the newest second of presented frames. 0 when the surface has gone quiet. */
  private fun parseLatency(out: String): Int {
    val stamps = ArrayList<Long>()
    var first = true
    for (line in out.lineSequence()) {
      if (first) { first = false; continue }
      val c = line.trim().split(Regex("\\s+"))
      if (c.size < 3) continue
      val t = c[1].toLongOrNull() ?: continue
      if (t <= 0L || t >= Long.MAX_VALUE / 2) continue
      stamps.add(t)
    }
    if (stamps.size < 2) return 0
    val last = stamps.max()
    if (System.nanoTime() - last > 3_000_000_000L) return 0
    val window = stamps.filter { it >= last - 1_000_000_000L }.sorted()
    if (window.size < 2) return 0
    val span = window.last() - window.first()
    if (span <= 0L) return 0
    return min(240.0, (window.size - 1) * 1e9 / span).roundToInt()
  }

  private suspend fun readPing(): Int {
    // Three quick packets, averaged: a single packet made the reading swing between ~9 and ~18 ms on its own.
    val out = Hub.adb.exec("ping -c 3 -i 0.2 -W 1 8.8.8.8 2>/dev/null").getOrNull() ?: return -1
    val all = Regex("time[=<]([0-9.]+)").findAll(out).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
    if (all.isEmpty()) return -1
    return all.average().roundToInt()
  }

  // ---------- sessions ----------

  private fun record(pkg: String, s: PerfSample, cfg: Cfg) {
    frontGoneAt = 0L
    val cur = session
    if (cur != null && cur.pkg != pkg) { closeSession(); }
    if (session == null) session = Session(pkg, cfg.profiles.firstOrNull { it.pkg == pkg }?.name ?: pkg, System.currentTimeMillis())
    session?.add(s)
  }

  /** A game that vanishes for a moment (window relaunch after a DPI change) must not split its session in two. */
  private fun noteFrontGone() {
    val now = SystemClock.elapsedRealtime()
    if (frontGoneAt == 0L) { frontGoneAt = now; return }
    if (now - frontGoneAt > 8_000L) closeSession()
  }

  @Synchronized private fun closeSession() {
    val s = session ?: return
    session = null
    frontGoneAt = 0L
    val end = System.currentTimeMillis()
    // Anything under 20 seconds is a flash, not a session.
    if (end - s.start < 20_000L || s.count < 4) return
    val arr = JSONArray().put(s.toJson(end, false))
    val old = runCatching { JSONArray(Hub.prefs.getString("perfSessions", "[]")) }.getOrDefault(JSONArray())
    for (i in 0 until min(old.length(), 29)) arr.put(old.getJSONObject(i))
    Hub.prefs.edit().putString("perfSessions", arr.toString()).apply()
  }

  /** Saved sessions, newest first, with the one in progress (marked live) in front. */
  @Synchronized fun sessionsJson(): String {
    val arr = JSONArray()
    session?.let { if (it.count >= 3) arr.put(it.toJson(System.currentTimeMillis(), true)) }
    val old = runCatching { JSONArray(Hub.prefs.getString("perfSessions", "[]")) }.getOrDefault(JSONArray())
    for (i in 0 until old.length()) arr.put(old.getJSONObject(i))
    return arr.toString()
  }

  @Synchronized fun clearSessions() { Hub.prefs.edit().remove("perfSessions").apply(); session = null }
}

/** Small actions the optimizer runs through adb. Each reports honestly instead of pretending it worked. */
object Optimizer {
  private fun failed(s: String) = s.contains("error", true) || s.contains("exception", true) || s.contains("unknown", true) || s.contains("not found", true) || s.contains("usage:", true)

  private suspend fun availMb(): Int? {
    val out = Hub.adb.exec("grep MemAvailable /proc/meminfo").getOrNull() ?: return null
    return Regex("(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()?.let { (it / 1024).toInt() }
  }

  /** Stops cached background apps so the game has more free RAM. Never touches the foreground app. */
  suspend fun cleanRam(): String {
    val before = availMb()
    Hub.adb.exec("am kill-all")
    delay(700)
    val after = availMb()
    return if (before != null && after != null) {
      val d = after - before
      if (d >= 30) "Freed about $d MB of RAM ($after MB free now)" else "RAM was already clean ($after MB free)"
    } else "Closed cached background apps"
  }

  /** Android's own low-latency Wi-Fi mode: keeps the radio awake and skips power-save delays while gaming. */
  suspend fun lowLatencyWifi(on: Boolean): Boolean {
    val out = Hub.adb.exec("cmd wifi force-low-latency-mode " + (if (on) "enabled" else "disabled")).getOrNull() ?: return false
    return !failed(out)
  }

  /** Switches the system Game Mode for one app. The command differs between Android versions, so both forms are tried. */
  suspend fun setGameMode(pkg: String, performance: Boolean): Boolean {
    val named = if (performance) "performance" else "standard"
    val a = Hub.adb.exec("cmd game set --mode $named --user 0 $pkg").getOrNull().orEmpty()
    if (!failed(a)) return true
    val b = Hub.adb.exec("cmd game mode " + (if (performance) 2 else 1) + " $pkg").getOrNull().orEmpty()
    return !failed(b)
  }

  /**
   * Asks Android's Game Mode to draw the game at a lower resolution (Android 13 and newer, on games that allow it).
   * That is the one setting that raises frame rate on a heavy game without touching how the interface looks. [scale] is 0.5 to 0.9.
   */
  suspend fun setRenderScale(pkg: String, scale: Float): Boolean {
    val s = scale.coerceIn(0.3f, 0.9f)
    val txt = String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.')
    val out = Hub.adb.exec("cmd game set --mode performance --downscale $txt --user 0 $pkg").getOrNull().orEmpty()
    val ok = !failed(out)
    Hub.log(if (ok) "Render scale $txt for $pkg" else "This phone didn't accept a render scale for $pkg")
    return ok
  }

  /** Undoes [setRenderScale]. */
  suspend fun resetRenderScale(pkg: String) {
    Hub.adb.exec("cmd game reset --user 0 $pkg")
  }

  /**
   * One-time: has Android pre-compile the game's code for speed, which shortens loading and removes some first-run stutter.
   * It can take a while on a large game, so it is run on request and not on every launch.
   */
  suspend fun compileApp(pkg: String): String {
    if (!pkg.matches(Regex("[A-Za-z0-9._]+"))) return "That isn't a valid app."
    val out = Hub.adb.exec("cmd package compile -m speed-profile -f $pkg").getOrNull().orEmpty()
    return if (out.contains("Success", true)) "Done. $pkg is optimized." else if (out.isBlank()) "Couldn't reach the phone." else "Android said: ${out.trim().take(120)}"
  }
}

/** A tiny click-through readout drawn over the game: FPS, CPU load, temperature, RAM and ping. */
class PerfHud(private val ctx: Context) {
  private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())
  private var view: TextView? = null
  private var key = ""
  private var fields: Set<String> = emptySet()
  @Volatile var visible = false
    private set

  private val ticker = object : Runnable {
    override fun run() {
      if (!visible) return
      render()
      main.postDelayed(this, 1_000L)
    }
  }

  private fun scale(): Float {
    val phys = Hub.prefs.getInt("physDpi", 0)
    return (if (phys > 0) phys else ctx.resources.displayMetrics.densityDpi) / 160f
  }

  @SuppressLint("SetTextI18n")
  fun show(pos: String, shown: Set<String>) {
    main.post {
      val k = pos + "|" + shown.sorted().joinToString(",")
      if (visible && k == key) return@post
      if (visible) removeNow()
      val canDraw = Build.VERSION.SDK_INT < 23 || runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
      if (!canDraw) return@post
      val sc = scale()
      val tv = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 10.5f * sc)
        setTextColor(Color.WHITE)
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        setPadding((8 * sc).toInt(), (5 * sc).toInt(), (8 * sc).toInt(), (5 * sc).toInt())
        background = GradientDrawable().apply { setColor(Color.parseColor("#B3050709")); cornerRadius = 9 * sc }
        text = "…"
      }
      val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
      val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = when (pos) {
          "tl" -> Gravity.TOP or Gravity.START
          "bl" -> Gravity.BOTTOM or Gravity.START
          "br" -> Gravity.BOTTOM or Gravity.END
          else -> Gravity.TOP or Gravity.END
        }
        x = (8 * sc).toInt(); y = (8 * sc).toInt()
      }
      if (runCatching { wm.addView(tv, lp) }.isSuccess) {
        view = tv; key = k; fields = shown; visible = true
        main.removeCallbacks(ticker)
        main.post(ticker)
      }
    }
  }

  fun hide() { main.post { removeNow() } }

  private fun removeNow() {
    main.removeCallbacks(ticker)
    view?.let { v -> runCatching { wm.removeView(v) } }
    view = null; key = ""; visible = false
  }

  @SuppressLint("SetTextI18n")
  private fun render() {
    val s = Perf.latest
    val parts = ArrayList<String>()
    if ("fps" in fields) parts.add(if (s.fps >= 0) "${s.fps} FPS" else "– FPS")
    if ("cpu" in fields) parts.add(if (s.cpu >= 0) "CPU ${s.cpu}%" else "CPU –")
    if ("temp" in fields) parts.add(if (s.tempC > 0f) "${s.tempC.roundToInt()}°C" else "–°C")
    if ("ram" in fields) parts.add(if (s.ramPct >= 0) "RAM ${s.ramPct}%" else "RAM –")
    if ("ping" in fields) parts.add(if (s.pingMs >= 0) "${s.pingMs} ms" else "– ms")
    view?.text = parts.joinToString("  ")
    view?.setTextColor(
      when {
        s.tempC >= 48f -> Color.parseColor("#FF7A7A")
        s.tempC >= 42f -> Color.parseColor("#FFC27A")
        else -> Color.WHITE
      },
    )
  }
}
