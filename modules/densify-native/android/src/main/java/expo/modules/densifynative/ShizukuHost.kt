package expo.modules.densifynative

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.shizuku.api.BinderContainer
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks the Shizuku server protocol (the one AxManager exposes to apps), backed by Densify's own ADB connection.
 * Apps that use the Shizuku API find us the same way: we push a binder to their "<package>.shizuku" provider.
 * Supported: permission flow, version/uid, system properties and newProcess (shell commands with live streams).
 * Not supported: the binder-transact wrapper and user services, which need a process running as the shell user.
 */
object ShizukuHost {
  private const val D_SERVICE = "moe.shizuku.server.IShizukuService"
  private const val D_APP = "moe.shizuku.server.IShizukuApplication"
  private const val D_PROC = "moe.shizuku.server.IRemoteProcess"
  private const val API_PERMISSION = "moe.shizuku.manager.permission.API_V23"
  private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
  private const val VERSION = 13

  private val apps = ConcurrentHashMap<String, IBinder>()
  private val pending = ConcurrentHashMap<String, Int>()
  private var lastForeground: String? = null

  // ---------- grants (shared with the Apps screen, stored in prefs) ----------

  private fun grants(): JSONObject = runCatching { JSONObject(Hub.prefs.getString("apiGrants", "{}") ?: "{}") }.getOrDefault(JSONObject())
  fun stateOf(pkg: String): String = if (pkg.isEmpty()) "deny" else grants().optString(pkg, "none")
  private fun setState(pkg: String, v: String) { val g = grants(); g.put(pkg, v); Hub.prefs.edit().putString("apiGrants", g.toString()).apply() }

  private fun callerPackage(): String = Hub.app.packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: ""

  // ---------- pushing our binder to apps that use the Shizuku API ----------

