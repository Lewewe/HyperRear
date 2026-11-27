package com.os4.rear

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** HyperRear's settings, written here through LSPosed and read by [RearHooks] in the rear launcher. */
object RearSettings {
    const val GROUP = "settings"
    /** Our card in place of the native music card. */
    const val ENABLED = "enabled"
    const val VOLUME_RING = "volumeRing"

    /** The rear launcher answers this ordered broadcast while [RearHooks] is loaded there. */
    const val PING = "com.os4.rear.PING"
    const val LOADED = 1
    /** The module APK's modified time, as the loaded hook sees it: an older hook answers another. */
    const val APK_TIME = "apkTime"

    fun enabled(p: SharedPreferences?) = p?.getBoolean(ENABLED, true) ?: true
    fun volumeRing(p: SharedPreferences?) = p?.getBoolean(VOLUME_RING, true) ?: true
}

class RearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(ScopeRequester)
    }
}

/** LSPosed owns the approval and the actual scope. The callback only tracks the prompt. */
internal object ScopeRequester : XposedServiceHelper.OnServiceListener {
    private var service: XposedService? = null
    private var request: XposedService.OnScopeEventListener? = null
    private var requestedAt = 0L
    private var failed = false

    @Synchronized override fun onServiceBind(service: XposedService) {
        if (this.service !== service) {
            this.service = service
            request = null
            failed = false
        }
    }

    @Synchronized override fun onServiceDied(service: XposedService) {
        if (this.service === service) {
            this.service = null
            request = null
            failed = false
        }
    }

    /** The settings the hook reads; null until LSPosed has bound this app (the module is off). */
    @Synchronized fun settings(): SharedPreferences? =
        service?.let { runCatching { it.getRemotePreferences(RearSettings.GROUP) }.getOrNull() }

    @Synchronized fun state(): String {
        val bound = service ?: return "enable-module"
        val scope = runCatching { bound.scope }.getOrNull() ?: return "enable-module"
        if (RearHooks.SUBSCREEN in scope) {
            request = null
            failed = false
            return "ready"
        }
        if (request != null && SystemClock.elapsedRealtime() - requestedAt >= 60_000L) {
            request = null
            failed = true
        }
        return when {
            request != null -> "awaiting-approval"
            failed -> "approval-failed"
            else -> "request-scopes"
        }
    }

    fun requestScope() {
        val (bound, listener) = synchronized(this) {
            val bound = service ?: return
            if (state() !in listOf("request-scopes", "approval-failed")) return
            val listener = object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scope: List<String>) = finish(this, false)
                override fun onScopeRequestFailed(message: String) = finish(this, true)
            }
            request = listener
            requestedAt = SystemClock.elapsedRealtime()
            failed = false
            bound to listener
        }
        runCatching { bound.requestScope(listOf(RearHooks.SUBSCREEN), listener) }
            .onFailure { finish(listener, true) }
    }

    @Synchronized private fun finish(listener: XposedService.OnScopeEventListener, failed: Boolean) {
        if (request === listener) {
            request = null
            this.failed = failed
        }
    }
}
