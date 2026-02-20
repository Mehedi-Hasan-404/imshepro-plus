package com.livetvpro.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

object DeviceUtils {

    enum class DeviceType {
        PHONE,
        TABLET,
        TV,
        WATCH,
        AUTOMOTIVE,
        FOLDABLE
    }

    var deviceType: DeviceType = DeviceType.PHONE
        private set

    val isTvDevice: Boolean get() = deviceType == DeviceType.TV
    val isTablet: Boolean get() = deviceType == DeviceType.TABLET
    val isPhone: Boolean get() = deviceType == DeviceType.PHONE
    val isWatch: Boolean get() = deviceType == DeviceType.WATCH
    val isAutomotive: Boolean get() = deviceType == DeviceType.AUTOMOTIVE
    val isFoldable: Boolean get() = deviceType == DeviceType.FOLDABLE

    fun init(context: Context) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        deviceType = when (uiModeManager.currentModeType) {
            Configuration.UI_MODE_TYPE_TELEVISION -> DeviceType.TV
            Configuration.UI_MODE_TYPE_WATCH -> DeviceType.WATCH
            Configuration.UI_MODE_TYPE_CAR -> DeviceType.AUTOMOTIVE
            else -> detectHandheld(context)
        }

        // Fire TV may not report UI_MODE_TYPE_TELEVISION on older Fire OS
        if (deviceType != DeviceType.TV) {
            if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
                deviceType = DeviceType.TV
            }
        }
    }

    private fun detectHandheld(context: Context): DeviceType {
        // Foldable: has hinge sensor feature (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")) {
                return DeviceType.FOLDABLE
            }
        }

        // Tablet: smallest screen width >= 600dp
        val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
        if (smallestWidthDp >= 600) {
            return DeviceType.TABLET
        }

        return DeviceType.PHONE
    }
}
