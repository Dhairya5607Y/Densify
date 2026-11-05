package expo.modules.densifynative

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recorder settings, written by the app and read here, so the notification and the floating panel can start a recording with no app open.
 * [res]: sd, hd, fhd, qhd, uhd (4K) or native. [codec]: h264 (works everywhere) or hevc (about half the file size).
 * [scope]: "ask" lets Android 14+ offer a single app or the whole screen, "screen" records the whole screen.
 */
data class RecCfg(
  val res: String = "fhd",
  val fps: Int = 60,
  val mbps: Int = 0,
  val codec: String = "h264",
  val orient: String = "landscape",
  val scope: String = "ask",
  val notif: Boolean = true,
) {
  companion object {
    fun load(): RecCfg = parse(Hub.prefs.getString("rec", "") ?: "")
    fun parse(raw: String): RecCfg = runCatching {
      val o = JSONObject(raw)
      RecCfg(
        res = o.optString("res", "fhd"), fps = o.optInt("fps", 60), mbps = o.optInt("mbps", 0), codec = o.optString("codec", "h264"),
        orient = o.optString("orient", "landscape"), scope = o.optString("scope", "ask"), notif = o.optBoolean("notif", true),
      )
    }.getOrDefault(RecCfg())
  }
}

/** What the recorder is doing right now. Lives in the process, so the app, the panel and the notification all see the same thing. */
object RecState {
  @Volatile var state = "idle"          // idle | recording | paused
  @Volatile var accumulated = 0L        // recorded time before the current stretch
  @Volatile var resumedAt = 0L          // elapsedRealtime when the current stretch began
  @Volatile var info = ""
  @Volatile var notes = ""
  @Volatile var name = ""
  @Volatile var error = ""

  fun elapsedMs(): Long = if (state == "recording") accumulated + (SystemClock.elapsedRealtime() - resumedAt) else accumulated

  fun toJson(): String = JSONObject()
    .put("state", state).put("elapsedMs", elapsedMs()).put("info", info).put("notes", notes).put("name", name).put("error", error)
    .put("last", Hub.prefs.getString("recLast", "") ?: "")
    .toString()
}

/** Works out a size, frame rate and bitrate the phone's encoder can really do, and says so when it had to lower something. */
data class RecPlan(val w: Int, val h: Int, val fps: Int, val bitrate: Int, val hevc: Boolean, val notes: List<String>) {
  val label: String get() = "${w}x$h · $fps fps · ${if (hevc) "H.265" else "H.264"} · ${bitrate / 1_000_000} Mbps"

  companion object {
    private val LADDER = listOf("uhd" to 3840, "qhd" to 2560, "fhd" to 1920, "hd" to 1280, "sd" to 854)

    private fun hw(i: MediaCodecInfo): Boolean =
      if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated else !(i.name.startsWith("OMX.google.") || i.name.startsWith("c2.android."))

    private fun caps(mime: String): MediaCodecInfo.VideoCapabilities? = runCatching {
      val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci -> ci.isEncoder && ci.supportedTypes.any { t -> t.equals(mime, true) } }
      val pick = all.firstOrNull { hw(it) } ?: all.firstOrNull()
      pick?.getCapabilitiesForType(mime)?.videoCapabilities
    }.getOrNull()

    private fun supports(c: MediaCodecInfo.VideoCapabilities?, w: Int, h: Int, fps: Int): Boolean =
      c == null || runCatching { c.isSizeSupported(w, h) && c.areSizeAndRateSupported(w, h, fps.toDouble()) }.getOrDefault(false)

