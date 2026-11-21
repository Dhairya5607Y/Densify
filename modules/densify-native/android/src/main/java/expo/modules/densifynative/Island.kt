package expo.modules.densifynative

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Display
import android.view.DisplayCutout
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the monitor service needs from any floating panel, so it can swap the bubble and the island. */
interface PanelUi {
  var dismissedKey: String?
  fun show(startOpen: Boolean)
  fun hide()
  fun refreshTheme(n: PanelTheme)
}

private const val I_BLACK = 0xFF000000.toInt()
private const val I_WHITE = 0xFFFFFFFF.toInt()
private const val I_BLUE = 0xFF8CC4F5.toInt()

private fun islandLum(c: Int): Double {
  fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
  return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * The dynamic island: a plain black pill that sits over the camera. Tap it and it springs open into a card with the
 * smallest-width steps, Restore and Stretch. It closes again after a few seconds, or when you touch the game.
 * One view is the whole window, so the pill and the card are the same black shape growing and shrinking.
 * Sizes are in "physical dp" so the island keeps its size when Densify changes the screen density.
 */
class IslandPanel(private val ctx: Context) : PanelUi {
  private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())

  private var host: FrameLayout? = null
  private var lp: WindowManager.LayoutParams? = null
  private var back: GradientDrawable? = null
  private var sheen: View? = null
  private var nameLabel: TextView? = null
  private var content: LinearLayout? = null
  private var iconView: ImageView? = null
  private var valueLabel: TextView? = null
  private var captionLabel: TextView? = null
  private var stretchBtn: TextView? = null

  @Volatile override var dismissedKey: String? = null
  @Volatile var visible = false
    private set

  private var theme = PanelTheme()
  private var rawTheme = PanelTheme()
  private var scale = 1f
  private var sz = 1f
  private var cs = 1f
  private var expanded = false
  private var progress = 0f
  private var anim: ValueAnimator? = null
  private var autoOpened = false
  private var lastW = 0
  private var lastH = 0
  private var shownPkg: String? = null
  private var shownLabel: String? = null

  // Live activities: the island shows what is happening without being opened
  private var builtRec = false
  private var builtClip = false
  private var bannerOn = false
  private var bp = 0f
  private var bw = 0
  private var bannerAnim: ValueAnimator? = null
  private var lastFront: String? = null
  private var lowShown = false

  /** The smallest width the user has tapped to but that has not been applied yet. */
  @Volatile private var pending: Int? = null
  private val busy = AtomicBoolean(false)

  // Colours follow the island colour, so text is readable on black and on a light island alike
  private var cardColor = I_BLACK
  private var fg = I_WHITE
  private var muted = 0x99FFFFFF.toInt()
  private var chip = 0x2EFFFFFF.toInt()
  private var accent = I_BLUE
  private var onAccent = 0xFF06131F.toInt()

  // Geometry, in pixels. C = collapsed pill, E = expanded card.
  private var side = false
  /** Phone turned sideways while the island is still on Top: the open card becomes one wide, short row so it covers less of the game. */
  private var compact = false
  /** Where the user dragged the island to (percent of screen width and height), only used while it is movable. */
  private var free: Pair<Float, Float>? = null
  private var shownFree = ""
  private var sideLeft = false
  private var cw = 0
  private var ch = 0
  private var ew = 0
  private var eh = 0
  private var xC = 0
  private var xE = 0
  private var yC = 0
  private var yE = 0
  private var edgeC = 0
  private var edgeE = 0

  private val ticker = object : Runnable {
    override fun run() {
      if (!visible || !expanded) return
      syncValue(); syncStretch(); syncGame()
      main.postDelayed(this, 1_000L)
    }
  }
  private val collapseRun = Runnable {
    if (expanded && SystemClock.elapsedRealtime() >= Hub.floatForceUntil) setExpanded(false)
  }
  private val recheck = Runnable {
    if (visible) { computeLayout(); render() }
  }
  private val restoreCaption = Runnable { paintCaption() }
  private val bannerEnd = Runnable { animateBanner(0f) }
  private var bannerPkg: String? = null
  private val gameBanner = Runnable {
    val pkg = bannerPkg ?: return@Runnable
    showBanner("${labelOf(pkg) ?: "Game"} · ${currentSw()} sw", fg)
  }
  private val liveTick = object : Runnable {
    override fun run() {
      if (!visible) return
      val rec = RecState.state != "idle"
      if (rec != builtRec || (ReplayState.state == "buffering") != builtClip) { rebuild(); return }
      if (rec && !expanded && !bannerOn) nameLabel?.text = recText()
      watchGame()
      watchBattery()
      main.postDelayed(this, 1_000L)
    }
  }

  // ---------- show / hide ----------

  override fun show(startOpen: Boolean) {
    main.post {
      if (visible) {
        if (startOpen && !autoOpened) { autoOpened = true; setExpanded(true) }
        return@post
      }
      val canDraw = Build.VERSION.SDK_INT < 23 || runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
      if (!canDraw) return@post
      attach()
      if (startOpen) { autoOpened = true; main.postDelayed({ setExpanded(true) }, 250L) }
    }
  }

  override fun hide() {
    main.post {
      if (!visible) return@post
      detach()
      autoOpened = false
    }
  }

  /** Rebuilds when the theme, colour, size or position changed in the app, or when the screen turned. */
  override fun refreshTheme(n: PanelTheme) {
    main.post {
      val m = realMetrics(ctx)
      val turned = visible && (m.widthPixels != lastW || m.heightPixels != lastH || readFreeRaw() != shownFree)
      if (n == rawTheme && !turned) return@post
      if (!visible) { rawTheme = n; return@post }
      val wasOpen = expanded
      detach()
      attach()
      if (wasOpen) setExpanded(true, animate = false)
    }
  }

  private fun attach() {
    builtRec = RecState.state != "idle"
    builtClip = ReplayState.state == "buffering"
    build()
    val h = host ?: return
    val p = lp ?: return
    computeLayout()
    progress = 0f
    expanded = false
    render()
    runCatching { wm.addView(h, p); visible = true }
    // The camera cut-out is not always known the instant the window opens, so look again shortly after.
    main.postDelayed(recheck, 250L)
    main.postDelayed(recheck, 1_000L)
    main.postDelayed(liveTick, 1_000L)
  }

  private fun detach() {
    anim?.let { a -> anim = null; a.cancel() }
    main.removeCallbacks(ticker); main.removeCallbacks(collapseRun); main.removeCallbacks(recheck); main.removeCallbacks(restoreCaption)
    main.removeCallbacks(liveTick); main.removeCallbacks(bannerEnd); main.removeCallbacks(gameBanner)
    bannerAnim?.let { a -> bannerAnim = null; a.cancel() }
    bannerOn = false; bp = 0f
    host?.let { v -> runCatching { wm.removeView(v) } }
    host = null; lp = null; back = null; nameLabel = null; content = null; iconView = null; valueLabel = null; captionLabel = null; stretchBtn = null; sheen = null
    visible = false; expanded = false; progress = 0f; pending = null; shownPkg = null; shownLabel = null
  }

  // ---------- building ----------

  private fun physDensity(): Float {
    val phys = Hub.prefs.getInt("physDpi", 0)
    val dpi = if (phys > 0) phys else ctx.resources.displayMetrics.densityDpi
    return dpi / 160f
  }

  private fun px(dp: Float): Int = (dp * scale).roundToInt()

  private fun palette() {
    val light = islandLum(cardColor) > 0.45
    fg = if (light) 0xFF0B0B0D.toInt() else I_WHITE
    muted = if (light) 0x99000000.toInt() else 0x99FFFFFF.toInt()
    chip = if (light) 0x1A000000.toInt() else if (theme.islandGlass != "off") 0x3CFFFFFF else 0x2EFFFFFF
    accent = if (light) 0xFF1B6FB5.toInt() else I_BLUE
    onAccent = if (light) I_WHITE else 0xFF06131F.toInt()
  }

  private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

  private fun tv(text: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(ctx).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * cs * scale)
    setTextColor(color)
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    includeFontPadding = false
  }

  private fun build() {
    scale = physDensity()
    theme = Hub.engine.config().theme
    rawTheme = theme
    cardColor = theme.islandColor or I_BLACK
    palette()
    sz = (theme.islandSize / 100f).coerceAtLeast(0.9f)
    cs = min(sz, 1.25f)
    val m = realMetrics(ctx)
    lastW = m.widthPixels; lastH = m.heightPixels
    shownFree = readFreeRaw()
    free = if (theme.islandMove) parseFree(shownFree) else null
    compact = lastW > lastH && !(theme.islandPos == "right" && free == null)

    val bg = rounded(cardColor, px(18f).toFloat())
    back = bg
    val h = FrameLayout(ctx).apply {
      background = bg
      clipToOutline = true
      outlineProvider = ViewOutlineProvider.BACKGROUND
      // Tap opens it. When the island is movable, dragging the closed pill moves it. A touch outside the open card closes it
      // (and still reaches the game underneath).
      val slop = ViewConfiguration.get(ctx).scaledTouchSlop
      var downX = 0f; var downY = 0f; var startCx = 0f; var startTop = 0f; var dragging = false
      setOnTouchListener { v, e ->
        when (e.actionMasked) {
          MotionEvent.ACTION_OUTSIDE -> {
            if (expanded && SystemClock.elapsedRealtime() >= Hub.floatForceUntil) setExpanded(false)
            false
          }
          MotionEvent.ACTION_DOWN -> {
            downX = e.rawX; downY = e.rawY; dragging = false
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            startCx = loc[0] + v.width / 2f; startTop = loc[1].toFloat()
            true
          }
          MotionEvent.ACTION_MOVE -> {
            if (theme.islandMove && !expanded) {
              if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
              if (dragging) moveTo(startCx + e.rawX - downX, startTop + e.rawY - downY)
            }
            true
          }
          MotionEvent.ACTION_UP -> {
            if (dragging) saveFree() else if (!expanded) setExpanded(true)
            dragging = false
            true
          }
          MotionEvent.ACTION_CANCEL -> { dragging = false; true }
          else -> false
        }
      }
    }
    host = h

    // Collapsed: pure black. DENSIFY in light blue only when the user turned it on.
    val nl = TextView(ctx).apply {
      text = idleText()
      setTextSize(TypedValue.COMPLEX_UNIT_PX, 11.5f * sz * scale)
      setTextColor(if (builtRec) fg else accent)
      maxLines = 1
      typeface = Typeface.DEFAULT_BOLD
      letterSpacing = if (builtRec) 0.02f else 0.22f
      includeFontPadding = false
      gravity = Gravity.CENTER
    }
    nameLabel = nl
    if (glass != "off") {
      val sh = View(ctx).apply { background = glassDrawable(); alpha = 0f }
      sheen = sh
      h.addView(sh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    h.addView(nl, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

    val c = buildContent()
    c.visibility = View.GONE
    content = c
    h.addView(c, FrameLayout.LayoutParams(px(300f), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    lp = WindowManager.LayoutParams(
      px(120f), px(34f), type,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        (if (Build.VERSION.SDK_INT >= 31 && theme.islandGlass == "frosted") WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      // Draw into the camera cut-out area instead of being pushed below it
      if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
  }

  private val glass: String get() = theme.islandGlass

  private var sheenLayers: List<GradientDrawable> = emptyList()

  /** The glass layer of the open card. It fades in as the pill opens, so the closed island stays plain black. */
  private fun glassDrawable(): android.graphics.drawable.Drawable {
    val layers = ArrayList<GradientDrawable>()
    when (glass) {
      // Frosted: a light milky tint over the blurred screen, with a clear 1dp edge (tint 0.2, border 0.2 white)
      "frosted" -> layers.add(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x26FFFFFF)).apply { setStroke(px(1f), 0x38FFFFFF) })
      // Fluid: blue to violet to teal colour flowing across the card, with a soft glow in one corner
      "fluid" -> {
        layers.add(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x803B82F6.toInt(), 0x708B5CF6, 0x6622D3EE)).apply { setStroke(px(1f), 0x44FFFFFF) })
        layers.add(GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = px(150f).toFloat(); setGradientCenter(0.85f, 0.15f); colors = intArrayOf(0x55FFFFFF, 0x00FFFFFF) })
      }
      // Liquid: a bright specular sheen along the top left edge and a rim light all around
      else -> {
        layers.add(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x4DFFFFFF, 0x0DFFFFFF, 0x2EFFFFFF)).apply { setStroke(px(1f), 0x66FFFFFF) })
        layers.add(GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = px(120f).toFloat(); setGradientCenter(0.1f, 0.0f); colors = intArrayOf(0x44FFFFFF, 0x00FFFFFF) })
      }
    }
    sheenLayers = layers
    return android.graphics.drawable.LayerDrawable(layers.toTypedArray())
  }

  private fun buildContent(): LinearLayout {
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    if (theme.islandName) {
      col.addView(tv("DENSIFY", 10f, accent, true).apply { letterSpacing = 0.22f; gravity = Gravity.START },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f * cs) })
    }

    // Header: the game's icon, its name, the smallest width, and the two step buttons
    val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.setOnClickListener { touched(); setExpanded(false) }
    val icon = ImageView(ctx).apply {
      scaleType = ImageView.ScaleType.CENTER_CROP
      outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.28f) }
      }
      clipToOutline = true
    }
    iconView = icon
    head.addView(icon, LinearLayout.LayoutParams(px(46f * cs), px(46f * cs)))

    val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val cap = tv("Smallest width", 12f, muted, false).apply { gravity = Gravity.START; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    captionLabel = cap
    mid.addView(cap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val vrow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    val vl = tv("–", 28f, fg, true).apply { gravity = Gravity.START }
    valueLabel = vl
    vrow.addView(vl)
    vrow.addView(tv("sw", 12f, muted, true).apply { setPadding(px(5f * cs), 0, 0, px(4f * cs)) })
    mid.addView(vrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(1f * cs) })
    head.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f * cs); marginEnd = px(8f * cs) })

    head.addView(stepButton("−", false, -10), LinearLayout.LayoutParams(px(48f * cs), px(48f * cs)))
    head.addView(stepButton("+", true, 10), LinearLayout.LayoutParams(px(48f * cs), px(48f * cs)).apply { marginStart = px(10f * cs) })
    val st = pill("Stretch") { toggleStretch() }
    stretchBtn = st

    if (compact) {
      // Sideways: everything in one row
      fun slot() = LinearLayout.LayoutParams(px(72f * cs), px(40f * cs)).apply { marginStart = px(8f * cs) }
      head.addView(pill("Restore") { restore() }, slot().apply { marginStart = px(16f * cs) })
      head.addView(st, slot())
      head.addView(pill("Hide") { hideForGame() }, slot())
      stopPill()?.let { head.addView(it, slot()) }
      clipPill()?.let { head.addView(it, slot()) }
      col.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
      return col
    }
    col.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // Actions
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(pill("Restore") { restore() }, LinearLayout.LayoutParams(0, px(40f * cs), 1f))
    row.addView(st, LinearLayout.LayoutParams(0, px(40f * cs), 1f).apply { marginStart = px(8f * cs) })
    row.addView(pill("Hide") { hideForGame() }, LinearLayout.LayoutParams(0, px(40f * cs), 1f).apply { marginStart = px(8f * cs) })
    stopPill()?.let { row.addView(it, LinearLayout.LayoutParams(0, px(40f * cs), 1f).apply { marginStart = px(8f * cs) }) }
    clipPill()?.let { row.addView(it, LinearLayout.LayoutParams(0, px(40f * cs), 1f).apply { marginStart = px(8f * cs) }) }
    col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14f * cs) })
    return col
  }

  private fun pill(text: String, click: () -> Unit): TextView = tv(text, 13.5f, fg, true).apply {
    gravity = Gravity.CENTER
    background = rounded(chip, px(40f * cs) / 2f)
    setOnClickListener { touched(); click() }
    setOnTouchListener { v, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80L).start()
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        else -> {}
      }
      false
    }
  }

  /** A round − or + button. Hold it to keep stepping. */
  private fun stepButton(label: String, primary: Boolean, delta: Int): TextView {
    val rep = object : Runnable {
      override fun run() { change(delta); main.postDelayed(this, 110L) }
    }
    return tv(label, 26f, if (primary) onAccent else fg, true).apply {
      gravity = Gravity.CENTER
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (primary) accent else chip) }
      setOnTouchListener { v, e ->
        when (e.actionMasked) {
          MotionEvent.ACTION_DOWN -> { v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80L).start(); change(delta); main.postDelayed(rep, 380L) }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(rep); v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start() }
          else -> {}
        }
        true
      }
    }
  }

  // ---------- layout ----------

  private fun readCutout(): DisplayCutout? {
    if (Build.VERSION.SDK_INT < 28) return null
    if (Build.VERSION.SDK_INT >= 30) {
      val c = runCatching { wm.currentWindowMetrics.windowInsets.displayCutout }.getOrNull()
      if (c != null) return c
    }
    if (Build.VERSION.SDK_INT >= 29) {
      val c = runCatching { ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.cutout }.getOrNull()
      if (c != null) return c
    }
    return null
  }

  /** The camera hole in screen pixels, or null when the phone has none (or does not tell us). */
  private fun pickCutout(w: Int, h: Int): Rect? {
    val rects = readCutout()?.boundingRects ?: return null
    if (rects.isEmpty()) return null
    if (side) {
      if (w <= h) return null
      return rects.firstOrNull { it.right >= w - 2 } ?: rects.firstOrNull { it.left <= 2 }
    }
    return rects.filter { it.top <= 2 }.minByOrNull { abs(it.centerX() - w / 2) }
  }

  private fun clampCenter(v: Float, half: Float, total: Int, margin: Int): Float {
    val lo = half + margin
    val hi = max(lo, total - half - margin)
    return v.coerceIn(lo, hi)
  }

  private fun readFreeRaw(): String = Hub.prefs.getString("islandSpot", "") ?: ""

  private fun parseFree(s: String): Pair<Float, Float>? {
    val p = s.split(",")
    if (p.size < 2) return null
    val x = p[0].toFloatOrNull() ?: return null
    val y = p[1].toFloatOrNull() ?: return null
    return Pair(x.coerceIn(0f, 100f), y.coerceIn(0f, 100f))
  }

  /** Follows the finger while the movable island is dragged: cx is the pill's centre, top its top edge, in screen pixels. */
  private fun moveTo(cx: Float, top: Float) {
    val w = lastW; val h = lastH
    if (w <= 0 || h <= 0) return
    val x = cx.coerceIn(cw / 2f, max(cw / 2f, w - cw / 2f))
    val y = top.coerceIn(0f, max(0f, (h - ch).toFloat()))
    free = Pair(x / w * 100f, y / h * 100f)
    computeLayout()
    render()
  }

  private fun saveFree() {
    val f = free ?: return
    Hub.prefs.edit().putString("islandSpot", "${f.first},${f.second}").apply()
    shownFree = readFreeRaw()
  }

  /** Works out where the pill and the open card go. Called when the island is built, when the cut-out becomes known, and after a rotation. */
  private fun computeLayout() {
    val c = content ?: return
    val m = realMetrics(ctx)
    val w = m.widthPixels; val h = m.heightPixels
    lastW = w; lastH = h
    side = theme.islandPos == "right" && free == null
    val custom = theme.islandPos == "custom" || free != null
    val fx = free?.first ?: theme.islandX.toFloat()
    val fy = free?.second ?: theme.islandY.toFloat()
    val rc = if (custom) null else pickCutout(w, h)
    sideLeft = side && rc != null && rc.left <= 2 && rc.right < w - 2

    // The pill wraps the camera like a real dynamic island, but never grows past 1.35x its normal size: some phones report a very wide cut-out.
    var long = px(122f * sz); var short = px(34f * sz)
    val autoLen = theme.islandLen <= 0; val autoBr = theme.islandBr <= 0
    if (rc != null && !theme.islandMove) {
      if (side) { if (autoLen) long = min(max(long, rc.height() + px(26f * sz)), (long * 1.35f).roundToInt()); if (autoBr) short = min(max(short, rc.width() + px(12f * sz)), (short * 1.35f).roundToInt()) }
      else { if (autoLen) long = min(max(long, rc.width() + px(26f * sz)), (long * 1.35f).roundToInt()); if (autoBr) short = min(max(short, rc.height() + px(10f * sz)), (short * 1.35f).roundToInt()) }
    }
    if (!autoLen) long = px(theme.islandLen.toFloat())
    if (!autoBr) short = px(theme.islandBr.toFloat())
    cw = min(if (side) short else long, w)
    ch = min(if (side) long else short, h)
    val margin = px(10f)
    ew = min(px((if (compact) (if (builtRec || builtClip) 650f else 560f) else 344f) * cs), w - 2 * margin).coerceAtLeast(px(180f))

    val dx = if (free != null) 0 else px(theme.islandDx.toFloat())
    val dy = if (free != null) 0 else px(theme.islandDy.toFloat())
    val pad = px(18f * cs)
    var padL = pad; var padR = pad; var padT = px((if (compact) 12f else 16f) * cs)

    if (!side) {
      val cx = if (custom) w * fx / 100f else (rc?.exactCenterX() ?: (w / 2f))
      val cxC = (cx + dx).coerceIn(cw / 2f, w - cw / 2f)
      xC = (cxC - w / 2f).roundToInt()
      xE = (clampCenter(cxC, ew / 2f, w, margin) - w / 2f).roundToInt()
      val top0 = if (custom) (h * fy / 100f).roundToInt() else if (rc != null) rc.centerY() - ch / 2 else px(8f)
      yC = (top0 + dy).coerceIn(0, max(0, h - ch))
      if (rc != null) padT = max(padT, rc.bottom - yC + px(8f * cs))
      edgeC = 0; edgeE = 0
    } else {
      edgeC = if (sideLeft) dx else -dx
      edgeE = edgeC
      if (rc != null) {
        val clear = rc.width() + px(10f * cs)
        if (sideLeft) padL = max(padL, clear) else padR = max(padR, clear)
      }
    }

    c.setPadding(padL, padT, padR, px((if (compact) 12f else 16f) * cs))
    c.measure(View.MeasureSpec.makeMeasureSpec(ew, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    eh = c.measuredHeight
    c.layoutParams = FrameLayout.LayoutParams(ew, eh, Gravity.TOP or Gravity.CENTER_HORIZONTAL)

    if (!side) {
      yE = min(yC, max(0, h - eh - margin))
    } else {
      val cy = rc?.exactCenterY() ?: (h / 2f)
      val cyC = (cy + dy).coerceIn(ch / 2f, h - ch / 2f)
      yC = (cyC - h / 2f).roundToInt()
      yE = (clampCenter(cyC, eh / 2f, h, margin) - h / 2f).roundToInt()
    }
  }

  /** Draws the current frame: card size, corner radius, position and fades, from `progress` (0 = pill, 1 = open). */
  private fun render() {
    val h = host ?: return
    val p = lp ?: return
    val e = progress
    val k = e.coerceIn(0f, 1f)
    val w = lerp(lerp(cw.toFloat(), max(cw, bw).toFloat(), bp), ew.toFloat(), e).roundToInt().coerceAtLeast(1)
    val ht = lerp(ch.toFloat(), eh.toFloat(), e).roundToInt().coerceAtLeast(1)
    p.width = w; p.height = ht
    if (side) {
      p.gravity = (if (sideLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
      p.x = lerp(edgeC.toFloat(), edgeE.toFloat(), e).roundToInt()
      p.y = lerp(yC.toFloat(), yE.toFloat(), e).roundToInt()
    } else {
      p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      p.x = lerp(xC.toFloat(), xE.toFloat(), e).roundToInt()
      p.y = lerp(yC.toFloat(), yE.toFloat(), e).roundToInt()
    }
    val pillR = min(cw, ch) / 2f
    val cardR = px(30f * cs).toFloat()
    back?.cornerRadius = min(lerp(pillR, cardR, k), min(w, ht) / 2f)
    nameLabel?.alpha = (1f - k * 3f).coerceIn(0f, 1f)
    sheen?.let { sv -> sv.alpha = k; val cr = back?.cornerRadius ?: 0f; for (g in sheenLayers) g.cornerRadius = cr }
    if (glass == "frosted") {
      // A see-through card with the screen behind it blurred. Needs Android 12; older phones get the plain tinted card.
      back?.setColor(((lerp(255f, 185f, k)).roundToInt() shl 24) or (cardColor and 0xFFFFFF))
      if (Build.VERSION.SDK_INT >= 31) p.blurBehindRadius = (k * px(18f)).roundToInt()
    }
    content?.alpha = ((k - 0.3f) / 0.7f).coerceIn(0f, 1f)
    val base = (theme.opacity / 100f).coerceIn(0.2f, 1f)
    h.alpha = lerp(base, max(base, 0.94f), k)
    h.invalidateOutline()
    runCatching { wm.updateViewLayout(h, p) }
  }

  private fun setExpanded(on: Boolean, animate: Boolean = true) {
    val h = host ?: return
    if (on == expanded && anim == null && progress == (if (on) 1f else 0f)) return
    expanded = on
    val old = anim
    anim = null
    old?.cancel()
    val c = content
    if (on) {
      cancelBanner()
      c?.visibility = View.VISIBLE
      syncGame(); syncValue(); syncStretch()
      runCatching { h.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
      scheduleCollapse()
      main.removeCallbacks(ticker)
      main.postDelayed(ticker, 1_000L)
    } else {
      main.removeCallbacks(collapseRun); main.removeCallbacks(ticker)
    }
    val to = if (on) 1f else 0f
    if (!animate) {
      progress = to
      if (!on) c?.visibility = View.GONE
      render()
      return
    }
    val a = ValueAnimator.ofFloat(progress, to)
    a.duration = if (on) 440L else 300L
    a.interpolator = if (on) OvershootInterpolator(0.9f) else PathInterpolator(0.25f, 0f, 0.1f, 1f)
    a.addUpdateListener { progress = it.animatedValue as Float; render() }
    a.addListener(object : AnimatorListenerAdapter() {
      override fun onAnimationEnd(animation: Animator) {
        if (anim === animation) { anim = null; progress = to; if (!on) { content?.visibility = View.GONE; computeLayout() }; render() }
      }
    })
    anim = a
    a.start()
  }

  // ---------- state ----------

  private fun touched() {
    val now = SystemClock.elapsedRealtime()
    // While previewing from the app, any touch keeps the island on screen for another 15 seconds.
    if (now < Hub.floatForceUntil) Hub.floatForceUntil = now + 15_000L
    scheduleCollapse()
  }

  private fun scheduleCollapse() {
    main.removeCallbacks(collapseRun)
    if (expanded) main.postDelayed(collapseRun, 8_000L)
  }

  private fun currentSw(): Int = Hub.engine.state.value.appliedSw ?: ctx.resources.configuration.smallestScreenWidthDp

  private fun syncValue() {
    if (pending != null) return
    val s = currentSw().toString()
    if (valueLabel?.text?.toString() != s) valueLabel?.text = s
  }

  private fun syncStretch() {
    val b = stretchBtn ?: return
    val on = Hub.engine.state.value.stretched
    b.background = rounded(if (on) accent else chip, px(40f * cs) / 2f)
    b.setTextColor(if (on) onAccent else fg)
  }

  private fun paintCaption() {
    captionLabel?.let { it.text = shownLabel ?: "Smallest width"; it.setTextColor(muted) }
  }

  /** Shows a short message in place of the game name, then puts the name back. */
  private fun flash(msg: String, color: Int = 0xFFFF7A7A.toInt()) {
    captionLabel?.let { it.text = msg; it.setTextColor(color) }
    main.removeCallbacks(restoreCaption)
    main.postDelayed(restoreCaption, 2_500L)
  }

  /** The game that is open: its own icon and name. Falls back to the Densify logo and a plain caption. */
  private fun syncGame() {
    val st = Hub.engine.state.value
    val pkg = st.frontPkg ?: st.activePkg
    if (pkg == shownPkg && iconView?.drawable != null) return
    shownPkg = pkg
    var label: String? = null
    var icon: Drawable? = null
    if (pkg != null) {
      val pm = ctx.packageManager
      runCatching { icon = pm.getApplicationIcon(pkg) }
      runCatching { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
    }
    shownLabel = label
    val iv = iconView
    if (iv != null) {
      if (icon != null) iv.setImageDrawable(icon)
      else {
        val id = ctx.resources.getIdentifier("densify_logo", "drawable", ctx.packageName)
        if (id != 0) iv.setImageResource(id)
      }
    }
    paintCaption()
  }

  // ---------- live activities ----------

  private fun rebuild() {
    val wasOpen = expanded
    detach()
    attach()
    if (wasOpen) setExpanded(true, animate = false)
  }

  private fun idleText(): CharSequence = if (builtRec) recText() else if (theme.islandName) "DENSIFY" else ""

  /** A red dot and the recording time, shown inside the closed pill while the screen recorder runs. */
  private fun recText(): CharSequence {
    val sec = RecState.elapsedMs() / 1000
    val dot = if (RecState.state == "paused") 0xFFB9C2CC.toInt() else 0xFFFF453A.toInt()
    return SpannableString("● %d:%02d".format(sec / 60, sec % 60)).apply { setSpan(ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
  }

  /** Instant replay is on: one tap saves the last seconds. */
  private fun clipPill(): TextView? {
    if (!builtClip) return null
    return pill("Clip") { ReplayService.save(ctx); flash("Saving the last ${ReplayState.seconds}s…", accent) }.apply {
      background = rounded(accent, px(40f * cs) / 2f)
      setTextColor(onAccent)
    }
  }

  private fun stopPill(): TextView? {
    if (!builtRec) return null
    return pill("Stop") { RecorderService.toggle(ctx) }.apply {
      background = rounded(0xFFD94A4A.toInt(), px(40f * cs) / 2f)
      setTextColor(I_WHITE)
    }
  }

  private fun labelOf(pkg: String): String? = runCatching { ctx.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrNull()

  /** When a game comes to the front, the pill briefly widens to say which game and the smallest width. */
  private fun watchGame() {
    val pkg = Hub.engine.state.value.frontPkg
    if (pkg != null && pkg != lastFront && !builtRec) {
      bannerPkg = pkg
      main.removeCallbacks(gameBanner)
      main.postDelayed(gameBanner, 1_200L) // give the width a moment to be applied, so the number is the new one
    }
    lastFront = pkg
  }

  /** Once per drop below the user's low-battery level (when not charging), the pill warns for a moment. */
  private fun watchBattery() {
    val cfg = Hub.engine.config()
    if (!cfg.lowBattery) return
    val i = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val full = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    if (level < 0 || full <= 0) return
    val pct = level * 100 / full
    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    if (!charging && pct < cfg.lowBatteryPercent) {
      if (!lowShown) { lowShown = true; showBanner("Battery $pct%", 0xFFFF6B6B.toInt()) }
    } else lowShown = false
  }

  private fun showBanner(text: String, color: Int) {
    if (!visible || expanded || side || bannerOn) return
    val nl = nameLabel ?: return
    bannerOn = true
    nl.text = text
    nl.setTextColor(color)
    nl.letterSpacing = 0.02f
    bw = min(lastW - 2 * px(10f), (nl.paint.measureText(text) + px(44f * sz)).toInt())
    animateBanner(1f)
    main.removeCallbacks(bannerEnd)
    main.postDelayed(bannerEnd, 2_800L)
  }

  private fun animateBanner(to: Float) {
    val old = bannerAnim
    bannerAnim = null
    old?.cancel()
    val a = ValueAnimator.ofFloat(bp, to)
    a.duration = 280L
    a.interpolator = if (to > 0f) OvershootInterpolator(0.7f) else PathInterpolator(0.25f, 0f, 0.1f, 1f)
    a.addUpdateListener { bp = it.animatedValue as Float; render() }
    a.addListener(object : AnimatorListenerAdapter() {
      override fun onAnimationEnd(animation: Animator) {
        if (bannerAnim === animation) { bannerAnim = null; if (to == 0f) { bannerOn = false; bp = 0f; restoreLabel() } }
      }
    })
    bannerAnim = a
    a.start()
  }

  private fun cancelBanner() {
    main.removeCallbacks(bannerEnd); main.removeCallbacks(gameBanner)
    val old = bannerAnim
    bannerAnim = null
    old?.cancel()
    if (bannerOn || bp != 0f) { bannerOn = false; bp = 0f; restoreLabel() }
  }

  private fun restoreLabel() {
    nameLabel?.let {
      it.text = idleText()
      it.setTextColor(if (builtRec) fg else accent)
      it.letterSpacing = if (builtRec) 0.02f else 0.22f
    }
  }

  // ---------- actions ----------

  /** Smallest-width steps. The number changes the instant it is tapped and quick taps merge into one change. */
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
          val r = Hub.engine.adjustSw(target, confirm = true)
          if (pending == target) pending = null
          main.post {
            if (r.isSuccess) { if (pending == null) valueLabel?.text = (r.getOrNull() ?: target).toString() }
            else { pending = null; syncValue(); flash("Couldn't set it. Check the connection.") }
          }
          if (pending == null) break
        }
      } finally { busy.set(false) }
      if (pending != null) main.post { change(0) }
    }
  }

  private fun restore() {
    Hub.scope.launch {
      Hub.engine.restoreAll()
      pending = null
      main.post { syncValue(); syncStretch() }
    }
  }

  private fun toggleStretch() {
    Hub.scope.launch {
      val was = Hub.engine.state.value.stretched
      val on = Hub.engine.toggleStretch(confirm = true)
      main.post {
        syncStretch()
        if (!was && !on) flash("Stretch failed. Is Wireless Debugging on?")
      }
    }
  }

  private fun hideForGame() {
    dismissedKey = Hub.engine.state.value.frontPkg ?: "always"
    Hub.floatForceUntil = 0L
    hide()
  }
}
