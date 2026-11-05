package expo.modules.densifynative

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch

/** Tap: step through the user's quick smallest-width values (from config). */
class DensifyTile : TileService() {
  override fun onStartListening() { Hub.init(this); refresh(null) }
  override fun onClick() {
    Hub.init(this)
    Hub.scope.launch {
      val q = Hub.engine.config().quick
      if (q.isEmpty()) return@launch
      val cur = Hub.adb.readDisplay().getOrNull()?.sw ?: Hub.engine.state.value.appliedSw ?: 0
      val next = q.firstOrNull { it > cur } ?: q.first()
      val r = Hub.engine.adjustSw(next)
      refresh(if (r.isSuccess) next else null)
    }
  }
  private fun refresh(sw: Int?) {
    val t = qsTile ?: return
    t.state = if (Hub.adb.isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    t.label = "Density"
    val sub = sw?.let { "SW $it" } ?: Hub.engine.state.value.appliedSw?.let { "SW $it" } ?: "Tap to change"
    if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = sub
    t.updateTile()
  }
}
