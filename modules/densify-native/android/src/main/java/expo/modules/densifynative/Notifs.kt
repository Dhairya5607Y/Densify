package expo.modules.densifynative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

object Notifs {
  const val CHANNEL = "densify"
  const val CHANNEL_REC = "densify_rec"
  const val ID_MONITOR = 1
  const val ID_PAIRING = 2
  const val ID_BATTERY = 3
  const val ID_REC = 5
  const val ID_REC_IDLE = 6

  fun ensure(ctx: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null)
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Densify", NotificationManager.IMPORTANCE_LOW))
    if (nm.getNotificationChannel(CHANNEL_REC) == null)
      nm.createNotificationChannel(NotificationChannel(CHANNEL_REC, "Screen recorder", NotificationManager.IMPORTANCE_LOW))
  }

  fun builder(ctx: Context, channel: String = CHANNEL): Notification.Builder {
    ensure(ctx)
    @Suppress("DEPRECATION")
    val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx).setPriority(Notification.PRIORITY_LOW)
    return b.setSmallIcon(android.R.drawable.ic_menu_manage)
  }

  fun post(ctx: Context, id: Int, title: String, text: String) {
    runCatching {
      ctx.getSystemService(NotificationManager::class.java)
        .notify(id, builder(ctx).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }
  }

  /** An action that talks to the monitor service. */
  fun action(ctx: Context, title: String, act: String, code: Int): Notification.Action {
    val pi = PendingIntent.getService(ctx, code, Intent(ctx, MonitorService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_menu_manage), title, pi).build()
  }

  /** An action that talks to the recorder service (pause, resume, stop). */
  fun recAction(ctx: Context, title: String, act: String, code: Int): Notification.Action {
    val pi = PendingIntent.getService(ctx, code, Intent(ctx, RecorderService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_media_pause), title, pi).build()
  }

  /** An action that opens the transparent screen-capture consent screen, which is the only way to start a recording. */
  fun recStartAction(ctx: Context, title: String, code: Int): Notification.Action {
    val pi = PendingIntent.getActivity(ctx, code, Intent(ctx, RecordConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_media_play), title, pi).build()
  }
}
