package com.galaxytv.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings

data class DeviceIdentity(
    val macAddress: String,
    val serialNumber: String,
    val chipId: String,
    val model: String,
    val firmwareVersion: String
) {
    val activationUserAgent: String
        get() = "Maghreb $model $firmwareVersion"

    companion object {
        fun read(context: Context): DeviceIdentity {
            return DeviceIdentity(
                macAddress = DeviceMacAddress.read().orEmpty(),
                serialNumber = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ).orEmpty(),
                chipId = "",
                model = Build.MODEL.ifBlank { Build.DEVICE },
                firmwareVersion = Build.DISPLAY.ifBlank { Build.VERSION.RELEASE }
            )
        }
    }
}

object DeviceProfile {
    private const val LOW_RAM_THRESHOLD_BYTES = 2L * 1024L * 1024L * 1024L
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun currentIdentity(): DeviceIdentity {
        check(::appContext.isInitialized) { "Device profile has not been initialized" }
        return DeviceIdentity.read(appContext)
    }

    fun useLiteApi(): Boolean {
        check(::appContext.isInitialized) { "Device profile has not been initialized" }
        val memoryInfo = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(memoryInfo)
        return shouldUseLite(memoryInfo.totalMem, Runtime.getRuntime().availableProcessors())
    }

    internal fun shouldUseLite(totalMemoryBytes: Long, processorCount: Int): Boolean {
        return totalMemoryBytes <= LOW_RAM_THRESHOLD_BYTES || processorCount <= 2
    }
}

class GalaxyApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        ServerRuntime.initialize(this)
        DeviceProfile.initialize(this)
    }
}
