package expo.modules.densifynative

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder

/** Finds the pairing port via mDNS, then shows a notification with a text field for the 6-digit code. */
class PairingService : Service() {
  private lateinit var nsd: NsdManager
  private var listener: NsdManager.DiscoveryListener? = null
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Hub.init(this)
    startForeground(Notifs.ID_PAIRING,
      Notifs.builder(this).setContentTitle("Looking for pairing port…").setContentText("In Wireless Debugging, tap “Pair device with pairing code”.").setOngoing(true).build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    nsd = getSystemService(NsdManager::class.java)
    discover()
    return START_NOT_STICKY
  }

  private fun note(n: Notification) { runCatching { getSystemService(NotificationManager::class.java).notify(Notifs.ID_PAIRING, n) } }

  @Suppress("DEPRECATION")
  private fun discover() {
    val l = object : NsdManager.DiscoveryListener {
      override fun onServiceFound(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
          override fun onServiceResolved(r: NsdServiceInfo) { stopDiscovery(); showInput(r.port) }
          override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
        })
      }
      override fun onDiscoveryStarted(t: String) {}
      override fun onDiscoveryStopped(t: String) {}
      override fun onServiceLost(i: NsdServiceInfo) {}
      override fun onStartDiscoveryFailed(t: String, code: Int) {
        note(Notifs.builder(this@PairingService).setContentTitle("Pairing failed").setContentText("Couldn't search for the pairing port.").build()); stopSelf()
      }
      override fun onStopDiscoveryFailed(t: String, code: Int) {}
    }
    listener = l
    nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l)
  }

  private fun stopDiscovery() { listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null }

  private fun showInput(port: Int) {
    val remote = RemoteInput.Builder(PairingReceiver.KEY_CODE).setLabel("Pairing code").build()
    val pi = PendingIntent.getBroadcast(this, 0, Intent(this, PairingReceiver::class.java).setPackage(packageName).putExtra(PairingReceiver.EXTRA_PORT, port),
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val action = Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_manage), "Enter pairing code", pi).addRemoteInput(remote).build()
    note(Notifs.builder(this).setContentTitle("Enter the 6-digit pairing code").setContentText("Pairing port found. Type the code shown by Android.").addAction(action).setOngoing(true).build())
  }

  override fun onDestroy() { stopDiscovery(); super.onDestroy() }
}
