package com.example.overlay_mobile

import android.content.Intent

/**
 * Process-wide holder for the MediaProjection consent token.
 *
 * The token is captured in MainActivity.onActivityResult and later consumed by
 * ScreenCaptureService when it calls MediaProjectionManager.getMediaProjection().
 * Volatile ensures visibility across threads without a full lock.
 */
object ProjectionConsent {
    @Volatile var resultCode: Int = -1
    @Volatile var data: Intent? = null

    val isGranted: Boolean get() = data != null

    fun clear() {
        resultCode = -1
        data = null
    }
}
