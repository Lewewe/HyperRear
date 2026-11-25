package com.os4.rear

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

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
    @Volatile private var lastVolumeKey = 0L

    /**
     * Every volume key press reaches AudioService.adjustSuggestedStreamVolume, even one it then
     * ignores (nothing playing, front screen off); the rear card is told so it can show its ring,
     * and with the front screen off the press moves the music volume.
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching {
            val audio = param.classLoader.loadClass("com.android.server.audio.AudioService")
            val ctx = audio.getDeclaredField("mContext").apply { isAccessible = true }
            audio.declaredMethods.filter { it.name == "adjustSuggestedStreamVolume" }.forEach { m ->
                hook(m).intercept { chain ->
                    val context = ctx.get(chain.thisObject) as Context
                    val now = SystemClock.uptimeMillis()
                    if (now - lastVolumeKey > 50) {   // one press runs through several overloads
                        lastVolumeKey = now
                        val id = Binder.clearCallingIdentity()
                        runCatching { context.sendBroadcast(Intent(VOLUME_KEY).setPackage(SUBSCREEN)) }
                        Binder.restoreCallingIdentity(id)
                    }
                    // Screen off, the music stream not playing: AudioService takes a press with
                    // FLAG_SHOW_UI as "open the panel" while the panel is hidden, and the front
                    // panel never shows, so no press would move the volume. Drop the flag there.
                    val args = chain.args.toTypedArray()
                    val flags = args.getOrNull(2) as? Int
                    if (flags != null && flags and AudioManager.FLAG_SHOW_UI != 0 &&
                        context.getSystemService(PowerManager::class.java)?.isInteractive == false) {
                        args[2] = flags and AudioManager.FLAG_SHOW_UI.inv()
                        chain.proceed(args)
                    } else chain.proceed()
                }
            }
        }.onFailure { log(Log.ERROR, TAG, "volume key hook failed", it) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SUBSCREEN || !param.isFirstPackage) return
        val cl = param.classLoader
        runCatching { hookCard(cl) }.onFailure { log(Log.ERROR, TAG, "card hook failed", it) }
        runCatching { hookWhitelist(cl) }.onFailure { log(Log.ERROR, TAG, "whitelist hook failed", it) }
    }

    private fun hookCard(cl: ClassLoader) {
        // The card's Bundle: the only Bundle field of the cards' base class.
        val bundleField = cl.loadClass("q2.v").declaredFields.single { it.type == Bundle::class.java }
            .apply { isAccessible = true }
        hook(cl.loadClass("q2.k").getDeclaredMethod("r", Context::class.java)).intercept { chain ->
            val native = chain.proceed()
            val card = bundleField.get(chain.thisObject) as? Bundle
            if (card?.getString("package_name") != MUSIC_CARD) return@intercept native
            val context = chain.getArg(0) as Context
            RearMedia.update(context, card.getBundle("media_data"))
            log(Log.INFO, TAG, "music card swapped for ${RearMedia.key}, art=${RearMedia.art?.width}x${RearMedia.art?.height}, " +
                "shown=${RearMedia.shown}, running=${RearMedia.running}, dur=${RearMedia.duration}")
            // shortcut: the MAML view is still built and loaded off-screen (the card keeps sending
            // it AOD/edit commands); stop building it when RearCardView grows its own AOD.
            (native as? View)?.let { RearCardView(context) } ?: native
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
        const val VOLUME_KEY = "com.os4.rear.VOLUME_KEY"
    }
}
