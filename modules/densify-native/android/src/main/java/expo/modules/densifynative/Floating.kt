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
import kotlinx.coroutines.launch

/**
 * A draggable bubble that expands into a small control panel, drawn over the game.
 * Built from plain Views. Sizes are in "physical dp" so the panel keeps its size when Densify changes the screen density.
 */
class FloatingPanel(private val ctx: Context) {
  private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())

  private var root: LinearLayout? = null
  private var lp: WindowManager.LayoutParams? = null
  private var bubble: ImageView? = null
  private var panel: LinearLayout? = null
  private var valueLabel: TextView? = null
  private var scale = 1f
  private var expanded = false

  /** The game (or "always") the user dismissed the panel for. The service skips showing it again until that changes. */
  @Volatile var dismissedKey: String? = null
  @Volatile var visible = false
    private set

  fun show(startOpen: Boolean = false) {
    main.post {
      if (visible) { refreshLabels(); return@post }
      val canDraw = Build.VERSION.SDK_INT < 23 || runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
      if (!canDraw) return@post
      build()
      val r = root ?: return@post
      val p = lp ?: return@post
      runCatching { wm.addView(r, p); visible = true }
      if (startOpen) { expanded = true; panel?.visibility = View.VISIBLE }
      refreshLabels()
    }
  }

  fun hide() {
    main.post {
      if (!visible) return@post
      root?.let { v -> runCatching { wm.removeView(v) } }
      visible = false
      expanded = false
      root?.let { r -> runCatching { imm.hideSoftInputFromWindow(r.windowToken, 0) } }
      root = null; panel = null; bubble = null; valueLabel = null; quickA = null; quickB = null; sizeIn = null; sizeStatus = null
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

  private fun button(text: String, onClick: () -> Unit): TextView = label(text, 14f, Color.WHITE, true).apply {
    background = shape(Color.parseColor("#2B3138"), 10f)
    setPadding(px(6f), px(10f), px(6f), px(10f))
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
    background = shape(Color.parseColor("#1B2026"), 10f, 1f, Color.parseColor("#3A424B"))
    kids.forEachIndexed { i, k ->
      if (i == 0) addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) else addView(k)
    }
  }

  private fun build() {
    scale = physDensity()
    val ac = Color.parseColor("#5CBDFF")
    val dim = Color.parseColor("#8B939E")
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }

    // The bubble is the Densify logo. The density value lives in the panel it opens.
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
    attachDrag(b)
    col.addView(b)

    val pn = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = shape(Color.parseColor("#F2101418"), 18f, 1f, Color.parseColor("#3A424B"))
      setPadding(px(12f), px(12f), px(12f), px(12f))
      visibility = View.GONE
    }
    pn.layoutParams = LinearLayout.LayoutParams(px(236f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) }
    panel = pn

    // Density: value and two step buttons
    pn.addView(label("DENSITY", 10.5f, dim, true))
    val v = label(currentText(), 30f, Color.WHITE, true)
    valueLabel = v
    pn.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2f); bottomMargin = px(8f) })
    pn.addView(row(listOf(button("-5") { change(-5) }, button("+5") { change(5) })))

    // Two quick values, typable. Done (or Set) applies the value and remembers it.
    val qa = field("DPI", quickValue(1).toString(), { applyQuick(1) }, true).also { quickA = it }
    val qb = field("DPI", quickValue(2).toString(), { applyQuick(2) }, true).also { quickB = it }
    val sa = label("Set", 12f, ac, true).apply { setPadding(px(8f), px(10f), px(10f), px(10f)); setOnClickListener { applyQuick(1) } }
    val sb = label("Set", 12f, ac, true).apply { setPadding(px(8f), px(10f), px(10f), px(10f)); setOnClickListener { applyQuick(2) } }
    pn.addView(row(listOf(box(qa, sa), box(qb, sb))), rowParams())

    // Stretch resolution, typed as W*H
    pn.addView(label("STRETCH RESOLUTION", 10.5f, dim, true).apply { setPadding(0, px(14f), 0, px(4f)) })
    val size = field("2160*1080", savedSizeText(), { applySize() }, false).also { sizeIn = it }
    pn.addView(box(size), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    pn.addView(row(listOf(button("Stretch") { applySize() }, button("Reset") { resetSize() })), rowParams())
    val ss = label("", 11f, dim).apply { setPadding(0, px(6f), 0, 0) }
    sizeStatus = ss
    pn.addView(ss)

    val bottom = row(listOf(
      label("Restore all", 12.5f, ac, true).apply { setPadding(0, px(10f), 0, px(2f)); setOnClickListener { touched(); Hub.scope.launch { Hub.engine.restoreAll(); main.post { refreshLabels() } } } },
      label("Hide", 12.5f, dim, true).apply { setPadding(0, px(10f), 0, px(2f)); setOnClickListener { dismissedKey = Hub.engine.state.value.activePkg ?: "always"; Hub.floatForceUntil = 0L; hide() } },
    ))
    pn.addView(bottom)
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

  private fun row(items: List<View>): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    items.forEachIndexed { i, tv ->
      addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(6f) })
    }
  }

  /** Falls back to the current system density so the bubble never shows a placeholder word. */
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

  private fun refreshLabels() {
    val t = currentText()
    valueLabel?.text = t
    if (panel?.visibility == View.VISIBLE) {
      Hub.scope.launch {
        val z = Hub.adb.readSize().getOrNull()
        val txt = if (z == null) "" else if (z.overrideText != null) "Now ${z.ovrW}×${z.ovrH}  (screen ${z.physW}×${z.physH})" else "Now ${z.physW}×${z.physH} (normal)"
        main.post { sizeStatus?.text = txt }
      }
    }
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

  private fun change(delta: Int) {
    touched()
    Hub.scope.launch {
      val cur = Hub.adb.readDensity().getOrNull()?.effective ?: return@launch
      applyDpi((cur + delta).coerceIn(72, 1000))
    }
  }

  private fun applyQuick(i: Int) {
    touched()
    val et = if (i == 1) quickA else quickB
    val v = et?.text?.toString()?.trim()?.toIntOrNull()
    if (v == null || v !in 72..1000) { toast("DPI must be 72 to 1000"); return }
    Hub.prefs.edit().putInt("floatQuick$i", v).apply()
    endTyping()
    Hub.scope.launch { applyDpi(v) }
  }

  private suspend fun applyDpi(dpi: Int) {
    val r = Hub.adb.setDensity(dpi)
    if (r.isSuccess) {
      Hub.log("Floating panel set $dpi DPI")
      main.post { valueLabel?.text = dpi.toString() }
    } else toast("Couldn't set DPI. Check connection.")
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

  // ---------- dragging ----------

  @SuppressLint("ClickableViewAccessibility")
  private fun attachDrag(v: View) {
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
          if (moved) Hub.prefs.edit().putInt("floatX", p.x).putInt("floatY", p.y).apply() else toggle()
          true
        }
        else -> false
      }
    }
  }

  private fun toggle() {
    expanded = !expanded
    if (!expanded) endTyping()
    panel?.visibility = if (expanded) View.VISIBLE else View.GONE
    refreshLabels()
    val p = lp
    val r = root
    if (p != null && r != null) runCatching { wm.updateViewLayout(r, p) }
  }
}
