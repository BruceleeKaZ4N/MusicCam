package dev.musiccam.prototype

import android.content.Context
import android.util.Log
import java.io.File

/** Private fallback evidence; never includes authorization Intents or device identifiers. */
object PocLog {
    const val TAG = "MusicCamPoC"

    @Synchronized
    fun event(context: Context, message: String) {
        val written = Log.i(TAG, message)
        try {
            val file = File(context.filesDir, "phase1-events.log")
            if (file.length() > 256 * 1024) file.writeText("")
            file.appendText("${System.currentTimeMillis()} pid=${android.os.Process.myPid()} logWrite=$written $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Private diagnostic write failed: ${e.javaClass.simpleName}")
        }
    }
}
