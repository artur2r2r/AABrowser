/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class WebViewMediaSessionBridge(
    context: Context,
    private val onPlayRequested: () -> Unit,
    private val onPauseRequested: () -> Unit,
    private val onSkipForwardRequested: () -> Unit,
    private val onSkipBackwardRequested: () -> Unit,
    private val onSeekRequested: (Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var receiverRegistered = false
    private var isSessionActive = false
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WebMediaSessionService.ACTION_WEB_MEDIA_COMMAND) return

            when (intent.getIntExtra(WebMediaSessionService.EXTRA_COMMAND_TYPE, -1)) {
                WebMediaSessionService.COMMAND_PLAY -> onPlayRequested()
                WebMediaSessionService.COMMAND_PAUSE -> onPauseRequested()
                WebMediaSessionService.COMMAND_SKIP_FORWARD -> onSkipForwardRequested()
                WebMediaSessionService.COMMAND_SKIP_BACKWARD -> onSkipBackwardRequested()
                WebMediaSessionService.COMMAND_SEEK -> {
                    val positionMs = intent.getLongExtra(WebMediaSessionService.EXTRA_SEEK_POSITION, 0L)
                    onSeekRequested(positionMs)
                }
            }
        }
    }

    init {
        ensureServiceRunning()
    }

    fun onHostResume() {
        if (isMonitoring) return
        isMonitoring = true
        registerReceiverIfNeeded()
        startPlaybackStateMonitoring()
    }

    fun onHostPause() {
        if (!isMonitoring) return
        isMonitoring = false
        stopPlaybackStateMonitoring()
        updateSessionActive(false)
        unregisterReceiverIfNeeded()
    }

    fun release() {
        onHostPause()
        stopServiceIfRunning()
    }

    private fun startPlaybackStateMonitoring() {
        handler.post(playbackMonitor)
    }

    private fun stopPlaybackStateMonitoring() {
        handler.removeCallbacks(playbackMonitor)
    }

    private val playbackMonitor = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            val nowPlaying = audioManager?.isMusicActive == true
            updateSessionActive(nowPlaying)
            handler.postDelayed(this, PLAYBACK_MONITOR_INTERVAL_MS)
        }
    }

    private fun updateSessionActive(active: Boolean) {
        if (isSessionActive == active) return
        isSessionActive = active
        sendSessionState(active)
    }

    fun updateNowPlaying(title: String?, subtitle: String?) {
        runCatching {
            val intent = Intent(appContext, WebMediaSessionService::class.java).apply {
                action = WebMediaSessionService.ACTION_UPDATE_METADATA
                putExtra(WebMediaSessionService.EXTRA_TITLE, title.orEmpty())
                putExtra(WebMediaSessionService.EXTRA_SUBTITLE, subtitle.orEmpty())
            }
            appContext.startService(intent)
        }
    }

    fun updatePlaybackState(currentTimeSec: Double, durationSec: Double, isPaused: Boolean) {
        runCatching {
            val intent = Intent(appContext, WebMediaSessionService::class.java).apply {
                action = WebMediaSessionService.ACTION_UPDATE_STATE
                putExtra(WebMediaSessionService.EXTRA_POSITION, currentTimeSec)
                putExtra(WebMediaSessionService.EXTRA_DURATION, durationSec)
                putExtra(WebMediaSessionService.EXTRA_PAUSED, isPaused)
            }
            appContext.startService(intent)
        }
    }

    private fun ensureServiceRunning() {
        runCatching {
            val intent = Intent(appContext, WebMediaSessionService::class.java).apply {
                action = WebMediaSessionService.ACTION_INIT
            }
            appContext.startService(intent)
        }
    }

    private fun sendSessionState(active: Boolean) {
        runCatching {
            val intent = Intent(appContext, WebMediaSessionService::class.java).apply {
                action = WebMediaSessionService.ACTION_SET_ACTIVE
                putExtra(WebMediaSessionService.EXTRA_ACTIVE, active)
            }
            appContext.startService(intent)
        }
    }

    private fun stopServiceIfRunning() {
        runCatching {
            val intent = Intent(appContext, WebMediaSessionService::class.java).apply {
                action = WebMediaSessionService.ACTION_STOP
            }
            appContext.startService(intent)
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter(WebMediaSessionService.ACTION_WEB_MEDIA_COMMAND)
        ContextCompat.registerReceiver(
            appContext,
            commandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(commandReceiver)
        receiverRegistered = false
    }

    companion object {
        private const val PLAYBACK_MONITOR_INTERVAL_MS = 1000L
    }
}
