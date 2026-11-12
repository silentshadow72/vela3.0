package dev.vela.app

import android.content.Context
import java.io.File

/**
 * Holds what the component factory needs before any Activity exists.
 *
 * AppComponentFactory runs before the application's onCreate in some paths, so
 * these are set as early as possible and read defensively.
 */
object ReasonFiles {
    @Volatile var app: Context? = null
    @Volatile var dir: File? = null

    fun set(context: Context) {
        app = context.applicationContext
        dir = context.filesDir
    }
}
