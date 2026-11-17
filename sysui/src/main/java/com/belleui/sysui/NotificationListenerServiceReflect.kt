package com.belleui.sysui

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/** registerAsSystemService is a system API, so it is called by name. */
object NotificationListenerServiceReflect {
    fun register(l: NotificationListenerService, ctx: Context) {
        NotificationListenerService::class.java
            .getMethod("registerAsSystemService", Context::class.java, ComponentName::class.java, Int::class.javaPrimitiveType)
            .invoke(l, ctx, ComponentName(ctx.packageName, SysListener::class.java.name), -1)
    }
}
