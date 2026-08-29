package com.uliteeditor.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Installs an uncaught-exception handler that opens a professional
 * CrashReportActivity (device details + raw stack trace) instead of letting
 * release builds die silently. Debug builds keep the default crash behavior
 * so development stays fast and visible.
 */
class UliteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}

/**
 * Surfaces a crash to the report screen. The previous handler is chained as
 * a fallback only if the report screen itself cannot be shown (e.g. out of
 * memory), so the process still terminates instead of hanging silently.
 */
internal class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val intent = Intent(context, CrashReportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CrashReportActivity.EXTRA_THREAD, thread.name)
                putExtra(CrashReportActivity.EXTRA_CAUSE, throwable.javaClass.name)
                putExtra(CrashReportActivity.EXTRA_MESSAGE, throwable.message.orEmpty())
                putExtra(CrashReportActivity.EXTRA_STACK_TRACE, Log.getStackTraceString(throwable))
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            previous?.uncaughtException(thread, throwable)
        }
    }
}