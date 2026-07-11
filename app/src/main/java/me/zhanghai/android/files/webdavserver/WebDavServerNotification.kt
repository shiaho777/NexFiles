/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webdavserver

import android.app.Notification
import android.app.Service
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate

/**
 * Foreground notification template for [WebDavServerService]. Mirrors [FtpServerNotification]'s
 * structure so the two servers look consistent, with their own channel so a user can mute them
 * independently.
 */
private val notificationTemplate = NotificationTemplate(
    NotificationChannelTemplate(
        "webdav_server",
        R.string.webdav_server_notification_channel_name,
        NotificationManagerCompat.IMPORTANCE_LOW,
        descriptionRes = R.string.webdav_server_notification_channel_description,
        showBadge = false
    ),
    colorRes = R.color.color_primary,
    smallIcon = R.drawable.notification_icon,
    contentTitleRes = R.string.webdav_server_notification_title,
    ongoing = true,
    onlyAlertOnce = true,
    category = NotificationCompat.CATEGORY_SERVICE,
    priority = NotificationCompat.PRIORITY_LOW
)

object WebDavServerNotification {
    fun build(service: Service, state: WebDavServerService.State): Notification {
        val builder = notificationTemplate.createBuilder(service).apply {
            setContentText(when (state) {
                WebDavServerService.State.STARTING ->
                    service.getString(R.string.webdav_server_notification_text_starting)
                WebDavServerService.State.RUNNING ->
                    service.getString(R.string.webdav_server_notification_text_running)
                WebDavServerService.State.STOPPED ->
                    service.getString(R.string.webdav_server_notification_text_stopped)
            })
        }
        return builder.build()
    }
}
