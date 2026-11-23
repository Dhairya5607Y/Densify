package expo.modules.densifynative

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** What instant replay is doing. state: idle | buffering. Lives in the process so the app, the island and the notification agree. */
object ReplayState {
  @Volatile var state = "idle"
  @Volatile var seconds = 30
  @Volatile var info = ""
  @Volatile var error = ""
  @Volatile var lastSaved = ""
  @Volatile var savedAt = 0L

  fun load(): Int = (Hub.prefs.getString("replaySec", "30") ?: "30").toIntOrNull()?.coerceIn(10, 120) ?: 30

  fun toJson(): String = JSONObject().put("state", state).put("seconds", seconds).put("info", info).put("error", error).put("lastSaved", lastSaved).put("savedAt", savedAt).toString()
}

private class Frame(val data: ByteArray, val ptsUs: Long, val flags: Int) { val key: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }

/**
 * Instant replay. While it is on, the screen is encoded all the time with the hardware encoder and only the last N seconds are kept in memory.
 * One tap on Clip (island, notification or app) writes those seconds to Movies/Densify. Nothing touches storage until you save.
 * It uses the same screen-capture permission as the recorder, so the two can't run at once.
 */
class ReplayService : Service() {
  private val main = Handler(Looper.getMainLooper())
  private var projection: MediaProjection? = null
  private var projectionCb: MediaProjection.Callback? = null
  private var vdisplay: VirtualDisplay? = null
  private var codec: MediaCodec? = null
  private var worker: Thread? = null
  @Volatile private var running = false
  @Volatile private var format: MediaFormat? = null
  private val lock = Any()
  private val frames = ArrayDeque<Frame>()
  private var windowUs = 30_000_000L
  private var saving = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Hub.init(this)
    when (intent?.action) {
      ACTION_START -> {
        goForeground()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null || ReplayState.state != "idle" || RecState.state != "idle") { fail("Couldn't start instant replay."); return START_NOT_STICKY }
        begin(code, data)
      }
      ACTION_SAVE -> save()
      ACTION_STOP -> shutdown()
      else -> if (ReplayState.state == "idle") stopSelf()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() { release(); ReplayState.state = "idle"; super.onDestroy() }

