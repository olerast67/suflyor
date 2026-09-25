package com.olerast.suflyor.overlay

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.olerast.suflyor.App
import com.olerast.suflyor.MainActivity
import com.olerast.suflyor.R
import com.olerast.suflyor.session.SessionEngine

/**
 * Quick Settings tile: pull down the shade, tap "Suflyor", and the floating prompter with the current script appears
 * over whatever app is open. Tap again to stop.
 */
class PrompterTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val app = App.instance
        if (app.engine.state.mode == SessionEngine.Mode.OVERLAY) {
            OverlayHost.stopSession(this)
            refresh()
            return
        }
        // Starting a microphone foreground service needs a visible activity, so go through a see-through one.
        val intent = Intent(this, QuickStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val app = App.instance
        val running = app.engine.state.mode == SessionEngine.Mode.OVERLAY
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (running) getString(R.string.tile_subtitle_listening) else app.scripts.document.title
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, PrompterTileService::class.java)) }
        }
    }
}

/** See-through activity started from the tile: asks for the microphone if needed, starts the session, closes. */
class QuickStartActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else finishQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun start() {
        val problem = OverlayHost.startSession(this)
        if (problem != null) {
            Toast.makeText(this, getString(problem.message), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
