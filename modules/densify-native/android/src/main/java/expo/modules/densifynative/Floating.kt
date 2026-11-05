package expo.modules.densifynative

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A scroll area that never grows taller than [limit] pixels, so a long panel scrolls instead of running off the screen. */
class MaxHeightScrollView(ctx: Context, private val limit: () -> Int) : ScrollView(ctx) {
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit().coerceAtLeast(1), MeasureSpec.AT_MOST))
  }
}

private fun lum(c: Int): Double {
  fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
  return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
}

private fun ratio(a: Int, b: Int): Double {
  val l1 = lum(a); val l2 = lum(b)
  return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** Whatever theme arrives, text is always readable on its background. A colour that would vanish is swapped for black or white. */
private fun readable(t: PanelTheme): PanelTheme {
  fun opaque(c: Int) = c or BLACK
  val bg = opaque(t.bg); val sf = opaque(t.sf); val sf2 = opaque(t.sf2)
  val plain = if (lum(bg) > 0.4) BLACK else WHITE
  val tx = opaque(t.tx).let { if (ratio(it, bg) >= 4.0 && ratio(it, sf) >= 3.0) it else plain }
  val mu = opaque(t.mu).let { if (ratio(it, bg) >= 2.4 && ratio(it, sf) >= 2.0) it else tx }
  val ac = opaque(t.ac).let { if (ratio(it, bg) >= 2.0) it else tx }
  val on = opaque(t.on).let { if (ratio(it, ac) >= 3.0) it else if (lum(ac) > 0.4) BLACK else WHITE }
  return t.copy(bg = bg, sf = sf, sf2 = sf2, bd = opaque(t.bd), tx = tx, mu = mu, ac = ac, on = on)
}

/**
 * A draggable bubble that expands into a control panel, drawn over the game: live FPS / CPU / temperature / RAM / ping,
 * smallest-width steps, quick values, stretch resolution, one-tap optimize and the screen recorder. Built from plain Views.
 * Sizes are in "physical dp" so the panel keeps its size when Densify changes the screen density.
 * The panel takes the app's theme. The tall panel scrolls, so every control stays reachable on a short landscape screen.
 */
class FloatingPanel(private val ctx: Context) {
  private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())

  private var root: LinearLayout? = null
  private var lp: WindowManager.LayoutParams? = null
  private var bubble: ImageView? = null
  private var panel: LinearLayout? = null
  private var valueLabel: TextView? = null
  private var recBtn: TextView? = null
  private var pauseBtn: TextView? = null
  private val stats = HashMap<String, TextView>()
  private var scale = 1f
  private var expanded = false

  private var rawTheme = PanelTheme()
  private var t = readable(PanelTheme())

  /** The smallest width the user has tapped to but that has not been applied yet. While set, the label shows it and nothing overwrites it. */
  @Volatile private var pending: Int? = null
  private val busy = AtomicBoolean(false)

  /** The game (or "always") the user dismissed the panel for. The service skips showing it again until that changes. */
  @Volatile var dismissedKey: String? = null
  @Volatile var visible = false
    private set

  private val ticker = object : Runnable {
    override fun run() {
      if (!visible || !expanded) return
      updateStats()
      syncValue()
      syncRec()
      main.postDelayed(this, 1_000L)
    }
  }

  fun show(startOpen: Boolean = false) {
    main.post {
      if (visible) { syncValue(); return@post }
      val canDraw = Build.VERSION.SDK_INT < 23 || runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
      if (!canDraw) return@post
      build()
      val r = root ?: return@post
      val p = lp ?: return@post
      runCatching { wm.addView(r, p); visible = true }
      if (startOpen) setExpanded(true)
      syncValue()
    }
  }

  fun hide() {
    main.post {
      if (!visible) return@post
      main.removeCallbacks(ticker)
      Perf.release("panel")
      root?.let { r -> runCatching { imm.hideSoftInputFromWindow(r.windowToken, 0) } }
      root?.let { v -> runCatching { wm.removeView(v) } }
      visible = false
      expanded = false
      clearRefs()
    }
  }

  private fun clearRefs() {
    root = null; panel = null; bubble = null; valueLabel = null; quickA = null; quickB = null; sizeIn = null; sizeStatus = null; recBtn = null; pauseBtn = null
    stats.clear()
  }

  /** Called by the service every moment. When the app's theme changed, the panel is rebuilt in the new look. */
  fun refreshTheme(n: PanelTheme) {
    main.post {
      if (n == rawTheme) return@post
      rawTheme = n
      t = readable(n)
      if (!visible) return@post
      // Never pull the panel apart while the user is typing into it.
      if (quickA?.hasFocus() == true || quickB?.hasFocus() == true || sizeIn?.hasFocus() == true) return@post
      val wasOpen = expanded
      main.removeCallbacks(ticker)
      if (wasOpen) Perf.release("panel")
      root?.let { v -> runCatching { wm.removeView(v) } }
      visible = false
      expanded = false
      clearRefs()
      build()
      val r = root ?: return@post
      val p = lp ?: return@post
      runCatching { wm.addView(r, p); visible = true }
      if (wasOpen) setExpanded(true)
      syncValue()
    }
  }

  // ---------- building ----------

  private fun physDensity(): Float {
    val phys = Hub.prefs.getInt("physDpi", 0)
    val dpi = if (phys > 0) phys else ctx.resources.displayMetrics.densityDpi
    return dpi / 160f
  }

  private fun px(dp: Float): Int = (dp * scale).toInt()

  private fun face(bold: Boolean): Typeface = Typeface.create(if (t.mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)

  private fun txt(s: String): String = if (t.upper) s.uppercase() else s

  private fun label(text: String, sizeDp: Float, color: Int, bold: Boolean = false, caps: Boolean = true): TextView = TextView(ctx).apply {
    this.text = if (caps) txt(text) else text
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDp * scale)
    setTextColor(color)
    typeface = face(bold)
    gravity = Gravity.CENTER
    includeFontPadding = false
  }

  private fun shape(fill: Int, radiusDp: Float, strokeDp: Float = 0f, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radiusDp * scale
    if (strokeDp > 0f) setStroke(px(strokeDp).coerceAtLeast(1), stroke)
  }

  private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

  /** primary: filled with the theme's accent. Otherwise a quiet button on the surface colour. */
  private fun button(text: String, primary: Boolean, sizeDp: Float = 13f, onClick: () -> Unit): TextView =
    label(text, sizeDp, if (primary) t.on else t.tx, true).apply {
      background = if (primary) shape(t.ac, t.rs) else shape(t.sf2, t.rs, t.bw, t.bd)
      setPadding(px(6f), px(11f), px(6f), px(11f))
      isClickable = true
      setOnClickListener { onClick() }
    }

  private var quickA: EditText? = null
  private var quickB: EditText? = null
  private var sizeIn: EditText? = null
  private var sizeStatus: TextView? = null
  private val imm get() = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

  private fun field(hint: String, text: String, onDone: () -> Unit, numeric: Boolean): EditText = EditText(ctx).apply {
    setText(text)
    this.hint = hint
    setHintTextColor(withAlpha(t.mu, 0xCC))
    setTextColor(t.tx)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale)
    typeface = face(true)
    gravity = Gravity.CENTER
    setSingleLine(true)
    includeFontPadding = false
    background = null
    setPadding(px(4f), px(10f), px(4f), px(10f))
    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false }
    // The overlay is not focusable by default so the game keeps its touches. Becomes focusable only while typing.
    setOnTouchListener { v, e ->
      touched()
      if (e.action == MotionEvent.ACTION_DOWN) { windowFocusable(true); v.post { v.requestFocus(); runCatching { imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) } } }
      false
    }
  }

  private fun box(vararg kids: View): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = shape(t.bg, t.rs, t.bw, t.bd)
    kids.forEachIndexed { i, k ->
      if (i == 0) addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) else addView(k)
    }
  }

  private fun card(): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = shape(t.sf, t.r.coerceAtMost(18f), t.bw, t.bd)
    setPadding(px(12f), px(11f), px(12f), px(12f))
  }

  private fun statCell(key: String, caption: String): LinearLayout {
    val v = label("–", 14f, t.tx, true, caps = false)
    stats[key] = v
    return LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      background = shape(t.sf, t.rs, t.bw, t.bd)
      setPadding(px(2f), px(8f), px(2f), px(7f))
      addView(v)
      addView(label(caption, 8.5f, t.mu, true))
    }
  }

  /** Room the panel may use below the bubble, so it never runs off the screen. It scrolls inside this. */
  private fun scrollLimit(): Int {
    val h = realMetrics(ctx).heightPixels
    val top = lp?.y ?: 0
    val chrome = px(52f) + px(8f) + px(46f) + px(24f)
    val room = h - top - chrome
    return max(room, min(px(150f), h / 3))
  }

  private fun build() {
    scale = physDensity()
    stats.clear()
    rawTheme = Hub.engine.config().theme
    t = readable(rawTheme)
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }

    // The bubble is the Densify logo. Everything else lives in the panel it opens.
    val b = ImageView(ctx).apply {
      setImageResource(ctx.resources.getIdentifier("densify_logo", "drawable", ctx.packageName))
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.bg); setStroke(px(2f).coerceAtLeast(1), t.ac) }
      val ring = px(2f).coerceAtLeast(1)
      setPadding(ring, ring, ring, ring)
      outlineProvider = ViewOutlineProvider.BACKGROUND
      clipToOutline = true
      layoutParams = LinearLayout.LayoutParams(px(52f), px(52f))
      isClickable = true
    }
    bubble = b
    attachDrag(b, true)
    col.addView(b)

    val pn = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = shape(withAlpha(t.bg, 0xF4), t.r, t.bw, t.bd)
      setPadding(px(12f), px(10f), px(12f), px(10f))
      visibility = View.GONE
    }
    pn.layoutParams = LinearLayout.LayoutParams(px(272f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) }
    panel = pn

    // Header stays put while the rest scrolls: drag handle, app name and Hide
    val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    header.addView(label("Densify", 11f, t.ac, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; letterSpacing = 0.14f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(label("Hide", 12.5f, t.mu, true).apply {
      setPadding(px(12f), px(8f), px(4f), px(8f))
      setOnClickListener { dismissedKey = Hub.engine.state.value.activePkg ?: "always"; Hub.floatForceUntil = 0L; hide() }
    })
    attachDrag(header, false)
    pn.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(4f) })

    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // Live numbers
    body.addView(row(listOf(statCell("fps", "FPS"), statCell("cpu", "CPU"), statCell("temp", "TEMP"), statCell("ram", "RAM"), statCell("ping", "PING")), 5f), rowParams(0f))

    // Smallest width: value and step buttons. The number is the same one Developer options shows.
    val dc = card()
    dc.addView(label("Smallest width", 10f, t.mu, true).apply { letterSpacing = 0.12f })
    val v = label(currentText(), 36f, t.tx, true, caps = false)
    valueLabel = v
    dc.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2f); bottomMargin = px(2f) })
    dc.addView(label("Higher = smaller UI, more room", 10f, t.mu, false), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })
    dc.addView(row(listOf(
      button("−10", false, 14f) { change(-10) }, button("−5", false, 14f) { change(-5) },
      button("+5", true, 14f) { change(5) }, button("+10", true, 14f) { change(10) },
    ), 5f))
    body.addView(dc, rowParams())

    // Two quick values, typable. Done (or Set) applies the value and remembers it.
    val qa = field("SW", quickValue(1).toString(), { applyQuick(1) }, true).also { quickA = it }
    val qb = field("SW", quickValue(2).toString(), { applyQuick(2) }, true).also { quickB = it }
    val sa = label("Set", 12f, t.ac, true).apply { setPadding(px(8f), px(10f), px(10f), px(10f)); setOnClickListener { applyQuick(1) } }
    val sb = label("Set", 12f, t.ac, true).apply { setPadding(px(8f), px(10f), px(10f), px(10f)); setOnClickListener { applyQuick(2) } }
    body.addView(row(listOf(box(qa, sa), box(qb, sb))), rowParams())

    // Stretch resolution, typed as W*H
    val sc = card()
    sc.addView(label("Stretch resolution", 10f, t.mu, true).apply { letterSpacing = 0.12f; setPadding(0, 0, 0, px(6f)) })
    val size = field("2160*1080", savedSizeText(), { applySize() }, false).also { sizeIn = it }
    sc.addView(box(size), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    sc.addView(row(listOf(button("Stretch", true) { applySize() }, button("Reset", false) { resetSize() })), rowParams())
    val ss = label("", 11f, t.mu, false).apply { setPadding(0, px(6f), 0, 0) }
    sizeStatus = ss
    sc.addView(ss)
    body.addView(sc, rowParams())

    // Screen recorder
    val rc = card()
    rc.addView(label("Screen recorder", 10f, t.mu, true).apply { letterSpacing = 0.12f; setPadding(0, 0, 0, px(6f)) })
    val rb = button("Record", true) { touched(); RecorderService.toggle(ctx); main.postDelayed({ syncRec() }, 900L) }
    val pb = button("Pause", false) { touched(); RecorderService.send(ctx, RecorderService.ACTION_TOGGLE); main.postDelayed({ syncRec() }, 400L) }
    recBtn = rb; pauseBtn = pb
    rc.addView(row(listOf(rb, pb)))
    body.addView(rc, rowParams())

    body.addView(row(listOf(
      button("Optimize", true) { optimize() },
      button("Restore all", false) { touched(); Hub.scope.launch { Hub.engine.restoreAll(); pending = null; main.post { refreshLabels() } } },
    )), rowParams())

    val sv = MaxHeightScrollView(ctx) { scrollLimit() }.apply {
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
      addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    pn.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    col.addView(pn)

    root = col
    val saved = Hub.prefs
    val m = realMetrics(ctx)
    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      type,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
      x = saved.getInt("floatX", px(8f)).coerceIn(0, (m.widthPixels - px(52f)).coerceAtLeast(0))
      y = saved.getInt("floatY", px(160f)).coerceIn(0, (m.heightPixels - px(52f)).coerceAtLeast(0))
    }
  }

  private fun rowParams(top: Float = 8f) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }

  private fun row(items: List<View>, gapDp: Float = 6f): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    items.forEachIndexed { i, tv ->
      addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(gapDp) })
    }
  }

  /** The engine knows about every change, so its value is the truth. Falls back to what Android reports for the screen. */
  private fun currentSw(): Int = Hub.engine.state.value.appliedSw ?: ctx.resources.configuration.smallestScreenWidthDp
  private fun currentText(): String = currentSw().toString()

  /** The smallest width the phone ships with, used to suggest quick values. */
  private fun phoneSw(): Int {
    val pw = Hub.prefs.getInt("physW", 0); val ph = Hub.prefs.getInt("physH", 0); val pd = Hub.prefs.getInt("physDpi", 0)
    return if (pw > 0 && ph > 0 && pd > 0) Sw.fromDpi(min(pw, ph), pd) else ctx.resources.configuration.smallestScreenWidthDp
  }

  private fun quickValue(i: Int): Int {
    val saved = Hub.prefs.getInt("floatSw$i", 0)
    if (saved in Engine.SW_MIN..Engine.SW_MAX) return saved
    Hub.engine.config().quick.getOrNull(i - 1)?.let { if (it in Engine.SW_MIN..Engine.SW_MAX) return it }
    return ((phoneSw() * (if (i == 1) 1.25 else 1.5)) / 10).toInt() * 10
  }

  private fun savedSizeText(): String {
    val w = Hub.prefs.getInt("stretchW", 0); val h = Hub.prefs.getInt("stretchH", 0)
    return if (w > 0 && h > 0) "$w*$h" else ""
  }

  /** Cheap: only copies what the engine already knows. Never talks to the phone and never overrides a tap in progress. */
  private fun syncValue() {
    if (pending != null) return
    val s = currentText()
    if (valueLabel?.text?.toString() != s) valueLabel?.text = s
  }

  private fun syncRec() {
    val st = RecState.state
    val sec = RecState.elapsedMs() / 1000
    val clock = "%d:%02d".format(sec / 60, sec % 60)
    recBtn?.let { b ->
      b.text = txt(if (st == "idle") "Record" else "Stop · $clock")
      b.background = if (st == "idle") shape(t.ac, t.rs) else shape(0xFFD94A4A.toInt(), t.rs)
      b.setTextColor(if (st == "idle") t.on else WHITE)
    }
    pauseBtn?.let { b ->
      b.text = txt(if (st == "paused") "Resume" else "Pause")
      b.alpha = if (st == "idle") 0.4f else 1f
      b.isEnabled = st != "idle"
    }
  }

  private fun updateStats() {
    val s = Perf.latest
    stats["fps"]?.text = if (s.fps >= 0) s.fps.toString() else "–"
    stats["cpu"]?.text = if (s.cpu >= 0) "${s.cpu}%" else "–"
    stats["temp"]?.let { tv ->
      tv.text = if (s.tempC > 0f) "${s.tempC.roundToInt()}°" else "–"
      tv.setTextColor(if (s.tempC >= 48f) 0xFFFF7A7A.toInt() else if (s.tempC >= 42f) 0xFFFFC27A.toInt() else t.tx)
    }
    stats["ram"]?.text = if (s.ramPct >= 0) "${s.ramPct}%" else "–"
    stats["ping"]?.text = if (s.pingMs >= 0) s.pingMs.toString() else "–"
  }

  /** Reads the screen size once (when the panel opens or after a size change), not on a timer. */
  private fun loadSize() {
    Hub.scope.launch {
      val z = Hub.adb.readSize().getOrNull()
      val txt = if (z == null) "" else if (z.overrideText != null) "Now ${z.ovrW}×${z.ovrH}  (screen ${z.physW}×${z.physH})" else "Now ${z.physW}×${z.physH} (normal)"
      main.post { sizeStatus?.text = txt }
    }
  }

  private fun refreshLabels() {
    syncValue()
    if (expanded) loadSize()
  }

  // ---------- actions ----------

  private fun touched() {
    // While previewing, any interaction keeps the panel on screen for another 15 seconds.
    val now = SystemClock.elapsedRealtime()
    if (now < Hub.floatForceUntil) Hub.floatForceUntil = now + 15_000L
  }

  private fun windowFocusable(on: Boolean) {
    val p = lp ?: return
    val r = root ?: return
    p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    runCatching { wm.updateViewLayout(r, p) }
  }

  private fun endTyping() {
    val r = root ?: return
    runCatching { imm.hideSoftInputFromWindow(r.windowToken, 0) }
    quickA?.clearFocus(); quickB?.clearFocus(); sizeIn?.clearFocus()
    windowFocusable(false)
  }

  private fun toast(msg: String) { main.post { sizeStatus?.text = msg } }

  /**
   * Smallest-width steps. The number changes the instant it is tapped and quick taps are merged into one change, so it never lags or fights itself.
   * The change goes through the engine, which keeps every screen (and the game profile) in agreement.
   */
  private fun change(delta: Int) {
    touched()
    val base = pending ?: currentSw()
    val next = (base + delta).coerceIn(Engine.SW_MIN, Engine.SW_MAX)
    pending = next
    valueLabel?.text = next.toString()
    if (!busy.compareAndSet(false, true)) return
    Hub.scope.launch {
      try {
        while (true) {
          delay(140)
          val target = pending ?: break
          val r = Hub.engine.adjustSw(target)
          if (pending == target) pending = null
          main.post {
            if (r.isSuccess) { if (pending == null) valueLabel?.text = (r.getOrNull() ?: target).toString() }
            else { pending = null; valueLabel?.text = currentText(); sizeStatus?.text = "Couldn't set it. Check the connection." }
          }
          if (pending == null) break
        }
      } finally { busy.set(false) }
      if (pending != null) main.post { change(0) }
    }
  }

  private fun applyQuick(i: Int) {
    touched()
    val et = if (i == 1) quickA else quickB
    val v = et?.text?.toString()?.trim()?.toIntOrNull()
    if (v == null || v !in Engine.SW_MIN..Engine.SW_MAX) { toast("Smallest width must be ${Engine.SW_MIN} to ${Engine.SW_MAX}"); return }
    Hub.prefs.edit().putInt("floatSw$i", v).apply()
    endTyping()
    pending = v
    valueLabel?.text = v.toString()
    Hub.scope.launch {
      val r = Hub.engine.adjustSw(v)
      if (pending == v) pending = null
      main.post {
        if (r.isFailure) { valueLabel?.text = currentText(); sizeStatus?.text = "Couldn't set it. Check the connection." }
        else r.getOrNull()?.let { valueLabel?.text = it.toString() }
      }
    }
  }

  private fun applySize() {
    touched()
    val wh = parseSize(sizeIn?.text?.toString() ?: "")
    if (wh == null) { toast("Type it as width*height, e.g. 2160*1080"); return }
    endTyping()
    Hub.scope.launch {
      val ok = Hub.engine.stretchNow(wh.first, wh.second)
      main.post { if (!ok) toast("Couldn't change the resolution.") else refreshLabels() }
    }
  }

  private fun resetSize() {
    touched(); endTyping()
    Hub.scope.launch { Hub.engine.unstretch(); main.post { refreshLabels() } }
  }

  private fun optimize() {
    touched()
    toast("Cleaning RAM…")
    Hub.scope.launch {
      val msg = runCatching { Hub.engine.optimizeNow() }.getOrDefault("Couldn't reach the phone.")
      toast(msg)
    }
  }

  private fun setExpanded(on: Boolean) {
    expanded = on
    panel?.visibility = if (on) View.VISIBLE else View.GONE
    if (on) {
      // Keep the whole panel on screen: if the bubble sits low, slide it up so there is room to read, and the rest scrolls.
      val p = lp
      if (p != null) {
        val h = realMetrics(ctx).heightPixels
        val want = (h * 0.5f).toInt()
        if (h - p.y < want) p.y = max(0, h - want)
      }
      Perf.start()
      Perf.acquire("panel")
      updateStats()
      syncRec()
      loadSize()
      main.removeCallbacks(ticker)
      main.postDelayed(ticker, 1_000L)
    } else {
      Perf.release("panel")
      main.removeCallbacks(ticker)
      endTyping()
    }
    val p = lp
    val r = root
    if (p != null && r != null) runCatching { wm.updateViewLayout(r, p) }
  }

  // ---------- dragging ----------

  @SuppressLint("ClickableViewAccessibility")
  private fun attachDrag(v: View, toggleOnTap: Boolean) {
    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
    v.setOnTouchListener { _, e ->
      val p = lp ?: return@setOnTouchListener false
      when (e.action) {
        MotionEvent.ACTION_DOWN -> { startX = p.x; startY = p.y; touchX = e.rawX; touchY = e.rawY; moved = false; true }
        MotionEvent.ACTION_MOVE -> {
          val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
          if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
          if (moved) {
            val m = realMetrics(ctx)
            p.x = (startX + dx).coerceIn(0, (m.widthPixels - px(52f)).coerceAtLeast(0))
            p.y = (startY + dy).coerceIn(0, (m.heightPixels - px(52f)).coerceAtLeast(0))
            root?.let { r -> runCatching { wm.updateViewLayout(r, p) } }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          touched()
          if (moved) Hub.prefs.edit().putInt("floatX", p.x).putInt("floatY", p.y).apply() else if (toggleOnTap) toggle()
          true
        }
        else -> false
      }
    }
  }

  private fun toggle() {
    setExpanded(!expanded)
    syncValue()
  }
}
