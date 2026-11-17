package app.shoalwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the app's process after the phone restarts, and does nothing else: [ShoalwatchApp.onCreate] then sends the outbox and
 * requeues waiting downloads, as on any start (see "Sync outbox" in docs/ARCHITECTURE.md).
 *
 * Why it exists (Milestone 5 device run, D-1): WorkManager's jobs do not survive a reboot. Its own RescheduleReceiver puts them
 * back, but WorkManager switches that receiver on and off at run time, and Android saves the switch lazily, so a reboot soon after
 * a change could find it off with work still queued. This one is always on, never exported, and starts nothing while nothing waits.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
