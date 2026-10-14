package com.voxelport.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/** The one ongoing notification both services share (same id, so Android shows one). */
object Notify {
    const val ID = 1
    private const val CHANNEL = "server"

    fun build(ctx: Context, title: String, text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, ServerService::class.java).setAction(ServerService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop server", stop).build())
            .build()
    }

    /** Changes the text of the ongoing notification (e.g. to show today's mobile data use). */
    fun update(ctx: Context, title: String, text: String) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, title, text))
    }

    private const val ALERTS = "data"
    private const val ALERT_ID = 2

    /** A one-off heads-up about data use; separate from the ongoing notification so it makes a sound and can be swiped away. */
    fun alert(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Data use", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            ALERT_ID,
            Notification.Builder(ctx, ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun foreground(svc: Service, title: String, text: String) {
        val n = build(svc, title, text)
        if (Build.VERSION.SDK_INT >= 34) svc.startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else svc.startForeground(ID, n)
    }
}