  private fun goForeground() {
    val n = notif("Instant replay", "Starting…")
    if (Build.VERSION.SDK_INT >= 29) startForeground(Notifs.ID_REPLAY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(Notifs.ID_REPLAY, n)
  }

  private fun act(title: String, action: String, code: Int): Notification.Action {
    val pi = PendingIntent.getService(this, code, Intent(this, ReplayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_save), title, pi).build()
  }

  private fun notif(title: String, text: String): Notification {
    val b = Notifs.builder(this, Notifs.CHANNEL_REC).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
    if (ReplayState.state == "buffering") { b.addAction(act("Save clip", ACTION_SAVE, 31)); b.addAction(act("Turn off", ACTION_STOP, 32)) }
    return b.build()
  }

  private fun refreshNotif(text: String) {
    runCatching { getSystemService(NotificationManager::class.java).notify(Notifs.ID_REPLAY, notif("Instant replay on", text)) }
  }

  private fun begin(code: Int, data: Intent) {
    try {
      val secs = ReplayState.load()
      ReplayState.seconds = secs
      windowUs = secs * 1_000_000L
      // Always H.264 at up to 1080p and 30 or 60 fps: light on memory (about 1 MB per second) and every phone can do it.
      val cfg = RecCfg.load().copy(res = RecCfg.load().res.let { if (it == "qhd" || it == "uhd" || it == "native") "fhd" else it }, codec = "h264", mbps = 0, fps = minOf(RecCfg.load().fps, 60))
      val dm = realMetrics(this)
      val p0 = RecPlan.make(cfg, dm.widthPixels, dm.heightPixels)
      val bitrate = p0.bitrate.coerceAtMost(12_000_000)

      val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, p0.w, p0.h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, p0.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
      }
      val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
      mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      val surface: Surface = mc.createInputSurface()
      codec = mc

      val mpm = getSystemService(MediaProjectionManager::class.java)
      val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Android didn't allow screen capture.")
      projection = mp
      val cb = object : MediaProjection.Callback() { override fun onStop() { main.post { shutdown() } } }
      projectionCb = cb
      mp.registerCallback(cb, main)
      vdisplay = mp.createVirtualDisplay("densify-replay", p0.w, p0.h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
      mc.start()

      running = true
      worker = Thread({ drain(mc) }, "densify-replay").also { it.start() }
      ReplayState.error = ""
      ReplayState.info = "${p0.w}x${p0.h} · ${p0.fps} fps · last ${secs}s"
      ReplayState.state = "buffering"
      Hub.log("Instant replay on · ${ReplayState.info}")
      refreshNotif("Keeping the last ${secs}s")
    } catch (t: Throwable) {
      release()
      fail(t.message ?: "Couldn't start instant replay.")
    }
  }

  /** Pulls encoded frames and keeps only the last [windowUs], always starting on a key frame so a saved clip plays from its first frame. */
  private fun drain(mc: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    while (running) {
      val i = try { mc.dequeueOutputBuffer(info, 100_000L) } catch (t: Throwable) { break }
      when {
        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = mc.outputFormat
        i >= 0 -> {
          val buf = mc.getOutputBuffer(i)
          if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            val bytes = ByteArray(info.size)
            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
            val f = Frame(bytes, info.presentationTimeUs, info.flags)
            synchronized(lock) { frames.addLast(f); trim(f.ptsUs) }
          }
          runCatching { mc.releaseOutputBuffer(i, false) }
          if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
      }
    }
  }

  /** Drops everything before the last key frame that is still at least [windowUs] old. Call with the lock held. */
  private fun trim(newest: Long) {
    var cut = -1; var n = 0
    for (f in frames) { if (f.key && newest - f.ptsUs >= windowUs) cut = n; if (newest - f.ptsUs < windowUs) break; n++ }
    while (cut-- > 0) frames.pollFirst()
  }

  private fun save() {
    if (ReplayState.state != "buffering" || saving) return
    val fmt = format
    val snap: List<Frame> = synchronized(lock) { frames.toList() }
    val start = snap.indexOfFirst { it.key }
    if (fmt == null || start < 0) { toast("Nothing to save yet. Give it a moment."); return }
    saving = true
    val clip = snap.subList(start, snap.size)
    Thread({
      var uri: android.net.Uri? = null
      var file: File? = null
      var ok = false
      var name = ""
      try {
        name = "Densify_replay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val mux: MediaMuxer
        var pfd: android.os.ParcelFileDescriptor? = null
        if (Build.VERSION.SDK_INT >= 29) {
          val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Densify"); put(MediaStore.Video.Media.IS_PENDING, 1)
          }
          uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("Couldn't create the file.")
          pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Couldn't open the file.")
          mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
          val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Densify"); dir.mkdirs()
          file = File(dir, name)
          mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        val track = mux.addTrack(fmt)
        mux.start()
        val t0 = clip[0].ptsUs
        val bi = MediaCodec.BufferInfo()
        for (f in clip) {
          bi.set(0, f.data.size, f.ptsUs - t0, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
          mux.writeSampleData(track, java.nio.ByteBuffer.wrap(f.data), bi)
        }
        mux.stop(); mux.release()
        runCatching { pfd?.close() }
        if (uri != null) runCatching { contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
        ok = true
      } catch (t: Throwable) {
        Hub.log("Instant replay save failed: ${t.message}")
        uri?.let { runCatching { contentResolver.delete(it, null, null) } }
        file?.let { runCatching { it.delete() } }
      }
      main.post {
        saving = false
        if (ok) {
          val secs = (clip.last().ptsUs - clip[0].ptsUs) / 1_000_000
          ReplayState.lastSaved = name; ReplayState.savedAt = SystemClock.elapsedRealtime()
          Hub.prefs.edit().putString("recLast", (uri?.toString() ?: file?.let { Uri.fromFile(it).toString() }) ?: "").apply()
          Hub.log("Replay saved: $name (${secs}s)")
          toast("Saved the last ${secs}s")
          Notifs.post(this, Notifs.ID_REC_IDLE + 2, "Replay saved", name)
        } else toast("Couldn't save the clip")
      }
    }, "densify-replay-save").start()
  }

  private fun toast(s: String) { main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show() } }

  private fun shutdown() {
    release()
    ReplayState.state = "idle"
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    stopSelf()
  }

  private fun fail(msg: String) {
    ReplayState.state = "idle"; ReplayState.error = msg
    Hub.log("Instant replay: $msg")
    Notifs.post(this, Notifs.ID_REC_IDLE + 2, "Instant replay stopped", msg)
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    stopSelf()
  }

  private fun release() {
    running = false
    runCatching { worker?.join(500) }
    worker = null
    runCatching { vdisplay?.release() }; vdisplay = null
    projectionCb?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }; projectionCb = null
    runCatching { projection?.stop() }; projection = null
    runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
    synchronized(lock) { frames.clear() }
    format = null
  }

  companion object {
    const val ACTION_START = "expo.modules.densifynative.REPLAY_START"
    const val ACTION_SAVE = "expo.modules.densifynative.REPLAY_SAVE"
    const val ACTION_STOP = "expo.modules.densifynative.REPLAY_STOP"
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"

    fun send(ctx: Context, action: String) { runCatching { ctx.startService(Intent(ctx, ReplayService::class.java).setAction(action)) } }
    fun startFlow(ctx: Context) { runCatching { ctx.startActivity(Intent(ctx, ReplayConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    /** Starts when off, turns off when on. */
    fun toggle(ctx: Context) { if (ReplayState.state == "idle") startFlow(ctx) else send(ctx, ACTION_STOP) }
    fun save(ctx: Context) = send(ctx, ACTION_SAVE)
  }
}

/** Shows Android's screen-capture prompt for instant replay, then hands the answer to the service. */
class ReplayConsentActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Hub.init(this)
    Hub.overGame()
    if (ReplayState.state != "idle" || RecState.state != "idle") {
      Toast.makeText(this, if (RecState.state != "idle") "Stop the recording first" else "Instant replay is already on", Toast.LENGTH_SHORT).show()
      finish(); return
    }
    try {
      val mpm = getSystemService(MediaProjectionManager::class.java)
      val intent = if (Build.VERSION.SDK_INT >= 34 && RecCfg.load().scope == "screen") mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
      @Suppress("DEPRECATION")
      startActivityForResult(intent, REQ)
    } catch (t: Throwable) {
      Toast.makeText(this, "Couldn't open the prompt.", Toast.LENGTH_SHORT).show(); finish()
    }
  }

  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
      val i = Intent(this, ReplayService::class.java).setAction(ReplayService.ACTION_START)
        .putExtra(ReplayService.EXTRA_CODE, resultCode).putExtra(ReplayService.EXTRA_DATA, data)
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    } else Toast.makeText(this, "Instant replay cancelled", Toast.LENGTH_SHORT).show()
    finish()
  }

  companion object { const val REQ = 4411 }
}
