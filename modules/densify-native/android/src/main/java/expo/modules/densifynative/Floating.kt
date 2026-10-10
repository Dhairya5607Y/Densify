package expo.modules.densifynative

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch

/**
 * A draggable bubble that expands into a small control panel, drawn over the game.
 * Built from plain Views. Sizes are in "physical dp" so the panel keeps its size when Densify changes the screen density.
 */
class FloatingPanel(private val ctx: Context) {
  private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())

  private var root: LinearLayout? = null
  private var lp: WindowManager.LayoutParams? = null
  private var bubble: TextView? = null
  private var panel: LinearLayout? = null
  private var valueLabel: TextView? = null
  private var stretchBtn: TextView? = null
  private var scale = 1f
  private var expanded = false

  /** The game (or "always") the user dismissed the panel for. The service skips showing it again until that changes. */
  @Volatile var dismissedKey: String? = null
  @Volatile var visible = false
    private set

  fun show() {
    main.post {
      if (visible) { refreshLabels(); return@post }
      val canDraw = runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
      if (!canDraw) return@post
      build()
      val r = root ?: return@post
      val p = lp ?: return@post
      runCatching { wm.addView(r, p); visible = true }
      refreshLabels()
    }
  }

  fun hide() {
    main.post {
      if (!visible) return@post
      root?.let { v -> runCatching { wm.removeView(v) } }
      visible = false
      expanded = false
      root = null; panel = null; bubble = null; valueLabel = null; stretchBtn = null
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

  private fun build() {
    scale = physDensity()
    val ac = Color.parseColor("#5CBDFF")
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }

    val b = label(currentText(), 13f, Color.WHITE, true).apply {
      background = shape(Color.parseColor("#E6101418"), 26f, 2f, ac)
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

    pn.addView(label("DISPLAY DENSITY", 10.5f, Color.parseColor("#8B939E"), true))
    val v = label(currentText(), 34f, Color.WHITE, true)
    valueLabel = v
    pn.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2f); bottomMargin = px(8f) })

    pn.addView(row(listOf("-10" to -10, "-1" to -1, "+1" to 1, "+10" to 10).map { (t, d) -> button(t) { change(d) } }))

    val quick = Hub.engine.config().quick.take(4)
    if (quick.isNotEmpty()) {
      pn.addView(row(quick.map { q -> button(q.toString()) { setTo(q) } }), rowParams())
    }

    val restore = button("Restore") { Hub.scope.launch { Hub.engine.restoreAll(); main.post { refreshLabels() } } }
    val st = button("Stretch") { Hub.scope.launch { Hub.engine.toggleStretch(); main.post { refreshLabels() } } }
    stretchBtn = st
    pn.addView(row(listOf(restore, st)), rowParams())

    val close = label("Hide for this game", 12f, Color.parseColor("#8B939E")).apply {
      setPadding(0, px(10f), 0, px(2f))
      setOnClickListener { dismissedKey = Hub.engine.state.value.activePkg ?: "always"; hide() }
    }
    pn.addView(close)
    col.addView(pn)

    root = col
    val saved = Hub.prefs
    lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = saved.getInt("floatX", px(8f))
      y = saved.getInt("floatY", px(160f))
    }
  }

  private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) }

  private fun row(items: List<TextView>): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    items.forEachIndexed { i, tv ->
      addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(6f) })
    }
  }

  private fun currentText(): String = (Hub.engine.state.value.appliedDpi ?: Hub.prefs.getInt("physDpi", 0)).let { if (it > 0) it.toString() else "DPI" }

  private fun refreshLabels() {
    val t = currentText()
    bubble?.text = t
    valueLabel?.text = t
    val s = Hub.engine.state.value
    val hasStretch = s.activePkg != null && Hub.engine.config().profiles.any { it.pkg == s.activePkg && it.stretch.on }
    stretchBtn?.visibility = if (hasStretch) View.VISIBLE else View.GONE
    stretchBtn?.text = if (s.stretched) "Stretch: on" else "Stretch: off"
  }

  // ---------- actions ----------

  private fun change(delta: Int) {
    Hub.scope.launch {
      val cur = Hub.adb.readDensity().getOrNull()?.effective ?: return@launch
      applyDpi((cur + delta).coerceIn(72, 1000))
    }
  }

  private fun setTo(dpi: Int) { Hub.scope.launch { applyDpi(dpi) } }

  private suspend fun applyDpi(dpi: Int) {
    val r = Hub.adb.setDensity(dpi)
    if (r.isSuccess) {
      Hub.log("Floating panel set $dpi DPI")
      main.post { bubble?.text = dpi.toString(); valueLabel?.text = dpi.toString() }
    }
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
          if (moved) Hub.prefs.edit().putInt("floatX", p.x).putInt("floatY", p.y).apply() else toggle()
          true
        }
        else -> false
      }
    }
  }

  private fun toggle() {
    expanded = !expanded
    panel?.visibility = if (expanded) View.VISIBLE else View.GONE
    refreshLabels()
    val p = lp
    val r = root
    if (p != null && r != null) runCatching { wm.updateViewLayout(r, p) }
  }
}
