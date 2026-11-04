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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * A draggable bubble that expands into a control panel, drawn over the game: live FPS / CPU / temperature / RAM / ping,
 * DPI steps, quick values, stretch resolution and one-tap optimize. Built from plain Views.
 * Sizes are in "physical dp" so the panel keeps its size when Densify changes the screen density.
 */
class FloatingPanel(private val ctx: Context) {
  private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())

  private var root: LinearLayout? = null
  private var lp: WindowManager.LayoutParams? = null
  private var bubble: ImageView? = null
  private var panel: LinearLayout? = null
  private var valueLabel: TextView? = null
  private val stats = HashMap<String, TextView>()
  private var scale = 1f
  private var expanded = false

  /** The DPI the user has tapped to but that has not been applied yet. While set, the label shows it and nothing overwrites it. */
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
      root = null; panel = null; bubble = null; valueLabel = null; quickA = null; quickB = null; sizeIn = null; sizeStatus = null
      stats.clear()
    }
  }

  // ---------- building ----------

  private fun physDensity(): Float {
    val phys = Hub.prefs.getInt("physDpi", 0)
    val dpi = if (phys > 0) phys else ctx.resources.displayMetrics.densityDpi
    return dpi / 160f
  }

  private fun px(dp: Float): Int = (dp * scale).toInt()

  private fun label(text: String, sizeDp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDp * scale)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    includeFontPadding = false
  }

  private fun shape(fill: Int, radiusDp: Float, strokeDp: Float = 0f, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radiusDp * scale
    if (strokeDp > 0f) setStroke(px(strokeDp).coerceAtLeast(1), stroke)
  }

  private fun button(text: String, tint: String, sizeDp: Float = 14f, onClick: () -> Unit): TextView = label(text, sizeDp, Color.WHITE, true).apply {
    background = shape(Color.parseColor(tint), 12f)
    setPadding(px(6f), px(12f), px(6f), px(12f))
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
    setHintTextColor(Color.parseColor("#6B737D"))
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale)
    typeface = Typeface.DEFAULT_BOLD
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
    background = shape(Color.parseColor("#10151B"), 12f, 1f, Color.parseColor("#2A333D"))
    kids.forEachIndexed { i, k ->
      if (i == 0) addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) else addView(k)
    }
  }

  private fun card(): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = shape(Color.parseColor("#141A21"), 16f)
    setPadding(px(12f), px(11f), px(12f), px(12f))
  }

  private fun statCell(key: String, caption: String): LinearLayout {
    val v = label("–", 15f, Color.WHITE, true)
    stats[key] = v
    return LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      background = shape(Color.parseColor("#141A21"), 12f)
      setPadding(px(2f), px(8f), px(2f), px(7f))
      addView(v)
      addView(label(caption, 8.5f, Color.parseColor("#7E8791"), true))
    }
  }

  private fun build() {
    scale = physDensity()
    stats.clear()
    val ac = Color.parseColor("#5CBDFF")
    val dim = Color.parseColor("#8B939E")
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }

    // The bubble is the Densify logo. Everything else lives in the panel it opens.
    val b = ImageView(ctx).apply {
      setImageResource(ctx.resources.getIdentifier("densify_logo", "drawable", ctx.packageName))
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#101418")); setStroke(px(2f).coerceAtLeast(1), ac) }
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
      background = shape(Color.parseColor("#F00A0E13"), 22f, 1f, Color.parseColor("#2A333D"))
      setPadding(px(12f), px(10f), px(12f), px(12f))
      visibility = View.GONE
    }
    pn.layoutParams = LinearLayout.LayoutParams(px(268f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) }
    panel = pn

    // Header: drag handle with the app name and Hide
    val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    header.addView(label("DENSIFY", 11f, ac, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; letterSpacing = 0.14f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(label("Hide", 12.5f, dim, true).apply {
      setPadding(px(12f), px(8f), px(4f), px(8f))
      setOnClickListener { dismissedKey = Hub.engine.state.value.activePkg ?: "always"; Hub.floatForceUntil = 0L; hide() }
    })
    attachDrag(header, false)
    pn.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })

    // Live numbers
    pn.addView(row(listOf(statCell("fps", "FPS"), statCell("cpu", "CPU"), statCell("temp", "TEMP"), statCell("ram", "RAM"), statCell("ping", "PING")), 5f))

    // Density: value and two step buttons
    val dc = card()
    dc.addView(label("DENSITY", 10f, dim, true).apply { letterSpacing = 0.12f })
    val v = label(currentText(), 38f, Color.WHITE, true)
    valueLabel = v
    dc.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2f); bottomMargin = px(10f) })
    dc.addView(row(listOf(button("−5", "#26303B", 18f) { change(-5) }, button("+5", "#1E3A52", 18f) { change(5) })))
    pn.addView(dc, rowParams())

    // Two quick values, typable. Done (or Set) applies the value and remembers it.
    val qa = field("DPI", quickValue(1).toString(), { applyQuick(1) }, true).also { quickA = it }
    val qb = field("DPI", quickValue(2).toString(), { applyQuick(2) }, true).also { quickB = it }
    val sa = label("Set", 12f, ac, true).apply { setPadding(px(8f), px(10f), px(10f), px(10f)); setOnClickListener { applyQuick(1) } }
    val sb = label("Set", 12f, ac, true).apply { setPadding(px(8f), px(10f), px(10f), px(10f)); setOnClickListener { applyQuick(2) } }
    pn.addView(row(listOf(box(qa, sa), box(qb, sb))), rowParams())

    // Stretch resolution, typed as W*H
    val sc = card()
    sc.addView(label("STRETCH RESOLUTION", 10f, dim, true).apply { letterSpacing = 0.12f; setPadding(0, 0, 0, px(6f)) })
    val size = field("2160*1080", savedSizeText(), { applySize() }, false).also { sizeIn = it }
    sc.addView(box(size), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    sc.addView(row(listOf(button("Stretch", "#1E3A52") { applySize() }, button("Reset", "#26303B") { resetSize() })), rowParams())
    val ss = label("", 11f, dim).apply { setPadding(0, px(6f), 0, 0) }
    sizeStatus = ss
    sc.addView(ss)
    pn.addView(sc, rowParams())

    pn.addView(row(listOf(
      button("Optimize", "#1E3A52") { optimize() },
      button("Restore all", "#26303B") { touched(); Hub.scope.launch { Hub.engine.restoreAll(); pending = null; main.post { refreshLabels() } } },
    )), rowParams())
    col.addView(pn)

    root = col
    val saved = Hub.prefs
    val m = ctx.resources.displayMetrics
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

  private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) }

  private fun row(items: List<View>, gapDp: Float = 6f): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    items.forEachIndexed { i, tv ->
      addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(gapDp) })
    }
  }

  /** The engine now knows about every manual change, so its value is the truth. Falls back to the system density. */
  private fun currentText(): String = (Hub.engine.state.value.appliedDpi ?: ctx.resources.displayMetrics.densityDpi).toString()

  private fun quickValue(i: Int): Int {
    val saved = Hub.prefs.getInt("floatQuick$i", 0)
    if (saved in 72..1000) return saved
    val cfg = Hub.engine.config().quick
    cfg.getOrNull(i - 1)?.let { if (it in 72..1000) return it }
    val phys = Hub.prefs.getInt("physDpi", 0).let { if (it > 0) it else ctx.resources.displayMetrics.densityDpi }
    return ((phys * (if (i == 1) 0.8 else 0.65)) / 5).toInt() * 5
  }

  private fun savedSizeText(): String {
    val w = Hub.prefs.getInt("stretchW", 0); val h = Hub.prefs.getInt("stretchH", 0)
    return if (w > 0 && h > 0) "$w*$h" else ""
  }

  /** Cheap: only copies what the engine already knows. Never talks to the phone and never overrides a tap in progress. */
  private fun syncValue() {
    if (pending != null) return
    val t = currentText()
    if (valueLabel?.text?.toString() != t) valueLabel?.text = t
  }

  private fun updateStats() {
    val s = Perf.latest
    stats["fps"]?.text = if (s.fps >= 0) s.fps.toString() else "–"
    stats["cpu"]?.text = if (s.cpu >= 0) "${s.cpu}%" else "–"
    stats["temp"]?.let { tv ->
      tv.text = if (s.tempC > 0f) "${s.tempC.roundToInt()}°" else "–"
      tv.setTextColor(Color.parseColor(if (s.tempC >= 48f) "#FF7A7A" else if (s.tempC >= 42f) "#FFC27A" else "#FFFFFF"))
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
    val now = android.os.SystemClock.elapsedRealtime()
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
   * +5 / -5. The number changes the instant it is tapped and quick taps are merged into one change, so it never lags or fights itself.
   * The change goes through the engine, which keeps every screen (and the game profile) in agreement.
   */
  private fun change(delta: Int) {
    touched()
    val base = pending ?: Hub.engine.state.value.appliedDpi ?: ctx.resources.displayMetrics.densityDpi
    val next = (base + delta).coerceIn(72, 1000)
    pending = next
    valueLabel?.text = next.toString()
    if (!busy.compareAndSet(false, true)) return
    Hub.scope.launch {
      try {
        while (true) {
          delay(140)
          val target = pending ?: break
          val r = Hub.engine.adjustDpi(target)
          if (pending == target) pending = null
          main.post {
            if (r.isSuccess) { if (pending == null) valueLabel?.text = target.toString() }
            else { pending = null; valueLabel?.text = currentText(); sizeStatus?.text = "Couldn't set DPI. Check connection." }
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
    if (v == null || v !in 72..1000) { toast("DPI must be 72 to 1000"); return }
    Hub.prefs.edit().putInt("floatQuick$i", v).apply()
    endTyping()
    pending = v
    valueLabel?.text = v.toString()
    Hub.scope.launch {
      val r = Hub.engine.adjustDpi(v)
      if (pending == v) pending = null
      main.post { if (r.isFailure) { valueLabel?.text = currentText(); sizeStatus?.text = "Couldn't set DPI. Check connection." } }
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
      Perf.start()
      Perf.acquire("panel")
      updateStats()
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
            val m = ctx.resources.displayMetrics
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
