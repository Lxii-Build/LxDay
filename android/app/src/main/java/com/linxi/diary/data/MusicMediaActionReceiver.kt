package com.linxi.diary.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.util.Logs

/**
 * Handles the three transport buttons exposed by the system media notification.
 *
 * The receiver is explicit and not exported. It only accepts PendingIntents
 * created by [MusicNotificationController], while the actual work is delegated
 * to the shared playback manager so the in-app and system controls cannot drift.
 */
class MusicMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                MusicNotificationController.ACTION_PREVIOUS ->
                    NeteasePlaybackManager.skipToPrevious()

                MusicNotificationController.ACTION_NEXT ->
                    NeteasePlaybackManager.skipToNext()

                MusicNotificationController.ACTION_PLAY ->
                    NeteasePlaybackManager.resume()

                MusicNotificationController.ACTION_PAUSE ->
                    NeteasePlaybackManager.pause()
            }
        }.onFailure {
            // A media notification can outlive the app's authenticated session;
            // a stale tap must never crash the receiver process.
            Logs.w("Music", "Unable to handle media notification action", it)
        }
    }
}
