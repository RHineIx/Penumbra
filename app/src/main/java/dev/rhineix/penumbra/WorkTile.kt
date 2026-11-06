package dev.rhineix.penumbra

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class WorkTile : TileService() {
    override fun onStartListening() {
        val active=runCatching {
            val engine=ProfileEngine(this)
            if(engine.isManaged) engine.canManage
            else engine.workUsers().any { !engine.users.isQuietModeEnabled(it) }
        }.getOrDefault(false)
        qsTile?.apply { state = if(active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
    // Intent overload is used only on Android versions preceding the PendingIntent API.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("workControls", true)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
    }
}
