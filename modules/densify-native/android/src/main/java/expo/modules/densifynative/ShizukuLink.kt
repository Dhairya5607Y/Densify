package expo.modules.densifynative

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.api.BinderContainer
import java.io.ByteArrayOutputStream

/**
 * Densify as a Shizuku CLIENT. When Shizuku is running and has allowed Densify, every command runs through it with shell
 * rights, so no Wireless Debugging pairing is needed at all. Speaks the same binder protocol that [ShizukuHost] serves.
 * No library is used on purpose: it keeps the app small and avoids clashing with the BinderContainer class already in this module.
 */
object ShizukuLink {
  private const val D_SERVICE = "moe.shizuku.server.IShizukuService"
  private const val D_APP = "moe.shizuku.server.IShizukuApplication"
  private const val D_PROC = "moe.shizuku.server.IRemoteProcess"
  const val MANAGER_PKG = "moe.shizuku.privileged.api"
  private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
  private const val REQUEST_CODE = 4107

  enum class Status { MISSING, STOPPED, NEEDS_PERMISSION, READY }

  @Volatile private var service: IBinder? = null
  @Volatile private var granted = false

  /** Cheap and never blocks, so it is safe to read from any thread. */
  val ready: Boolean get() = service?.isBinderAlive == true && granted

  // ---------- Shizuku calling us back ----------

