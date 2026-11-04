package expo.modules.densifynative

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

data class Rom(val name: String, val version: String)

/** What phone and Android flavour this is, so setup can pick the method that works and give advice that fits. */
object DeviceInfo {
  private var props: Map<String, String>? = null

  /** Every system property, read once. `getprop` works for apps on every Android version. */
  @Synchronized private fun dump(): Map<String, String> {
    props?.let { return it }
    val map = HashMap<String, String>()
    runCatching {
      val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
      val re = Regex("^\\[(.*?)]: \\[(.*)]$")
      p.inputStream.bufferedReader().useLines { lines -> lines.forEach { l -> re.find(l)?.let { map[it.groupValues[1]] = it.groupValues[2] } } }
      p.waitFor()
    }
    props = map
    return map
  }

  fun prop(name: String): String = dump()[name].orEmpty()

  /** One property, read right now (values like the Wireless Debugging port change at runtime). */
  fun fresh(name: String): String = runCatching {
    val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
    val s = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    s
  }.getOrDefault("")

  private fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { k -> dump()[k]?.takeIf { it.isNotBlank() } }.orEmpty()

  fun rom(): Rom {
    val who = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
    return when {
      who.contains("oneplus") -> Rom("OxygenOS", first("ro.build.version.oplusrom.display", "ro.build.version.oplusrom", "ro.oxygen.version", "ro.rom.version"))
      who.contains("oppo") -> Rom("ColorOS", first("ro.build.version.oplusrom.display", "ro.build.version.oplusrom", "ro.build.version.opporom"))
      who.contains("realme") -> Rom("realme UI", first("ro.build.version.realmeui", "ro.build.version.oplusrom"))
      first("ro.mi.os.version.name").isNotEmpty() -> Rom("HyperOS", first("ro.mi.os.version.name"))
      first("ro.miui.ui.version.name").isNotEmpty() -> Rom("MIUI", first("ro.miui.ui.version.name"))
      who.contains("samsung") -> Rom("One UI", first("ro.build.version.oneui"))
      who.contains("vivo") || who.contains("iqoo") -> Rom("OriginOS / Funtouch OS", first("ro.vivo.os.version", "ro.vivo.os.name"))
      who.contains("huawei") || who.contains("honor") -> Rom("EMUI / MagicOS", first("ro.build.version.emui", "ro.build.version.magic"))
      who.contains("nothing") -> Rom("Nothing OS", first("ro.build.version.nothing", "ro.nothing.version"))
      who.contains("motorola") -> Rom("Motorola Hello UI", "")
      who.contains("google") -> Rom("Pixel (stock Android)", "")
      else -> Rom("Android", "")
    }
  }

  private fun tips(rom: Rom): List<String> = when (rom.name) {
    "OxygenOS", "ColorOS", "realme UI" -> listOf(
      "Keep Wi-Fi connected while pairing. ${rom.name} drops Wireless Debugging the moment Wi-Fi changes.",
      "Settings > Battery > Densify: set to Unrestricted and allow Auto-launch, or ${rom.name} will stop the connection in the background.",
      "In Developer options turn on “Disable adb authorization timeout” so the pairing never expires.",
      "If the pairing code window closes by itself, use the notification method: Densify keeps its own field open.",
    )
    "HyperOS", "MIUI" -> listOf(
      "Allow Autostart for Densify and set its battery saver to No restrictions.",
      "Turn on “USB debugging (Security settings)” in Developer options.",
      "Keep Wi-Fi connected while pairing.",
    )
    "One UI" -> listOf(
      "Turn off Auto Blocker if Wireless Debugging refuses to start.",
      "Settings > Battery > Background usage limits: remove Densify from sleeping apps.",
    )
    else -> listOf("Keep Wi-Fi connected while pairing and set Densify's battery to Unrestricted.")
  }

  /** A short sentence added to a connection error so the advice matches the phone. */
  fun hint(): String = when (rom().name) {
    "OxygenOS", "ColorOS", "realme UI" -> " On ${rom().name}: stay on Wi-Fi, set Densify's battery to Unrestricted and allow Auto-launch. Shizuku also works without pairing."
    "HyperOS", "MIUI" -> " On ${rom().name}: allow Autostart and turn on USB debugging (Security settings). Shizuku also works without pairing."
    else -> " Shizuku also works without pairing."
  }

  private fun wifiOn(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }

  /** Talks to Shizuku, so call it off the main thread. */
  fun snapshot(ctx: Context): JSONObject {
    val cr = ctx.contentResolver
    fun g(k: String): Int = runCatching { Settings.Global.getInt(cr, k, 0) }.getOrDefault(0)
    val rom = rom()
    val sdk = Build.VERSION.SDK_INT
    val shz = ShizukuLink.status(ctx)
    val rec = if (shz == ShizukuLink.Status.READY) "shizuku" else if (sdk < 30) "tcpip" else "wireless"
    return JSONObject()
      .put("sdk", sdk)
      .put("release", Build.VERSION.RELEASE)
      .put("brand", Build.BRAND)
      .put("model", Build.MODEL)
      .put("rom", rom.name)
      .put("romVersion", rom.version)
      .put("wifi", wifiOn(ctx))
      .put("wirelessSupported", sdk >= 30)
      .put("devOptions", g("development_settings_enabled") == 1)
      .put("usbDebugging", g("adb_enabled") == 1)
      .put("wirelessDebugging", g("adb_wifi_enabled") == 1)
      .put("secureSettings", ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
      .put("shizuku", shz.name)
      .put("recommended", rec)
      .put("tips", JSONArray(tips(rom)))
  }
}