    fun make(cfg: RecCfg, dispW: Int, dispH: Int): RecPlan {
      val notes = ArrayList<String>()
      val longN = maxOf(dispW, dispH); val shortN = maxOf(1, minOf(dispW, dispH))
      val landscape = when (cfg.orient) { "landscape" -> true; "portrait" -> false; else -> dispW >= dispH }
      val aspect = longN.toDouble() / shortN.toDouble()

      var hevc = cfg.codec == "hevc"
      if (hevc && caps("video/hevc") == null) { hevc = false; notes.add("This phone can't record H.265, so H.264 is used.") }
      val c = caps(if (hevc) "video/hevc" else "video/avc")
      val wa = (c?.widthAlignment ?: 2).coerceAtLeast(2)
      val ha = (c?.heightAlignment ?: 2).coerceAtLeast(2)

      fun dims(longSide: Int): Pair<Int, Int> {
        val s = (longSide / aspect).toInt()
        val ww = if (landscape) longSide else s
        val hh = if (landscape) s else longSide
        return Pair(ww / wa * wa, hh / ha * ha)
      }

      val want = if (cfg.res == "native") longN else (LADDER.firstOrNull { it.first == cfg.res }?.second ?: 1920)
      val rungs = (listOf(want) + LADDER.map { it.second }.filter { it < want }).distinct()
      val reqFps = cfg.fps.coerceIn(15, 120)
      val fpsTry = if (reqFps > 30) listOf(reqFps, 30) else listOf(reqFps)

      var cw = 0; var ch = 0; var cf = reqFps; var found = false
      loop@ for (l in rungs) {
        val (ww, hh) = dims(l)
        if (ww < 2 || hh < 2) continue
        for (f in fpsTry) {
          if (supports(c, ww, hh, f)) { cw = ww; ch = hh; cf = f; found = true; break@loop }
        }
      }
      if (!found) { val (ww, hh) = dims(1280); cw = ww; ch = hh; cf = 30; notes.add("Couldn't confirm what this encoder supports, so 1280 at 30 fps is used.") }

      val (dw, dh) = dims(want)
      if (found && (cw != dw || ch != dh)) notes.add("This phone can't encode ${dw}x$dh, so ${cw}x$ch is used.")
      if (found && cf != reqFps) notes.add("$reqFps fps isn't available at ${cw}x$ch here, so $cf fps is used.")

      val bpp = if (hevc) 0.045 else 0.07
      var br: Long = if (cfg.mbps > 0) cfg.mbps.coerceIn(1, 200) * 1_000_000L
      else (cw.toLong() * ch * cf * bpp).toLong().coerceIn(2_000_000L, 80_000_000L)
      c?.bitrateRange?.let { r -> br = br.coerceIn(r.lower.toLong(), r.upper.toLong()) }
      return RecPlan(cw, ch, cf, br.toInt(), hevc, notes)
    }

    /** What the app shows next to the settings, so a choice the phone can't do is never a surprise. */
    fun capsJson(): String {
      val avc = caps("video/avc"); val hv = caps("video/hevc")
      return JSONObject()
        .put("hevc", hv != null)
        .put("uhd30", supports(avc, 3840, 2160, 30) || supports(hv, 3840, 2160, 30))
        .put("uhd60", supports(avc, 3840, 2160, 60) || supports(hv, 3840, 2160, 60))
        .put("sdk", Build.VERSION.SDK_INT)
        .toString()
    }
  }
}

@Suppress("DEPRECATION")
fun realMetrics(ctx: Context): DisplayMetrics {
  val dm = DisplayMetrics()
  val d = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
  if (d != null) d.getRealMetrics(dm) else dm.setTo(ctx.resources.displayMetrics)
  return dm
}

/**
 * Records the screen with Android's own hardware encoder (MediaRecorder fed by a MediaProjection), so it costs almost no CPU.
 * Start, pause, resume and stop all work from the notification, the floating panel and the app.
 */
