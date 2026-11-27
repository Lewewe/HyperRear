package com.os4.rear

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * HyperRear inside com.xiaomi.subscreencenter, the rear screen's launcher.
 *
 * - The music card (SmartAssistant widget `unified.music`) is a MAML widget built by the card's
 *   `r(Context)`; its view is swapped for [RearCardView], so no MAML is shown.
 * - The system only gives the card to whitelisted players (`m2.a.c`). The package SystemUI
 *   reports as the top media session (`m2.d.q` checks that bundle) is let through too, so every
 *   player gets it.
 *
 * shortcut: obfuscated names are subscreencenter RELEASE-1.0.2609231642's (versionCode
 * 426092316); a missing one logs and leaves the native card. Find them by string anchors or
 * DexKit once a second version has to work.
 */
class RearHooks : XposedModule() {
    @Volatile private var topMediaPkg = ""
    /** Set from HMC-E's Extensions page through HyperRear's bridge; null if LSPosed has none for us. */
    private val settings: SharedPreferences? by lazy {
        runCatching { getRemotePreferences(RearSettings.GROUP) }
            .onFailure { log(Log.WARN, TAG, "no settings, defaults used", it) }.getOrNull()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SUBSCREEN || !param.isFirstPackage) return
        val cl = param.classLoader
        runCatching { hookCard(cl) }.onFailure { log(Log.ERROR, TAG, "card hook failed", it) }
        runCatching { hookWhitelist(cl) }.onFailure { log(Log.ERROR, TAG, "whitelist hook failed", it) }
        runCatching { answerPing() }.onFailure { log(Log.ERROR, TAG, "ping receiver failed", it) }
    }

    /** Tells HyperRear's bridge (HMC-E's setup) that this build of the hook is loaded here. */
    private fun answerPing() {
        val apkTime = java.io.File(moduleApplicationInfo.sourceDir).lastModified()
        hook(Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java))
            .intercept { chain ->
                val result = chain.proceed()
                (chain.getArg(0) as Application).registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        resultCode = RearSettings.LOADED
                        getResultExtras(true).putLong(RearSettings.APK_TIME, apkTime)
                    }
                }, IntentFilter(RearSettings.PING), Context.RECEIVER_EXPORTED)
                result
            }
    }

    private fun hookCard(cl: ClassLoader) {
        // The card's Bundle: the only Bundle field of the cards' base class.
        val bundleField = cl.loadClass("q2.v").declaredFields.single { it.type == Bundle::class.java }
            .apply { isAccessible = true }
        // The panel's double tap (Z1.d, DoubleClickGesture) sleeps the rear screen unless its `k`
        // is set during the touch; the native MAML card sets it ("interceptDoubleClick") from its
        // buttons. Its gestures see each event before the views, so setting it on our ACTION_DOWN
        // holds for that tap.
        val doubleClick = cl.loadClass("Z1.d")
        val intercept = doubleClick.getDeclaredField("k").apply { isAccessible = true }
        fun gestureOf(context: Context): Any? {
            var c: Context? = context
            while (c != null && c !is android.app.Activity) c = (c as? android.content.ContextWrapper)?.baseContext
            val activity = c ?: return null
            val field = generateSequence<Class<*>>(activity.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.type == doubleClick } ?: return null
            return field.apply { isAccessible = true }.get(activity)
        }
        hook(cl.loadClass("q2.k").getDeclaredMethod("r", Context::class.java)).intercept { chain ->
            val native = chain.proceed()
            val card = bundleField.get(chain.thisObject) as? Bundle
            if (card?.getString("package_name") != MUSIC_CARD) return@intercept native
            // Read when a card is built: HMC-E restarts the rear launcher when this changes.
            if (!RearSettings.enabled(settings)) return@intercept native
            val context = chain.getArg(0) as Context
            RearMedia.update(context, card.getBundle("media_data"))
            log(Log.INFO, TAG, "music card swapped for ${RearMedia.key}, art=${RearMedia.art?.width}x${RearMedia.art?.height}, " +
                "shown=${RearMedia.shown}, running=${RearMedia.running}, dur=${RearMedia.duration}")
            // shortcut: the MAML view is still built and loaded off-screen (the card keeps sending
            // it AOD/edit commands); stop building it when RearCardView grows its own AOD.
            (native as? View)?.let {
                RearCardView(context, {
                    runCatching { gestureOf(context)?.let { g -> intercept.setBoolean(g, true) } }
                        .onFailure { log(Log.WARN, TAG, "double tap not held", it) }
                }, { RearSettings.volumeRing(settings) }, { RearSettings.progressRing(settings) })
            } ?: native
        }
        // The notification card's K(view) passes its Bundle to the MAML view whenever the data
        // changes (another player, new extras): the same moment feeds RearMedia.
        cl.loadClass("q2.n").declaredMethods.filter { it.name == "K" && it.parameterCount == 1 }.forEach { k ->
            hook(k).intercept { chain ->
                val result = chain.proceed()
                val card = bundleField.get(chain.thisObject) as? Bundle
                if (card?.getString("package_name") == MUSIC_CARD) {
                    val view = (chain.getArg(0) as? View)
                    view?.context?.let { RearMedia.update(it, card.getBundle("media_data")) }
                }
                result
            }
        }
    }

    private fun hookWhitelist(cl: ClassLoader) {
        hook(cl.loadClass("m2.d").getDeclaredMethod("q", Bundle::class.java)).intercept { chain ->
            val ok = chain.proceed() as Boolean
            if (ok) topMediaPkg = (chain.getArg(0) as Bundle).getString("packageName", "")
            ok
        }
        hook(cl.loadClass("m2.a").getDeclaredMethod("c", String::class.java)).intercept { chain ->
            val native = chain.proceed() as Boolean
            native || (chain.getArg(0) as String?).let { it != null && it.isNotEmpty() && it == topMediaPkg }
        }
    }

    companion object {
        const val TAG = "HyperRear"
        const val SUBSCREEN = "com.xiaomi.subscreencenter"
        const val MUSIC_CARD = "unified.music"
    }
}
