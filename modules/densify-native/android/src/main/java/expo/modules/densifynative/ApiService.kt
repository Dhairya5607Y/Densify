package expo.modules.densifynative

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Lets other apps use Densify's ADB access, with a per-app approval the user controls in Densify.
 * Bind with action "com.densify.app.api.BIND" and call transact():
 *   1 exec(String cmd)  -> reply: exception or String output
 *   2 setDensity(Int)   -> reply: exception or Int
 *   3 restore()         -> reply: exception or Int
 *   4 ping()            -> reply: String "densify"
 * Descriptor: "com.densify.app.IDensifyApi". An app that is not approved gets a SecurityException and shows up in Densify's Apps screen.
 */
class ApiService : Service() {
  override fun onBind(intent: Intent?): IBinder {
    Hub.init(this)
    return object : Binder() {
      override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in 1..4) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val pkg = packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: ""
        val state = grantOf(pkg)
        if (state != "allow") {
          if (state == "none") markSeen(pkg)
          reply?.writeException(SecurityException("Densify has not approved $pkg. Open Densify > Apps to allow it."))
          return true
        }
        try {
          when (code) {
            4 -> { reply?.writeNoException(); reply?.writeString("densify") }
            1 -> {
              val cmd = data.readString().orEmpty()
              Hub.log("API ($pkg): ${cmd.take(60)}")
              val out = runBlocking(Dispatchers.IO) { Hub.adb.exec(cmd) }.getOrThrow()
              reply?.writeNoException(); reply?.writeString(out)
            }
            2 -> {
              val dpi = data.readInt()
              Hub.log("API ($pkg): set $dpi DPI")
              val r = runBlocking(Dispatchers.IO) { Hub.adb.setDensity(dpi) }.getOrThrow()
              reply?.writeNoException(); reply?.writeInt(r)
            }
            3 -> {
              Hub.log("API ($pkg): restore")
              val r = runBlocking(Dispatchers.IO) { Hub.engine.restoreAll() }.getOrThrow()
              reply?.writeNoException(); reply?.writeInt(r)
            }
          }
        } catch (t: Throwable) {
          reply?.writeException(IllegalStateException(t.message ?: "Failed"))
        }
        return true
      }
    }
  }

  private fun grants(): JSONObject = runCatching { JSONObject(Hub.prefs.getString("apiGrants", "{}") ?: "{}") }.getOrDefault(JSONObject())
  private fun grantOf(pkg: String): String = if (pkg.isEmpty()) "deny" else grants().optString(pkg, "none")

  /** Remember that an app asked, so the Apps screen can offer Allow / Deny. */
  private fun markSeen(pkg: String) {
    if (pkg.isEmpty()) return
    val g = grants()
    g.put(pkg, "ask")
    Hub.prefs.edit().putString("apiGrants", g.toString()).apply()
    Notifs.post(this, Notifs.ID_BATTERY + 1, "App wants Densify access", "$pkg asked to use Densify. Open Apps to allow or deny.")
  }

  companion object { const val DESCRIPTOR = "com.densify.app.IDensifyApi" }
}