  private val appBinder = object : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
      when (code) {
        1 -> { // bindApplication: Shizuku tells us whether we are already allowed
          data.enforceInterface(D_APP)
          val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
          if (b != null) granted = b.getBoolean("shizuku:attach-reply-permission-granted", false)
          return true
        }
        2 -> { // dispatchRequestPermissionResult: the user answered the prompt
          data.enforceInterface(D_APP)
          data.readInt()
          val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
          granted = b?.getBoolean("shizuku:request-permission-reply-allowed", false) == true
          return true
        }
        3 -> return true // showPermissionConfirmation: nothing to do
      }
      return super.onTransact(code, data, reply, flags)
    }
  }

  /** Shizuku handed us its binder (through our provider, or because we asked for it). */
  fun onBinder(b: IBinder) {
    service = b
    granted = false
    runCatching { b.linkToDeath(IBinder.DeathRecipient { if (service === b) { service = null; granted = false } }, 0) }
    Thread {
      runCatching { attach(b) }
      refreshGranted()
      Hub.log("Shizuku found. ${if (granted) "Densify is allowed." else "Waiting for permission."}")
    }.start()
  }

  private fun attach(b: IBinder) {
    val d = Parcel.obtain(); val r = Parcel.obtain()
    try {
      d.writeInterfaceToken(D_SERVICE)
      d.writeStrongBinder(appBinder)
      d.writeInt(1)
      Bundle().apply {
        putInt("shizuku:attach-args-api-version", 13)
        putString("shizuku:attach-args-package-name", Hub.app.packageName)
      }.writeToParcel(d, 0)
      b.transact(17, d, r, 0)
      r.readException()
    } finally { d.recycle(); r.recycle() }
  }

  /** Asks the Shizuku app for its binder, for when it started after Densify did. */
  private fun fetch(ctx: Context) {
    runCatching {
      val reply = ctx.contentResolver.call(Uri.parse("content://$MANAGER_PKG.shizuku"), "getBinder", null, Bundle()) ?: return
      reply.classLoader = BinderContainer::class.java.classLoader
      @Suppress("DEPRECATION")
      val c = reply.getParcelable<BinderContainer>(EXTRA_BINDER)
      c?.binder?.let { if (it.isBinderAlive && it !== service) onBinder(it) }
    }
  }

  // ---------- state ----------

  private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
    val b = service ?: throw IllegalStateException("Shizuku is not running.")
    val d = Parcel.obtain(); val r = Parcel.obtain()
    try {
      d.writeInterfaceToken(D_SERVICE)
      write(d)
      b.transact(code, d, r, 0)
      r.readException()
      return read(r)
    } finally { d.recycle(); r.recycle() }
  }

  private fun refreshGranted() { granted = runCatching { call(15, { }, { it.readInt() != 0 }) }.getOrDefault(granted) }

  /** Talks to Shizuku, so call it off the main thread. */
  fun status(ctx: Context): Status {
    if (service?.isBinderAlive != true) fetch(ctx)
    val s = service
    if (s != null && s.isBinderAlive) {
      refreshGranted()
      return if (granted) Status.READY else Status.NEEDS_PERMISSION
    }
    val installed = runCatching { ctx.packageManager.getPackageInfo(MANAGER_PKG, 0); true }.getOrDefault(false)
    return if (installed) Status.STOPPED else Status.MISSING
  }

  fun explain(st: Status): String = when (st) {
    Status.MISSING -> "Shizuku isn't installed. Install and start Shizuku, then allow Densify in it."
    Status.STOPPED -> "Shizuku isn't running. Open Shizuku and start it, then try again."
    Status.NEEDS_PERMISSION -> "Allow Densify in Shizuku, then try again."
    Status.READY -> ""
  }

  /** Shows Shizuku's own "allow this app?" prompt. The answer arrives through [appBinder]. */
  fun requestPermission(ctx: Context): Status {
    val st = status(ctx)
    if (st != Status.NEEDS_PERMISSION) return st
    runCatching { call(14, { it.writeInt(REQUEST_CODE) }, { }) }
    return st
  }

  fun open(ctx: Context): Boolean = runCatching {
    val i = ctx.packageManager.getLaunchIntentForPackage(MANAGER_PKG) ?: return false
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
  }.getOrDefault(false)

  // ---------- running commands ----------

  private fun pfd(proc: IBinder, code: Int): ParcelFileDescriptor? {
    val d = Parcel.obtain(); val r = Parcel.obtain()
    try {
      d.writeInterfaceToken(D_PROC)
      proc.transact(code, d, r, 0)
      r.readException()
      return if (r.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(r) else null
    } finally { d.recycle(); r.recycle() }
  }

  private fun procInt(proc: IBinder, code: Int): Int {
    val d = Parcel.obtain(); val r = Parcel.obtain()
    try {
      d.writeInterfaceToken(D_PROC)
      proc.transact(code, d, r, 0)
      r.readException()
      return r.readInt()
    } finally { d.recycle(); r.recycle() }
  }

  /** Starts a process with shell rights. Output (stdout) is read from `input`, stdin is `output`. */
  fun start(cmd: Array<String>): ProcLike {
    val proc = call(7, { it.writeStringArray(cmd); it.writeStringArray(null); it.writeString(null) }, { it.readStrongBinder() })
      ?: throw IllegalStateException("Shizuku refused to start the command.")
    val inFd = pfd(proc, 2) ?: throw IllegalStateException("Shizuku gave no output stream.")
    val outFd = pfd(proc, 1) ?: throw IllegalStateException("Shizuku gave no input stream.")
    return ProcLike(
      ParcelFileDescriptor.AutoCloseInputStream(inFd),
      ParcelFileDescriptor.AutoCloseOutputStream(outFd),
      { runCatching { procInt(proc, 6) } },
      { if (runCatching { procInt(proc, 7) }.getOrDefault(0) != 0) null else runCatching { procInt(proc, 5) }.getOrNull() },
    )
  }

  /** One command, whole output (errors merged in). A watchdog ends it if it hangs. */
  fun exec(cmd: String, timeoutMs: Long = 20_000L): String {
    val p = start(arrayOf("sh", "-c", "exec 2>&1; $cmd"))
    runCatching { p.output.close() }
    val dog = Hub.scope.launch { delay(timeoutMs); p.destroy() }
    try {
      val out = ByteArrayOutputStream()
      val buf = ByteArray(4096)
      while (true) {
        val n = p.input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
      }
      return out.toString("UTF-8")
    } finally { dog.cancel(); runCatching { p.input.close() } }
  }
}

/**
 * Shizuku sends its binder here when Densify starts (it finds apps that ask for its permission and whose provider
 * is named "<package>.shizuku"). Protected by INTERACT_ACROSS_USERS_FULL, so only the system side can call it.
 */
class DensifyShizukuProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    if (method != "sendBinder" || extras == null) return null
    val c = context ?: return null
    Hub.init(c)
    extras.classLoader = BinderContainer::class.java.classLoader
    @Suppress("DEPRECATION")
    val container = extras.getParcelable<BinderContainer>("moe.shizuku.privileged.api.intent.extra.BINDER")
    container?.binder?.let { ShizukuLink.onBinder(it) }
    return Bundle()
  }

  override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
