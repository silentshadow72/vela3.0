package dev.vela.app

import android.util.Log

/**
 * Our side of the Preloader mod-menu ABI.
 *
 * Mod menu ABI: LiteLDev (preloader-android), used with permission.
 *
 * The mod registers its modules into this ABI when it loads; the menu is drawn by
 * the host — us — and not by the mod. So the ball and the shader screen open the
 * menu through here, and this class reports honestly whether the ABI is present
 * rather than pretending a tap did something.
 */
object ModMenuBridge {

    private const val TAG = "VelaModMenu"

    @Volatile
    private var available = false

    private external fun nativeOpenMenu()
    private external fun nativeCloseMenu()
    private external fun nativeSetModuleEnabled(moduleId: String, enabled: Boolean)
    private external fun nativeIsAvailable(): Boolean

    fun load(): Boolean {
        return try {
            System.loadLibrary("vela_modmenu")
            available = nativeIsAvailable()
            Log.i(TAG, "mod menu ABI loaded: $available")
            available
        } catch (t: Throwable) {
            available = false
            Log.w(TAG, "mod menu ABI unavailable", t)
            false
        }
    }

    fun isAvailable(): Boolean = available

    /**
     * Opens the menu the mod registered into.
     *
     * Returns false when the ABI is not loaded, so the caller can say "shader menu
     * not ready" instead of leaving a tap that does nothing.
     */
    fun openMenu(): Boolean {
        if (!available) return false
        return try {
            nativeOpenMenu()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not open the menu", t)
            false
        }
    }

    fun closeMenu(): Boolean {
        if (!available) return false
        return try {
            nativeCloseMenu()
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun setModuleEnabled(moduleId: String, enabled: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeSetModuleEnabled(moduleId, enabled)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
