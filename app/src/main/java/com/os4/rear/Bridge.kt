package com.os4.rear

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HyperRear has no screen of its own: HyperMusicCover-Enhanced's Extensions page reads and sets it
 * through [call], as it does its other extensions. Only HMC-E, signed like this app, is answered.
 * - `query`: its state, see [state].
 * - `configure`: any of [RearSettings.ENABLED], [RearSettings.VOLUME_RING], [RearSettings.PROGRESS_RING]; returns the state.
 * - `requestScopes`: asks LSPosed for the rear launcher.
 */
class Bridge : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        val uid = Binder.getCallingUid()
        if (method == "wake" && uid == 0) return Bundle()
        // HyperOS can hide the host's package name; its signature permission still shows.
        if (!(uid >= android.os.Process.FIRST_APPLICATION_UID &&
                c.checkCallingPermission(MANAGE_EXTENSION) == PackageManager.PERMISSION_GRANTED)) {
            val caller = runCatching { callingPackage }.getOrNull()
            if (caller != HMC || c.packageManager.checkSignatures(caller, c.packageName) !=
                PackageManager.SIGNATURE_MATCH) return null
        }
        when (method) {
            "configure" -> extras?.let { e ->
                ScopeRequester.settings()?.edit()?.apply {
                    for (key in listOf(RearSettings.ENABLED, RearSettings.VOLUME_RING, RearSettings.PROGRESS_RING)) {
                        if (e.containsKey(key)) putBoolean(key, e.getBoolean(key))
                    }
                }?.commit()
            }
            "requestScopes" -> ScopeRequester.requestScope()
            "query" -> Unit
            else -> return null
        }
        return state(c)
    }

    /** Whether the rear launcher has this build's [RearHooks] loaded; null when it did not answer in time. */
    private fun hookActive(c: Context): Boolean? {
        val done = CountDownLatch(1)
        var active = false
        val apkTime = File(c.applicationInfo.sourceDir).lastModified()
        c.sendOrderedBroadcast(Intent(RearSettings.PING).setPackage(RearHooks.SUBSCREEN)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    active = resultCode == RearSettings.LOADED && apkTime > 0 &&
                        getResultExtras(false)?.getLong(RearSettings.APK_TIME) == apkTime
                    done.countDown()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null)
        return if (done.await(HOOK_ANSWER_MS, TimeUnit.MILLISECONDS)) active else null
    }

    private fun state(c: Context) = Bundle().apply {
        putBoolean("installed", true)
        putInt("setupProtocol", 1)
        val scopeState = ScopeRequester.state()
        putString("scopeState", scopeState)
        putBoolean("scopesGranted", scopeState == "ready")
        hookActive(c)?.let { putBoolean("hookActive", it) }
        putString("version", runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull())
        val settings = ScopeRequester.settings()
        putBoolean(RearSettings.ENABLED, RearSettings.enabled(settings))
        putBoolean(RearSettings.VOLUME_RING, RearSettings.volumeRing(settings))
        putBoolean(RearSettings.PROGRESS_RING, RearSettings.progressRing(settings))
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    private companion object {
        const val HMC = "com.yzc26623.HyperMusicCoverEnhanced"
        const val MANAGE_EXTENSION = "com.yzc26623.HyperRear.permission.MANAGE_EXTENSION"
        const val HOOK_ANSWER_MS = 1_500L
    }
}
