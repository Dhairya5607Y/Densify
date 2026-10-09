package expo.modules.densifynative

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

class PairingReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    Hub.init(ctx)
    val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
    val port = intent.getIntExtra(EXTRA_PORT, -1)
    val pending = goAsync()
    Hub.scope.launch {
      val r: Result<Unit> =
        if (code.isNullOrEmpty() || port <= 0) Result.failure(IllegalArgumentException("No code entered."))
        else Hub.adb.pair(port, code).let { p -> if (p.isSuccess) Hub.adb.connect() else p }
      val ok = r.isSuccess
      Notifs.post(ctx, Notifs.ID_PAIRING, if (ok) "Densify is connected" else "Pairing failed", if (ok) "You can return to Densify." else (r.exceptionOrNull()?.message ?: "Try again."))
      ctx.stopService(Intent(ctx, PairingService::class.java))
      pending.finish()
    }
  }
  companion object { const val KEY_CODE = "pairing_code"; const val EXTRA_PORT = "port" }
}
