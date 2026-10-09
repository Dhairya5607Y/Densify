package expo.modules.densifynative

import android.Manifest
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class DensifyNativeModule : Module() {
  private val ctx: Context get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private fun run(promise: Promise, block: suspend () -> Any?) {
    scope.launch(Dispatchers.IO) {
      try { promise.resolve(block()) } catch (t: Throwable) { promise.reject("E_DENSIFY", t.message ?: "Unknown error", t) }
    }
  }
  private fun <T> Result<T>.orThrow(): T = getOrElse { throw IllegalStateException(it.message ?: "Failed") }

  override fun definition() = ModuleDefinition {
    Name("DensifyNative")
    Events("onAdb", "onEngine")

    OnCreate {
      Hub.init(ctx)
      scope.launch { Hub.adb.state.collect { sendEvent("onAdb", mapOf("state" to it.name, "message" to it.message)) } }
      scope.launch { Hub.engine.state.collect { sendEvent("onEngine", mapOf("running" to it.running, "appliedDpi" to it.appliedDpi, "activePkg" to it.activePkg, "errTitle" to it.errTitle, "errHint" to it.errHint)) } }
    }
    OnDestroy { scope.cancel() }

    // ---- storage (JS-owned state + derived engine config) ----
    Function("storageGet") { key: String -> Hub.prefs.getString(key, null) }
    Function("storageSet") { key: String, value: String -> Hub.prefs.edit().putString(key, value).apply(); true }
    Function("setFlags") { monitoring: Boolean, restoreOnBoot: Boolean -> Hub.prefs.edit().putBoolean("monitoring", monitoring).putBoolean("restoreOnBoot", restoreOnBoot).apply(); true }

    // ---- permissions and state checks ----
    Function("hasUsageAccess") { Hub.detector.hasAccess() }
    Function("hasNotifications") {
      Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    Function("isBatteryUnrestricted") { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    Function("hasSecureSettings") { ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED }
    Function("adbConnected") { Hub.adb.isConnected }
    Function("isMonitoring") { Hub.engine.state.value.running }
    Function("battery") { val b = batteryInfo(ctx); mapOf("level" to b.level, "charging" to b.charging, "tempC" to b.tempC.toDouble()) }
    Function("openSettings") { kind: String ->
      val i = when (kind) {
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else -> Intent(Settings.ACTION_SETTINGS)
      }
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      runCatching { ctx.startActivity(i) }.isSuccess
    }
    Function("requestAddTile") {
      if (Build.VERSION.SDK_INT >= 33) {
        runCatching {
          ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(ctx, DensifyTile::class.java), "Density", android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_manage), ctx.mainExecutor) { }
        }.isSuccess
      } else false
    }

    // ---- apps ----
    AsyncFunction("getInstalledApps") { promise: Promise ->
      run(promise) {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(q, 0).mapNotNull { ri ->
          val ai = ri.activityInfo.applicationInfo
          if (ai.packageName == ctx.packageName) null else {
            @Suppress("DEPRECATION")
            val game = ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            mapOf("pkg" to ai.packageName, "name" to ri.loadLabel(pm).toString(), "isGame" to game)
          }
        }.distinctBy { it["pkg"] }.sortedBy { (it["name"] as String).lowercase() }
      }
    }
    AsyncFunction("getAppIcon") { pkg: String, promise: Promise ->
      run(promise) {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); d.setBounds(0, 0, 96, 96); d.draw(c)
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
      }
    }

    // ---- wireless debugging ----
    Function("startPairing") { ctx.startForegroundService(Intent(ctx, PairingService::class.java)); true }
    AsyncFunction("connect") { promise: Promise -> run(promise) { Hub.adb.connect().orThrow(); true } }
    AsyncFunction("grantSelf") { promise: Promise -> run(promise) { Hub.adb.grantSelf().orThrow(); true } }
    AsyncFunction("capabilities") { promise: Promise ->
      run(promise) {
        val connected = Hub.adb.isConnected || Hub.adb.connect().isSuccess
        mapOf("hz" to Hub.maxHz().toInt(), "perf" to (connected && Hub.adb.perfSupported()), "guard" to false, "tile" to (Build.VERSION.SDK_INT >= 33))
      }
    }

    // ---- density ----
    AsyncFunction("readDensity") { promise: Promise -> run(promise) { val d = Hub.adb.readDensity().orThrow(); mapOf("physical" to d.physical, "effective" to d.effective) } }
    AsyncFunction("setDensity") { dpi: Int, promise: Promise -> run(promise) { Hub.adb.setDensity(dpi).orThrow() } }
    AsyncFunction("restoreDefault") { promise: Promise -> run(promise) { Hub.engine.restoreAll().orThrow() } }

    // ---- monitoring ----
    Function("startMonitoring") { MonitorService.start(ctx); true }
    Function("stopMonitoring") { MonitorService.stop(ctx); true }
  }
}
