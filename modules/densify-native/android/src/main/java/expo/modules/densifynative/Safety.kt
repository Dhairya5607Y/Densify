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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Keep these settings?" for risky changes made by hand (a big smallest-width jump, a new screen size).
 * A small card appears in the middle of the screen with a 5 second countdown. Keep it, or do nothing and the change is undone.
 * It is its own overlay window, drawn at the phone's real density, so it stays readable even when the change made the screen hard to use.
 * Automatic switching, Tasker and other apps never trigger it.
 */
object Safety {
  const val SECONDS = 5
  private val main = Handler(Looper.getMainLooper())

  private var undo: (suspend () -> Unit)? = null
  private var root: LinearLayout? = null
  private var wm: WindowManager? = null
  private var what: TextView? = null
  private var count: TextView? = null
  private var bar: View? = null
  private var deadline = 0L
  private var scale = 1f

  private val tick = object : Runnable {
    override fun run() {
      if (root == null) return
      val left = deadline - SystemClock.elapsedRealtime()
      if (left <= 0) { revert(); return }
      count?.text = ((left + 999) / 1000).toString()
      main.postDelayed(this, 120L)
    }
  }

  /**
   * Call right after a manual change was applied. [undo] puts the previous state back.
   * If a prompt is already open, the countdown restarts but the ORIGINAL state is still what Revert goes back to.
   */
  fun ask(text: String, undo: suspend () -> Unit) {
    val ctx = runCatching { Hub.app }.getOrNull() ?: return
    val canDraw = Build.VERSION.SDK_INT < 23 || runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
    if (!canDraw) return
    main.post {
      if (this.undo == null) this.undo = undo
      show(ctx, text)
    }
  }

  private fun px(dp: Float): Int = (dp * scale).roundToInt()

  private fun tv(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean) = TextView(ctx).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * scale)
    setTextColor(color)
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    includeFontPadding = false
    gravity = Gravity.CENTER
  }

  private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

  @SuppressLint("ClickableViewAccessibility")
  private fun show(ctx: Context, text: String) {
    deadline = SystemClock.elapsedRealtime() + SECONDS * 1000L
    val existing = root
    if (existing != null) {
      what?.text = text
      bar?.let { b -> b.animate().cancel(); b.scaleX = 1f; b.animate().scaleX(0f).setDuration(SECONDS * 1000L).setInterpolator(android.view.animation.LinearInterpolator()).start() }
      main.removeCallbacks(tick); main.post(tick)
      return
    }
    val phys = Hub.prefs.getInt("physDpi", 0)
    scale = (if (phys > 0) phys else ctx.resources.displayMetrics.densityDpi) / 160f
    val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    wm = manager

    val blue = 0xFF8CC4F5.toInt()
    val card = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      background = round(0xF4000000.toInt(), px(26f).toFloat())
      setPadding(px(20f), px(20f), px(20f), px(18f))
    }
    card.addView(tv(ctx, "Keep these settings?", 17f, 0xFFFFFFFF.toInt(), true))
    val w = tv(ctx, text, 13f, 0x99FFFFFF.toInt(), false)
    what = w
    card.addView(w, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(5f) })
    val c = tv(ctx, SECONDS.toString(), 44f, blue, true)
    count = c
    card.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })

    // A thin bar that drains over the countdown
    val track = FrameLayout(ctx).apply { background = round(0x2EFFFFFF, px(2f).toFloat()) }
    val fill = View(ctx).apply { background = round(blue, px(2f).toFloat()); pivotX = 0f }
    bar = fill
    track.addView(fill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    card.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(4f)).apply { topMargin = px(10f) })

    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    val rev = tv(ctx, "Revert", 15f, 0xFFFFFFFF.toInt(), true).apply {
      background = round(0x2EFFFFFF, px(24f).toFloat())
      setOnClickListener { revert() }
    }
    val keep = tv(ctx, "Keep", 15f, 0xFF06131F.toInt(), true).apply {
      background = round(blue, px(24f).toFloat())
      setOnClickListener { keepIt() }
    }
    row.addView(rev, LinearLayout.LayoutParams(0, px(48f), 1f))
    row.addView(keep, LinearLayout.LayoutParams(0, px(48f), 1f).apply { marginStart = px(10f) })
    card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })

    val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    val p = WindowManager.LayoutParams(
      px(280f), WindowManager.LayoutParams.WRAP_CONTENT, type,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.CENTER }
    root = card
    if (runCatching { manager.addView(card, p) }.isFailure) { root = null; undo = null; return }
    fill.animate().scaleX(0f).setDuration(SECONDS * 1000L).setInterpolator(android.view.animation.LinearInterpolator()).start()
    main.removeCallbacks(tick); main.post(tick)
  }

  private fun close() {
    main.removeCallbacks(tick)
    bar?.animate()?.cancel()
    root?.let { v -> runCatching { wm?.removeView(v) } }
    root = null; what = null; count = null; bar = null
  }

  private fun keepIt() { undo = null; close() }

  private fun revert() {
    val u = undo
    undo = null
    close()
    if (u != null) Hub.scope.launch { runCatching { u() }.onFailure { Hub.log("Revert failed: ${it.message}") } }
  }
}
