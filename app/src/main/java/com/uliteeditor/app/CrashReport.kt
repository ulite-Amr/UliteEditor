package com.uliteeditor.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/** Device + app identity included in every crash report. */
internal data class DeviceDetails(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersion: String,
)

/** Collects device + app identity without touching any UI. */
internal fun collectDeviceDetails(context: Context): DeviceDetails {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return DeviceDetails(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        appVersion = "${info.versionName} (${info.versionCode})",
    )
}

/** Compiles the human-readable crash report block. */
internal fun buildReport(
    device: DeviceDetails,
    threadName: String,
    cause: String,
    message: String,
    stackTrace: String,
): String = buildString {
    appendLine("UliteEditor crash report")
    appendLine()
    appendLine("Device: ${device.manufacturer} ${device.model}")
    appendLine("Android: ${device.androidRelease} (API ${device.sdkInt})")
    appendLine("App: ${device.appVersion}")
    appendLine("Thread: $threadName")
    appendLine("Exception: $cause")
    if (message.isNotBlank()) appendLine("Message: $message")
    appendLine()
    appendLine("Stack trace:")
    append(stackTrace)
}

/**
 * Puts a report on the platform clipboard. Safe to call from the crashing
 * thread; returns false if the clipboard service is unavailable.
 */
internal fun copyReportToClipboard(context: Context, report: String): Boolean {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
    manager.setPrimaryClip(ClipData.newPlainText("UliteEditor crash report", report))
    return true
}