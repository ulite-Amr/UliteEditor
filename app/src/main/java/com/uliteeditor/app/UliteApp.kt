package com.uliteeditor.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Installs an uncaught-exception handler that surfaces crashes for
 * non-debuggable builds: the report is auto-copied to the clipboard (the
 * crash screen may never paint), then a professional CrashReportActivity with
 * device details and the raw stack trace is opened. Debug builds keep the
 * default crash behavior so development stays fast and visible.
 */
class UliteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Session log file opened at app start (and closed on the activity's
        // onStop): the source of caret/alignment ground truth on a device with
        // no adb. Reached via the share-logs action or filesDir/logs.
        EditorLog.open(this)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}

/**
 * Handles a crash. The report is copied to the platform clipboard first
 * (cheap, and works even if the process dies before the screen can show),
 * then the report activity is opened. The previous handler is chained only
 * as a fallback when the report screen cannot be shown (e.g. out of memory),
 * so the process still terminates instead of hanging silently.
 */
internal class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val threadName = thread.name
        val cause = throwable.javaClass.name
        val message = throwable.message.orEmpty()
        val stackTrace = Log.getStackTraceString(throwable)
        val report = buildReport(
            device = collectDeviceDetails(context),
            threadName = threadName,
            cause = cause,
            message = message,
            stackTrace = stackTrace,
        )
        try {
            copyReportToClipboard(context, report)
            val intent = Intent(context, CrashReportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CrashReportActivity.EXTRA_THREAD, threadName)
                putExtra(CrashReportActivity.EXTRA_CAUSE, cause)
                putExtra(CrashReportActivity.EXTRA_MESSAGE, message)
                putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            previous?.uncaughtException(thread, throwable)
        }
    }
}