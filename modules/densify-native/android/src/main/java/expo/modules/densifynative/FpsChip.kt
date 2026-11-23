package expo.modules.densifynative

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small floating FPS readout in the island's style: the island colour, rounded, with the same glass look.
 * It stays on screen while a game is open, even when the island itself is closed. Drag it anywhere; it remembers the spot.
 */
class FpsChip(private val ctx: Context) {
  private val main = Handler(Looper.getMainLooper())
  private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private var view: TextView? = null
  private var lp: WindowManager.LayoutParams? = null
  private var theme: PanelTheme? = null
  private var visible = false
  private var spot: Pair<Float, Float> = Pair(3f, 10f)

  private val ticker = object : Runnable {
    override fun run() {
      if (!visible) return
      ChoreoFps.start()
      val f = ChoreoFps.read()
      val t = if (f > 0) "$f FPS" else "– FPS"
      view?.let { v -> if (v.text.toString() != t) v.text = t }
      main.postDelayed(this, 500L)
    }
  }

  /** Called by the monitor every moment: shows the chip while [wanted], rebuilds it when the theme changed, hides it otherwise. */
  fun update(wanted: Boolean, t: PanelTheme) {
    main.post {
      if (!wanted) { detach(); return@post }
      if (visible && t == theme) return@post
      detach()
      attach(t)
    }
  }

  fun hide() { main.post { detach() } }

  private fun detach() {
    main.removeCallbacks(ticker)
    view?.let { v -> runCatching { wm.removeView(v) } }
    view = null; lp = null; visible = false; theme = null
  }

  private fun readSpot() {
    val p = (Hub.prefs.getString("fpsSpot", "") ?: "").split(",")
    val x = p.getOrNull(0)?.toFloatOrNull(); val y = p.getOrNull(1)?.toFloatOrNull()
    if (x != null && y != null) spot = Pair(x.coerceIn(0f, 100f), y.coerceIn(0f, 100f))
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun attach(t: PanelTheme) {
    val canDraw = Build.VERSION.SDK_INT < 23 || runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
    if (!canDraw) return
    theme = t
    readSpot()
    val phys = Hub.prefs.getInt("physDpi", 0)
    val scale = (if (phys > 0) phys else ctx.resources.displayMetrics.densityDpi) / 160f
    val sz = (t.islandSize / 100f).coerceIn(0.9f, 1.25f)
    fun px(dp: Float) = (dp * scale * sz).roundToInt()

    val base = t.islandColor or 0xFF000000.toInt()
    val lum = (0.299 * ((base shr 16) and 255) + 0.587 * ((base shr 8) and 255) + 0.114 * (base and 255)) / 255
    val light = lum > 0.45
    val radius = px(18f).toFloat()
    val layers = ArrayList<android.graphics.drawable.Drawable>()
    layers.add(GradientDrawable().apply { setColor(base); cornerRadius = radius })
    when (t.islandGlass) {
      "frosted" -> layers.add(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x26FFFFFF)).apply { cornerRadius = radius; setStroke(px(1f), 0x38FFFFFF) })
      "fluid" -> layers.add(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x803B82F6.toInt(), 0x708B5CF6, 0x6622D3EE)).apply { cornerRadius = radius; setStroke(px(1f), 0x44FFFFFF) })
      "liquid" -> layers.add(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x4DFFFFFF, 0x0DFFFFFF, 0x2EFFFFFF)).apply { cornerRadius = radius; setStroke(px(1f), 0x66FFFFFF) })
    }

    val v = TextView(ctx).apply {
      text = "– FPS"
      setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13f).toFloat())
      setTextColor(if (light) 0xFF1B6FB5.toInt() else 0xFF8CC4F5.toInt())
      typeface = Typeface.DEFAULT_BOLD
      includeFontPadding = false
      letterSpacing = 0.04f
      gravity = Gravity.CENTER
      setPadding(px(13f), px(7f), px(13f), px(7f))
      background = LayerDrawable(layers.toTypedArray())
      alpha = (t.opacity / 100f).coerceIn(0.4f, 1f)
    }

    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    val p = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    val m = realMetrics(ctx)
    p.x = (m.widthPixels * spot.first / 100f).roundToInt()
    p.y = (m.heightPixels * spot.second / 100f).roundToInt()

    // Drag to move. A plain tap does nothing, so it never gets in the way of the game.
    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moving = false
    v.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moving = false; true }
        MotionEvent.ACTION_MOVE -> {
          if (!moving && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moving = true
          if (moving) {
            val mm = realMetrics(ctx)
            p.x = (startX + e.rawX - downX).roundToInt().coerceIn(0, maxOf(0, mm.widthPixels - v.width))
            p.y = (startY + e.rawY - downY).roundToInt().coerceIn(0, maxOf(0, mm.heightPixels - v.height))
            runCatching { wm.updateViewLayout(v, p) }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (moving) {
            val mm = realMetrics(ctx)
            Hub.prefs.edit().putString("fpsSpot", "${p.x * 100f / mm.widthPixels},${p.y * 100f / mm.heightPixels}").apply()
          }
          true
        }
        else -> true
      }
    }

    view = v; lp = p
    if (runCatching { wm.addView(v, p) }.isSuccess) { visible = true; main.post(ticker) } else { view = null; lp = null }
  }
}
