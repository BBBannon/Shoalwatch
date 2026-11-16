package app.shoalwatch.data.sync

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.shoalwatch.data.downloads.DownloadNotifications
import app.shoalwatch.data.downloads.DownloadWorker
import app.shoalwatch.data.downloads.QueueRun
import app.shoalwatch.data.downloads.ShoalwatchWorkerFactory
import app.shoalwatch.ui.FakeConnections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * A tick just before the app is closed: the row is committed and the job enqueued in the same call, so when the process dies
 * before the in-process send gets through, the job (in a new process: a new store, sender and worker) sends it, once.
 */
class ClosedAfterTickTest : SyncSenderTestBase() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Volatile private var nextProcess: SyncSender? = null

    @Test fun aRowWhoseSendDiedWithTheProcessIsSentOnceByTheJob() {
        val parts = DownloadWorker.Parts({ _, _ -> QueueRun.Drained }, DownloadNotifications(context), {}, { 0 }, { false }, { 0L })
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(ShoalwatchWorkerFactory({ parts }, { nextProcess!! })).build(),
        )
        val workManager = WorkManager.getInstance(context)

        // The first process: the in-process kick is held at the gateway (reading the server's copy) when the process dies.
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstOutbox = OutboxStore(db) { clock }
        val firstSender = SyncSender(firstOutbox, gateway, RecordingLanding(firstOutbox), { true }, {}, firstScope, { clock }, Dispatchers.Default)
        val scheduler = SyncScheduler(workManager, firstSender, firstOutbox, OnlineEvents(FakeConnections(), firstOutbox.serversWithWork(), firstScope), firstScope)
        gateway.gate = CompletableDeferred()
        val seq = enqueue(s1u1, played("film"))
        scheduler.changed(s1u1)
        awaitHeld(1)
        firstScope.cancel()
        gateway.gate = null

        // The next process: the job runs when a network is there.
        val nextOutbox = OutboxStore(db) { clock }
        val nextLanding = RecordingLanding(nextOutbox)
        nextProcess = SyncSender(nextOutbox, gateway, nextLanding, { true }, {}, CoroutineScope(SupervisorJob() + Dispatchers.Default), { clock }, Dispatchers.Default)
        val job = workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, job.state)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(job.id)
        var tries = 0
        while (!workManager.getWorkInfoById(job.id).get()!!.state.isFinished && tries++ < 1_000) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(job.id).get()!!.state)
        assertEquals("one write reached the server", 1, gateway.ops().count { it != "userData" })
        assertTrue(gateway.copy(s1u1, id("film")).played)
        assertEquals(listOf(seq), nextLanding.confirmed)
        assertEquals(0, count("sync_outbox"))
    }
}