class RecorderService : Service() {
  private val main = Handler(Looper.getMainLooper())
  private var recorder: MediaRecorder? = null
  private var projection: MediaProjection? = null
  private var projectionCb: MediaProjection.Callback? = null
  private var vdisplay: VirtualDisplay? = null
  private var pfd: ParcelFileDescriptor? = null
  private var outUri: Uri? = null
  private var outFile: File? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Hub.init(this)
    when (intent?.action) {
      ACTION_START -> {
        // Android 14 and newer insist the foreground notification is up before the screen-capture token is used.
        goForeground("Starting the recorder…", "")
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null || RecState.state != "idle") { fail("Couldn't start the recorder."); return START_NOT_STICKY }
        begin(code, data)
      }
      ACTION_PAUSE -> pause()
      ACTION_RESUME -> resume()
      ACTION_TOGGLE -> if (RecState.state == "paused") resume() else pause()
      ACTION_STOP -> finish()
      else -> if (RecState.state == "idle") stopSelf()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    if (recorder != null) {
      runCatching { recorder?.stop() }
      cleanup(true)
      RecState.state = "idle"
    }
    super.onDestroy()
  }

  private fun goForeground(title: String, text: String) {
    val n = buildNotif(title, text)
    if (Build.VERSION.SDK_INT >= 29) startForeground(Notifs.ID_REC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(Notifs.ID_REC, n)
  }

  private fun buildNotif(title: String, text: String): Notification {
    val b = Notifs.builder(this, Notifs.CHANNEL_REC).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
    when (RecState.state) {
      "recording" -> {
        b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - RecState.elapsedMs())
        b.addAction(Notifs.recAction(this, "Pause", ACTION_PAUSE, 21))
        b.addAction(Notifs.recAction(this, "Stop", ACTION_STOP, 23))
      }
      "paused" -> {
        b.addAction(Notifs.recAction(this, "Resume", ACTION_RESUME, 22))
        b.addAction(Notifs.recAction(this, "Stop", ACTION_STOP, 23))
      }
    }
    return b.build()
  }

  private fun refreshNotif() {
    val title = when (RecState.state) { "recording" -> "Recording"; "paused" -> "Recording paused"; else -> "Screen recorder" }
    runCatching { getSystemService(NotificationManager::class.java).notify(Notifs.ID_REC, buildNotif(title, RecState.info)) }
  }

  private fun begin(code: Int, data: Intent) {
    try {
      val cfg = RecCfg.load()
      val dm = realMetrics(this)
      val p = RecPlan.make(cfg, dm.widthPixels, dm.heightPixels)

      val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
      recorder = mr
      mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
      mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      mr.setVideoEncoder(if (p.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
      mr.setVideoSize(p.w, p.h)
      mr.setVideoFrameRate(p.fps)
      mr.setVideoEncodingBitRate(p.bitrate)
      openOutput(mr)
      mr.prepare()

      val mpm = getSystemService(MediaProjectionManager::class.java)
      val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Android didn't allow screen capture.")
      projection = mp
      // Android 14 and newer require a callback to be registered before the virtual display is made.
      val cb = object : MediaProjection.Callback() {
        override fun onStop() { main.post { finish() } }
      }
      projectionCb = cb
      mp.registerCallback(cb, main)
      vdisplay = mp.createVirtualDisplay("densify-rec", p.w, p.h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mr.surface, null, null)
      mr.start()

      RecState.error = ""
      RecState.info = p.label
      RecState.notes = p.notes.joinToString(" ")
      RecState.accumulated = 0L
      RecState.resumedAt = SystemClock.elapsedRealtime()
      RecState.state = "recording"
      Hub.log("Recording started · ${p.label}")
      refreshNotif()
    } catch (t: Throwable) {
      cleanup(false)
      fail(t.message ?: "Couldn't start recording.")
    }
  }

  private fun openOutput(mr: MediaRecorder) {
    val name = "Densify_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
    RecState.name = name
    if (Build.VERSION.SDK_INT >= 29) {
      val v = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Densify")
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }
      val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("Couldn't create the video file.")
      outUri = u
      val f = contentResolver.openFileDescriptor(u, "w") ?: throw IllegalStateException("Couldn't open the video file.")
      pfd = f
      mr.setOutputFile(f.fileDescriptor)
    } else {
      // Before Android 10 a file in Densify's own Movies folder needs no storage permission.
      val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Densify")
      dir.mkdirs()
      val f = File(dir, name)
      outFile = f
      mr.setOutputFile(f.absolutePath)
    }
  }

  private fun pause() {
    if (RecState.state != "recording") return
    val ok = runCatching { recorder?.pause() }.isSuccess
    if (!ok) return
    RecState.accumulated = RecState.elapsedMs()
    RecState.state = "paused"
    refreshNotif()
  }

  private fun resume() {
    if (RecState.state != "paused") return
    val ok = runCatching { recorder?.resume() }.isSuccess
    if (!ok) return
    RecState.resumedAt = SystemClock.elapsedRealtime()
    RecState.state = "recording"
    refreshNotif()
  }

  /** Stops, saves the file and shuts the service down. Safe to call more than once. */
  private fun finish() {
    val had = recorder != null
    var saved = false
    if (had) {
      saved = try { recorder?.stop(); true } catch (t: Throwable) { Hub.log("The recording was too short to save."); false }
      cleanup(saved)
      Hub.log(if (saved) "Recording saved: ${RecState.name}" else "Recording discarded")
    }
    RecState.state = "idle"
    RecState.accumulated = 0L
    stopForegroundCompat()
    stopSelf()
    if (had && saved) Notifs.post(this, Notifs.ID_REC_IDLE + 1, "Recording saved", RecState.name)
  }

  private fun fail(msg: String) {
    RecState.state = "idle"
    RecState.error = msg
    Hub.log("Recorder: $msg")
    Notifs.post(this, Notifs.ID_REC_IDLE + 1, "Recording stopped", msg)
    stopForegroundCompat()
    stopSelf()
  }

  /** Releases everything. A recording that isn't kept is deleted. */
  private fun cleanup(keep: Boolean) {
    runCatching { recorder?.reset() }
    runCatching { recorder?.release() }
    recorder = null
    runCatching { vdisplay?.release() }
    vdisplay = null
    projectionCb?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
    projectionCb = null
    runCatching { projection?.stop() }
    projection = null
    runCatching { pfd?.close() }
    pfd = null
    val u = outUri
    val f = outFile
    if (u != null) {
      if (keep) runCatching { val v = ContentValues(); v.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(u, v, null, null) }
      else runCatching { contentResolver.delete(u, null, null) }
      if (keep) Hub.prefs.edit().putString("recLast", u.toString()).apply()
    }
    if (f != null && !keep) runCatching { f.delete() }
    outUri = null
    outFile = null
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
  }

  companion object {
    const val ACTION_START = "expo.modules.densifynative.REC_START"
    const val ACTION_PAUSE = "expo.modules.densifynative.REC_PAUSE"
    const val ACTION_RESUME = "expo.modules.densifynative.REC_RESUME"
    const val ACTION_TOGGLE = "expo.modules.densifynative.REC_TOGGLE"
    const val ACTION_STOP = "expo.modules.densifynative.REC_STOP"
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"

    fun send(ctx: Context, action: String) { runCatching { ctx.startService(Intent(ctx, RecorderService::class.java).setAction(action)) } }

    /** Opens the consent screen. Android asks every time, which is what makes recording something only the user can allow. */
    fun startFlow(ctx: Context) {
      runCatching { ctx.startActivity(Intent(ctx, RecordConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Starts when idle, stops when recording. Used by the floating panel. */
    fun toggle(ctx: Context) { if (RecState.state == "idle") startFlow(ctx) else send(ctx, ACTION_STOP) }

    fun openLast(ctx: Context): Boolean {
      val u = Hub.prefs.getString("recLast", "") ?: ""
      if (u.isEmpty()) return false
      return runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "video/mp4").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
      }.getOrDefault(false)
    }
  }
}

/** A see-through screen whose only job is to show Android's "start recording" prompt, then hand the answer to the service. */
class RecordConsentActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Hub.init(this)
    if (RecState.state != "idle") { finish(); return }
    try {
      val mpm = getSystemService(MediaProjectionManager::class.java)
      val cfg = RecCfg.load()
      val intent = if (Build.VERSION.SDK_INT >= 34 && cfg.scope == "screen") mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
      @Suppress("DEPRECATION")
      startActivityForResult(intent, REQ)
    } catch (t: Throwable) {
      Toast.makeText(this, "Couldn't open the recording prompt.", Toast.LENGTH_SHORT).show()
      finish()
    }
  }

  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
      val i = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        .putExtra(RecorderService.EXTRA_CODE, resultCode).putExtra(RecorderService.EXTRA_DATA, data)
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    } else {
      Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
    }
    finish()
  }

  companion object { const val REQ = 4410 }
}
