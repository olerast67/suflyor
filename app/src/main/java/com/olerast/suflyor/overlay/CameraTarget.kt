package com.olerast.suflyor.overlay

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.annotation.StringRes
import com.olerast.suflyor.R

/** App that "Over the camera" opens right after the floating prompter appears. */
enum class CameraTarget(@StringRes val label: Int) {
    INSTAGRAM(R.string.camera_target_instagram),
    TIKTOK(R.string.camera_target_tiktok),
    CAMERA(R.string.camera_target_camera),
    NONE(R.string.camera_target_window_only);

    fun launchIntent(context: Context): Intent? {
        val pm = context.packageManager
        val intent = when (this) {
            INSTAGRAM -> pm.getLaunchIntentForPackage("com.instagram.android")
            TIKTOK -> TIKTOK_PACKAGES.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            CAMERA -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            NONE -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        } ?: return null
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isAvailable(context: Context): Boolean =
        this == NONE || this == CAMERA || launchIntent(context) != null

    companion object {
        /** Global, Asia and Chinese builds of TikTok. */
        val TIKTOK_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme")

        fun from(name: String): CameraTarget = entries.firstOrNull { it.name == name } ?: INSTAGRAM
    }
}
