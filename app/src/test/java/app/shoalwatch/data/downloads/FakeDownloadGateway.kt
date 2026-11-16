package app.shoalwatch.data.downloads

import app.shoalwatch.data.downloads.api.DownloadFacts
import app.shoalwatch.data.downloads.api.DownloadGateway
import app.shoalwatch.data.servers.Session

/** Answers [facts] from [known] (absent ids are left out, as the server would), or throws [failWith]; records each call's ids. */
internal class FakeDownloadGateway : DownloadGateway {
    val known = linkedMapOf<String, DownloadFacts>()
    val calls = mutableListOf<List<String>>()
    var failWith: Throwable? = null

    fun allow(itemId: String, canDownload: Boolean? = true, sizeBytes: Long? = null, container: String? = "mkv") {
        known[itemId] = DownloadFacts(itemId, canDownload, sourceId = "src1", container = container, sizeBytes = sizeBytes, eTag = null)
    }

    override suspend fun facts(s: Session, itemIds: List<String>): List<DownloadFacts> {
        calls += itemIds
        failWith?.let { throw it }
        require(itemIds.size <= 50) { "more than 50 ids in one call" }
        return itemIds.mapNotNull { known[it] }
    }
}
