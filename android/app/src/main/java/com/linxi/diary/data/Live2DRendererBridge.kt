package com.linxi.diary.data

import android.content.Context
import android.view.View
import java.io.File

/**
 * Optional entry point for the official Cubism Java/Android renderer.
 *
 * The concrete implementation lives in the opt-in `src/cubism` source set so
 * a normal checkout does not compile or redistribute Live2D's Core AAR.  The
 * reflection boundary keeps the rest of the app (and its tests) independent
 * of that licensed artifact while still allowing a real GL view to be used
 * when the owner supplies matching Core + Framework inputs at build time.
 */
object Live2DRendererBridge {
    private const val FACTORY = "com.linxi.diary.live2d.CubismRendererViewFactory"

    fun isInstalled(classLoader: ClassLoader? = Thread.currentThread().contextClassLoader): Boolean =
        classLoader?.let { loader -> runCatching { Class.forName(FACTORY, false, loader) }.isSuccess } == true

    fun create(
        context: Context,
        modelDirectory: File,
        manifestPath: String,
        classLoader: ClassLoader? = Thread.currentThread().contextClassLoader,
    ): View? {
        val loader = classLoader ?: return null
        return runCatching {
            val factory = Class.forName(FACTORY, true, loader)
            factory.getMethod("create", Context::class.java, File::class.java, String::class.java)
                .invoke(null, context, modelDirectory, manifestPath) as? View
        }.getOrNull()
    }

    fun close(view: View) {
        runCatching { view.javaClass.getMethod("close").invoke(view) }
    }
}
