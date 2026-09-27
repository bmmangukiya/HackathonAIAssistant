package com.hackathon.assistant

import android.content.Context
import android.util.Log

/**
 * Lights the iQOO/vivo camera ring ("Dynamic light" / Monster Halo) while Jarvis works.
 *
 * Uses the same call vivo Settings makes for its colour preview:
 * `getSystemService("vlight_manager_service").startLight(502, pkg)`, which returns a light id
 * that `stopLightById` switches off. 502 is the atmospheric-light scene, so the ring uses the
 * colour picked in Settings > Dynamic light. Reflection because it's a vivo-only API; on any
 * other phone (no such service) every call is a silent no-op.
 */
class HaloLight(private val context: Context) {
    private val svc: Any? by lazy { runCatching { context.getSystemService(SERVICE) }.getOrNull() }
    @Volatile private var lightId = -1

    @Synchronized fun on() {
        if (lightId >= 0) return
        val s = svc ?: return
        lightId = runCatching {
            s.javaClass.getMethod("startLight", Int::class.javaPrimitiveType, String::class.java)
                .invoke(s, ATMOSPHERE_SCENE, context.packageName) as Int
        }.onFailure { Log.w(TAG, "startLight failed: ${it.cause ?: it}") }.getOrDefault(-1)
        Log.i(TAG, "halo on, id=$lightId")
    }

    @Synchronized fun off() {
        val id = lightId.takeIf { it >= 0 } ?: return
        lightId = -1
        val s = svc ?: return
        runCatching {
            s.javaClass.getMethod("stopLightById", Int::class.javaPrimitiveType).invoke(s, id)
        }.onFailure { Log.w(TAG, "stopLightById failed: ${it.cause ?: it}") }
        Log.i(TAG, "halo off, id=$id")
        clear()
    }

    /** Switch off any ring light this app left running (e.g. from a previous process). */
    @Synchronized fun clear() {
        lightId = -1
        val s = svc ?: return
        runCatching {
            s.javaClass.getMethod("stopLightByLightType", Int::class.javaPrimitiveType, String::class.java)
                .invoke(s, ATMOSPHERE_SCENE, context.packageName)
        }.onFailure { Log.w(TAG, "stopLightByLightType failed: ${it.cause ?: it}") }
    }

    private companion object {
        const val TAG = "Halo"
        const val SERVICE = "vlight_manager_service"
        const val ATMOSPHERE_SCENE = 502
    }
}