  /** Called regularly. When an app that uses the Shizuku API comes to the front, hand it our binder. */
  fun tick(ctx: Context, foreground: String?) {
    if (foreground == null || foreground == ctx.packageName) { lastForeground = foreground; return }
    dispatchPending()
    if (foreground == lastForeground) return
    lastForeground = foreground
    val uses = runCatching {
      ctx.packageManager.getPackageInfo(foreground, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(API_PERMISSION) == true
    }.getOrDefault(false)
    if (uses) sendBinder(ctx, foreground)
  }

  fun sendBinder(ctx: Context, pkg: String) {
    runCatching {
      val extras = Bundle().apply { putParcelable(EXTRA_BINDER, BinderContainer(service)) }
      ctx.contentResolver.call(Uri.parse("content://$pkg.shizuku"), "sendBinder", null, extras)
      Hub.log("Offered Densify's service to $pkg")
    }.onFailure { Hub.log("Couldn't reach $pkg's Shizuku provider: ${it.message}") }
  }

  /** Tells apps waiting on a permission prompt what the user decided in the Apps screen. */
  fun dispatchPending() {
    for ((pkg, code) in pending.entries.toList()) {
      val st = stateOf(pkg)
      if (st == "ask") continue
      pending.remove(pkg)
      val app = apps[pkg] ?: continue
      val b = Bundle().apply { putBoolean("shizuku:request-permission-reply-allowed", st == "allow") }
      callApp(app, 2) { it.writeInt(code); it.writeInt(1); b.writeToParcel(it, 0) }
    }
  }

  private fun callApp(app: IBinder, code: Int, write: (Parcel) -> Unit) {
    val data = Parcel.obtain()
    try {
      data.writeInterfaceToken(D_APP)
      write(data)
      app.transact(code, data, null, IBinder.FLAG_ONEWAY)
    } catch (_: Throwable) {
    } finally { data.recycle() }
  }

  private fun bindApplication(app: IBinder, pkg: String) {
    val st = stateOf(pkg)
    val b = Bundle().apply {
      putInt("shizuku:attach-reply-version", VERSION)
      putInt("shizuku:attach-reply-patch-version", 5)
      putInt("shizuku:attach-reply-uid", uid())
      putString("shizuku:attach-reply-secontext", "u:r:shell:s0")
      putBoolean("shizuku:attach-reply-permission-granted", st == "allow")
      putBoolean("shizuku:attach-reply-should-show-request-permission-rationale", st == "deny")
    }
    callApp(app, 1) { it.writeInt(1); b.writeToParcel(it, 0) }
  }

  private fun uid(): Int = if (Hub.engine.config().mode == "root") 0 else 2000
  private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

  // ---------- the service binder ----------

  val service: IBinder = object : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
      val known = code in intArrayOf(2, 3, 4, 7, 8, 9, 10, 11, 12, 14, 15, 16, 17, 100, 101, 102, 103, 104, 105, 106)
      if (!known) return super.onTransact(code, data, reply, flags)
      data.enforceInterface(D_SERVICE)
      val pkg = callerPackage()
      val allowed = stateOf(pkg) == "allow"
      val open = code in intArrayOf(2, 3, 14, 15, 16, 17, 100)
      try {
        if (!open && !allowed) {
          if (stateOf(pkg) == "none") { setState(pkg, "ask"); notifyAsk(pkg) }
          reply?.writeException(SecurityException("Densify has not approved $pkg. Open Densify > Developer > App access."))
          return true
        }
        when (code) {
          2 -> { reply?.writeNoException(); reply?.writeInt(VERSION) }
          3 -> { reply?.writeNoException(); reply?.writeInt(uid()) }
          4 -> { data.readString(); reply?.writeNoException(); reply?.writeInt(if (allowed) 0 else -1) }
          7 -> {
            val cmd = data.createStringArray() ?: emptyArray()
            val env = data.createStringArray()
            val dir = data.readString()
            val proc = newProcess(cmd, env, dir)
            reply?.writeNoException(); reply?.writeStrongBinder(proc)
          }
          8 -> { reply?.writeNoException(); reply?.writeString("u:r:shell:s0") }
          9 -> {
            val name = data.readString().orEmpty(); val def = data.readString()
            val v = runBlocking(Dispatchers.IO) { Hub.adb.exec("getprop ${shq(name)}") }.getOrNull()?.trim().orEmpty()
            reply?.writeNoException(); reply?.writeString(if (v.isEmpty()) def else v)
          }
          10 -> {
            val name = data.readString().orEmpty(); val v = data.readString().orEmpty()
            runBlocking(Dispatchers.IO) { Hub.adb.exec("setprop ${shq(name)} ${shq(v)}") }
            reply?.writeNoException()
          }
          11 -> reply?.writeException(UnsupportedOperationException("User services need a shell-level server, which Densify does not run."))
          12 -> { reply?.writeNoException(); reply?.writeInt(0) }
          14 -> {
            val rc = data.readInt()
            when (stateOf(pkg)) {
              "allow" -> apps[pkg]?.let { app -> callApp(app, 2) { it.writeInt(rc); it.writeInt(1); Bundle().apply { putBoolean("shizuku:request-permission-reply-allowed", true) }.writeToParcel(it, 0) } }
              "none", "ask" -> { setState(pkg, "ask"); pending[pkg] = rc; notifyAsk(pkg) }
              else -> apps[pkg]?.let { app -> callApp(app, 2) { it.writeInt(rc); it.writeInt(1); Bundle().apply { putBoolean("shizuku:request-permission-reply-allowed", false) }.writeToParcel(it, 0) } }
            }
            reply?.writeNoException()
          }
          15 -> { reply?.writeNoException(); reply?.writeInt(if (allowed) 1 else 0) }
          16 -> { reply?.writeNoException(); reply?.writeInt(if (stateOf(pkg) == "deny") 1 else 0) }
          17 -> {
            val app = data.readStrongBinder()
            if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data)
            if (app != null) { apps[pkg] = app; bindApplication(app, pkg) }
            reply?.writeNoException()
          }
          100, 101, 106 -> reply?.writeNoException()
          102, 104 -> { /* oneway */ }
          103 -> { reply?.writeNoException(); reply?.writeInt(0) }
          105 -> { reply?.writeNoException(); reply?.writeInt(0) }
        }
      } catch (t: Throwable) {
        reply?.writeException(IllegalStateException(t.message ?: "Failed"))
      }
      return true
    }
  }

  private fun notifyAsk(pkg: String) {
    Notifs.post(Hub.app, Notifs.ID_BATTERY + 1, "App wants Densify access", "$pkg asked to run commands. Open Developer > App access to allow or deny.")
  }

  // ---------- newProcess ----------

  private fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IBinder {
    val line = StringBuilder()
    if (!dir.isNullOrEmpty()) line.append("cd ").append(shq(dir)).append(" 2>/dev/null; ")
    env?.forEach { if (it.contains("=")) line.append("export ").append(it.substringBefore("=")).append("=").append(shq(it.substringAfter("="))).append("; ") }
    line.append("exec ").append(cmd.joinToString(" ") { shq(it) })
    val p = runBlocking(Dispatchers.IO) { Hub.adb.openProcess(line.toString()) }.getOrThrow()
    return RemoteProcess(p)
  }

  /** The IRemoteProcess binder: pipes connect the client to the command running over ADB. */
  private class RemoteProcess(private val p: ProcLike) : Binder() {
    private val stdinPipe = ParcelFileDescriptor.createPipe()   // [0] we read, [1] client writes
    private val stdoutPipe = ParcelFileDescriptor.createPipe()  // [0] client reads, [1] we write
    private val stderrPipe = ParcelFileDescriptor.createPipe()
    @Volatile private var done = false

    init {
      stderrPipe[1].close()
      Thread {
        runCatching { FileOutputStream(stdoutPipe[1].fileDescriptor).use { out -> val buf = ByteArray(8192); while (true) { val n = p.input.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() } } }
        done = true
        runCatching { stdoutPipe[1].close() }
      }.start()
      Thread {
        runCatching { FileInputStream(stdinPipe[0].fileDescriptor).use { inp -> val buf = ByteArray(8192); while (true) { val n = inp.read(buf); if (n < 0) break; p.output.write(buf, 0, n); p.output.flush() } } }
      }.start()
    }

    private fun pfd(reply: Parcel?, f: ParcelFileDescriptor) {
      reply?.writeNoException(); reply?.writeInt(1); f.writeToParcel(reply!!, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
      if (code !in 1..8) return super.onTransact(code, data, reply, flags)
      data.enforceInterface(D_PROC)
      when (code) {
        1 -> pfd(reply, stdinPipe[1])
        2 -> pfd(reply, stdoutPipe[0])
        3 -> pfd(reply, stderrPipe[0])
        4 -> { while (!done) Thread.sleep(50); reply?.writeNoException(); reply?.writeInt(p.exitValue() ?: 0) }
        5 -> { reply?.writeNoException(); reply?.writeInt(p.exitValue() ?: 0) }
        6 -> { p.destroy(); reply?.writeNoException() }
        7 -> { reply?.writeNoException(); reply?.writeInt(if (done) 0 else 1) }
        8 -> {
          val t = data.readLong(); data.readString()
          val end = System.currentTimeMillis() + t
          while (!done && System.currentTimeMillis() < end) Thread.sleep(50)
          reply?.writeNoException(); reply?.writeInt(if (done) 1 else 0)
        }
      }
      return true
    }
  }
}
