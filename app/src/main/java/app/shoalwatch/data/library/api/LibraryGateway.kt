package app.shoalwatch.data.library.api

import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort

/**
 * The server's library endpoints as the app's own safe models (see "Library endpoints" in docs/ARCHITECTURE.md).
 * Nothing returned here is a DTO or a JSON string; ids are normalised and display strings sanitised.
 * Failures are [app.shoalwatch.core.AppError]s from the authenticated call path (offline, unauthorised, not found...).
 * Ids passed in must be ids this gateway returned; anything else fails as `SERVER_NOT_FOUND` before a request is made.
 */
interface LibraryGateway {
    /** The user's libraries of a kind v1 shows (films, TV, collections, mixed). `GET /UserViews` */
    suspend fun views(s: Session): List<LibraryView>

    /**
     * One page of the items under [parentId]. [kinds] (empty: any) and [recursive] let the caller list all films
     * under a films library, or all series under a TV library, rather than only direct children. `GET /Items`
     */
    suspend fun children(
        s: Session,
        parentId: String,
        start: Int,
        limit: Int,
        sort: Sort,
        filter: ItemFilter,
        kinds: Set<ItemKind> = emptySet(),
        recursive: Boolean = false,
    ): Page<ItemWithUserData>

    /** One item with full detail (overview). A server id that is not a GUID is `SERVER_ERROR`. `GET /Items/{itemId}` */
    suspend fun item(s: Session, itemId: String): ItemWithUserData

    /** `GET /Shows/{seriesId}/Seasons` */
    suspend fun seasons(s: Session, seriesId: String): List<ItemWithUserData>

    /** All episodes of a series, or of one season. `GET /Shows/{seriesId}/Episodes` */
    suspend fun episodes(s: Session, seriesId: String, seasonId: String?): List<ItemWithUserData>

    /** Films and episodes part-watched. `GET /UserItems/Resume` */
    suspend fun resume(s: Session, limit: Int): List<ItemWithUserData>

    /** The next episode to watch in each series being followed. `GET /Shows/NextUp` */
    suspend fun nextUp(s: Session, limit: Int): List<ItemWithUserData>

    /** Recently added to a library. `GET /Items/Latest` (a bare array, unlike the others) */
    suspend fun latest(s: Session, viewId: String, limit: Int): List<ItemWithUserData>

    /** Films, series and episodes matching [term]; a blank term returns nothing and sends nothing. `GET /Items?searchTerm=` */
    suspend fun search(s: Session, term: String, limit: Int): List<ItemWithUserData>

    /** `POST` (watched) or `DELETE` (unwatched) `/UserPlayedItems/{itemId}`. Safe to repeat, so retried once on a network failure. */
    suspend fun setPlayed(s: Session, itemId: String, played: Boolean)

    /** `POST` or `DELETE` `/UserFavoriteItems/{itemId}`. Safe to repeat. */
    suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean)

    /** Forgets where playback stopped. `POST /UserItems/{itemId}/UserData` with `{"PlaybackPositionTicks":0}`. Safe to repeat. */
    suspend fun clearResume(s: Session, itemId: String)
}
