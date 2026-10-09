package expo.modules.densifynative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notifs {
  const val CHANNEL = "densify"
  const val ID_MONITOR = 1
  const val ID_PAIRING = 2
  const val ID_BATTERY = 3

  fun ensure(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null)
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Densify", NotificationManager.IMPORTANCE_LOW))
  }

  fun builder(ctx: Context): Notification.Builder {
    ensure(ctx)
    return Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
  }

  fun post(ctx: Context, id: Int, title: String, text: String) {
    runCatching {
      ctx.getSystemService(NotificationManager::class.java)
        .notify(id, builder(ctx).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }
  }

  fun action(ctx: Context, title: String, act: String, code: Int): Notification.Action {
    val pi = PendingIntent.getService(ctx, code, Intent(ctx, MonitorService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_menu_manage), title, pi).build()
  }
}
