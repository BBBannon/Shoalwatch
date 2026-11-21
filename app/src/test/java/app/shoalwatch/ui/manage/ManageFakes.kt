package app.shoalwatch.ui.manage

import app.shoalwatch.core.AppError
import app.shoalwatch.data.manage.IdentifyKind
import app.shoalwatch.data.manage.IdentifyMatch
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.ItemImage
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.manage.MetadataEdit
import app.shoalwatch.data.manage.MetadataForm
import app.shoalwatch.data.manage.PreparedImage
import app.shoalwatch.data.manage.RefreshMode
import app.shoalwatch.data.manage.RemoteImage
import app.shoalwatch.data.manage.RemoteImages
import app.shoalwatch.data.manage.api.toMatches
import app.shoalwatch.data.manage.api.toRemote
import app.shoalwatch.data.manage.api.RemoteImageResultDto
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** [Manage] for ViewModel tests: every call is recorded in [calls] as "name:item[:arg]"; [failWith] makes every call fail. */
class FakeManage : Manage {
    val calls = mutableListOf<String>()
    var failWith: AppError? = null
    var gate: CompletableDeferred<Unit>? = null
    var matches: List<IdentifyMatch> = sampleMatches()
    var images: List<ItemImage> = listOf(ItemImage(ImageKind.PRIMARY, 0, 1000, 1500, 2000), ItemImage(ImageKind.BACKDROP, 0, 1920, 1080, 4000))
    var remote: RemoteImages = sampleRemote()
    var form = MetadataForm(
        "Old name", "", "", "Old overview.", 1999, null, "PG", 6.5, listOf("Drama"), emptyList(), listOf("Studio"), emptySet(), false, listOf("U", "PG"),
    )
    var lastEdit: MetadataEdit? = null
    var lastShown: MetadataForm? = null
    /** What `canDelete` answers per item (the item's own `CanDelete`). */
    var deletable: (String) -> Boolean = { true }
    var lastUpload: PreparedImage? = null

    private suspend fun record(call: String) {
        calls += call
        gate?.await()
        failWith?.let { throw it }
    }

    override suspend fun identify(s: Session, itemId: String, kind: IdentifyKind, name: String, year: Int?): List<IdentifyMatch> {
        record("identify:$itemId:$kind:$name:$year"); return matches
    }
    override suspend fun applyMatch(s: Session, itemId: String, match: IdentifyMatch, replaceAllImages: Boolean) = record("apply:$itemId:${match.name}:$replaceAllImages")
    override suspend fun images(s: Session, itemId: String): List<ItemImage> { record("images:$itemId"); return images }
    override suspend fun remoteImages(s: Session, itemId: String, kind: ImageKind, allLanguages: Boolean): RemoteImages {
        record("remote:$itemId:$kind:$allLanguages"); return remote
    }
    override suspend fun useRemoteImage(s: Session, itemId: String, image: RemoteImage) = record("use:$itemId:${image.kind}:${image.providerName}")
    override suspend fun uploadImage(s: Session, itemId: String, kind: ImageKind, image: PreparedImage) { lastUpload = image; record("upload:$itemId:$kind") }
    override suspend fun deleteImage(s: Session, itemId: String, kind: ImageKind, index: Int) = record("deleteImage:$itemId:$kind:$index")
    override suspend fun metadata(s: Session, itemId: String): MetadataForm { record("metadata:$itemId"); return form }
    override suspend fun saveMetadata(s: Session, itemId: String, edit: MetadataEdit, shown: MetadataForm) { lastEdit = edit; lastShown = shown; record("save:$itemId") }
    override suspend fun refresh(s: Session, itemId: String, mode: RefreshMode, replaceImages: Boolean) = record("refresh:$itemId:$mode:$replaceImages")
    override suspend fun canDelete(s: Session, itemId: String): Boolean = deletable(itemId)
    override suspend fun delete(s: Session, itemId: String) = record("delete:$itemId")

    companion object {
        fun sampleMatches(): List<IdentifyMatch> = (Json.parseToJsonElement(
            """[{"Name":"The Silent Harbour","ProductionYear":1999,"SearchProviderName":"TheMovieDb","ProviderIds":{"Tmdb":"1"}},
               {"Name":"The Silent Harbour Returns","ProductionYear":2003,"SearchProviderName":"TheMovieDb","ProviderIds":{"Tmdb":"2"}}]""",
        ) as JsonArray).toMatches()

        fun sampleRemote(kind: ImageKind = ImageKind.PRIMARY): RemoteImages = Json { ignoreUnknownKeys = true }.decodeFromString(
            RemoteImageResultDto.serializer(),
            """{"Images":[{"ProviderName":"TheMovieDb","Url":"https://image.tmdb.org/a.jpg","Width":1000,"Height":1500,"Language":"en","CommunityRating":7.4,"VoteCount":12}],"TotalRecordCount":1,"Providers":["TheMovieDb"]}""",
        ).toRemote(kind)
    }
}
