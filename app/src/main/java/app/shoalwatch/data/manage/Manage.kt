package app.shoalwatch.data.manage

import app.shoalwatch.data.servers.Session

/**
 * Library management as the screens use it (Milestone 10): a token-free [Session] in, validated models out. Implemented by
 * `api/ManageGateway` only. Every call fails `FORBIDDEN` with nothing sent unless the session is a server-confirmed administrator
 * (`AdminRights.allows`); [delete] needs `AdminRights.allowsDelete` instead. Item ids must be normalised `JellyfinId`s, else
 * `SERVER_NOT_FOUND` with nothing sent. Every change is sent once and never retried.
 */
interface Manage {
    suspend fun identify(s: Session, itemId: String, kind: IdentifyKind, name: String, year: Int?): List<IdentifyMatch>
    suspend fun applyMatch(s: Session, itemId: String, match: IdentifyMatch, replaceAllImages: Boolean)

    suspend fun images(s: Session, itemId: String): List<ItemImage>
    suspend fun remoteImages(s: Session, itemId: String, kind: ImageKind, allLanguages: Boolean): RemoteImages
    suspend fun useRemoteImage(s: Session, itemId: String, image: RemoteImage)
    suspend fun uploadImage(s: Session, itemId: String, kind: ImageKind, image: PreparedImage)
    suspend fun deleteImage(s: Session, itemId: String, kind: ImageKind, index: Int)

    suspend fun metadata(s: Session, itemId: String): MetadataForm

    /** Sends only what differs from [shown] (the form as [metadata] gave it); nothing at all when nothing does. */
    suspend fun saveMetadata(s: Session, itemId: String, edit: MetadataEdit, shown: MetadataForm)

    suspend fun refresh(s: Session, itemId: String, mode: RefreshMode, replaceImages: Boolean)

    /** Whether the server lets [s] delete [itemId] now: the item's own `CanDelete` for that user. False, nothing sent, without the delete right. */
    suspend fun canDelete(s: Session, itemId: String): Boolean

    suspend fun delete(s: Session, itemId: String)
}
