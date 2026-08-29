/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webdavserver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.Executors

/**
 * Foreground service hosting a [WebDavServer]. Lifecycle mirrors FtpServerService: a single
 * background executor starts/stops the server and publishes state through [stateLiveData].
 */
class WebDavServerService : Service() {
    private var state = State.STOPPED
        set(value) {
            field = value
            _stateLiveData.value = value
        }

    private lateinit var wakeWifiLock: WakeWifiLock

    private val executorService = Executors.newSingleThreadExecutor()

    private var server: WebDavServer? = null

    override fun onCreate() {
        super.onCreate()
        wakeWifiLock = WakeWifiLock(WebDavServerService::class.java.simpleName)
        startForeground(NOTIFICATION_ID, WebDavServerNotification.build(this, state))
        executeStart()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        executeStop()
        executorService.shutdown()
    }

    private fun executeStart() {
        if (state == State.STARTING || state == State.RUNNING) {
            return
        }
        state = State.STARTING
        startForeground(NOTIFICATION_ID, WebDavServerNotification.build(this, state))
        executorService.execute {
            val username = Settings.WEBDAV_SERVER_USERNAME.valueCompat
            val password = Settings.WEBDAV_SERVER_PASSWORD.valueCompat
            val port = Settings.WEBDAV_SERVER_PORT.valueCompat
            val homeDirectory = Settings.WEBDAV_SERVER_HOME_DIRECTORY.valueCompat
            try {
                val webDavServer = WebDavServer(port, homeDirectory, username, password)
                webDavServer.start(SOCKET_READ_TIMEOUT, DEFAULT_BIND_INHERIT)
                server = webDavServer
                wakeWifiLock.isAcquired = true
                state = State.RUNNING
                mainExecutorCompat.execute {
                    startForeground(NOTIFICATION_ID, WebDavServerNotification.build(this, state))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                state = State.STOPPED
                mainExecutorCompat.execute {
                    showToast(R.string.webdav_server_start_error)
                    stopSelf()
                }
            }
        }
    }

    private fun executeStop() {
        if (state == State.STOPPED) {
            return
        }
        executorService.execute {
            try {
                server?.stop()
            } catch (ignored: Exception) {}
            server = null
            wakeWifiLock.isAcquired = false
            state = State.STOPPED
        }
    }

    enum class State {
        STARTING, RUNNING, STOPPED
    }

    companion object {
        private const val NOTIFICATION_ID = 4242
        private const val SOCKET_READ_TIMEOUT = 0
        private const val DEFAULT_BIND_INHERIT = false

        private val _stateLiveData = MutableLiveData(State.STOPPED)
        val stateLiveData: LiveData<State> = _stateLiveData

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WebDavServerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebDavServerService::class.java))
        }
    }
}
