package app.shoalwatch

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tasks 9-10 review, "cannot verify": exactly one `DownloadQueue` per process. Two would run two drains, and one's requeue
 * would hand back the other's running row. The graph holds the one queue, and the worker is given that same queue.
 */
@RunWith(RobolectricTestRunner::class)
class AppGraphDownloadsTest {
    @Test fun theGraphHoldsOneQueueAndTheWorkerDrainsIt() {
        val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext())
        assertSame(graph.downloadQueue, graph.downloadQueue)
        assertSame(graph.downloadQueue, graph.downloadWorkerParts.queue)
        assertSame(graph, ShoalwatchApp.graph(ApplicationProvider.getApplicationContext()))
    }
}
