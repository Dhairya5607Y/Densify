package expo.modules.densifynative

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch

/** Tap: step through the user's quick DPI values (from config). */
class DensifyTile : TileService() {
  override fun onStartListening() { Hub.init(this); refresh(null) }
  override fun onClick() {
    Hub.init(this)
    Hub.scope.launch {
      val q = Hub.engine.config().quick
      if (q.isEmpty()) return@launch
      val cur = Hub.adb.readDensity().getOrNull()?.effective
      val next = q.firstOrNull { it > (cur ?: 0) } ?: q.first()
      val r = Hub.adb.setDensity(next)
      refresh(if (r.isSuccess) next else null)
    }
  }
  private fun refresh(dpi: Int?) {
    val t = qsTile ?: return
    t.state = if (Hub.adb.isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    t.label = "Density"
    t.subtitle = dpi?.let { "$it DPI" } ?: Hub.engine.state.value.appliedDpi?.let { "$it DPI" } ?: "Tap to change"
    t.updateTile()
  }
}
