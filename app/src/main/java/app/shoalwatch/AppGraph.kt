package app.shoalwatch

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminAccess
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.BrowserDashboard
import app.shoalwatch.data.admin.DashboardOpener
import app.shoalwatch.data.admin.api.AdminGateway
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.images.ArtworkDiskCaches
import app.shoalwatch.data.images.ArtworkEviction
import app.shoalwatch.data.images.ArtworkLoader
import app.shoalwatch.data.images.PinnedArtwork
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.db.downloads.LibraryWriteLock
import app.shoalwatch.data.downloads.AndroidVolumes
import app.shoalwatch.data.downloads.DownloadHousekeeping
import app.shoalwatch.data.downloads.DownloadNotifications
import app.shoalwatch.data.downloads.DownloadPlanner
import app.shoalwatch.data.downloads.DownloadRepository
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.DownloadPaths
import app.shoalwatch.data.downloads.DownloadPrefs
import app.shoalwatch.data.downloads.DownloadQueue
import app.shoalwatch.data.downloads.DownloadScheduler
import app.shoalwatch.data.downloads.DownloadVerifier
import app.shoalwatch.data.downloads.DownloadWorker
import app.shoalwatch.data.downloads.ExtrasFetcher
import app.shoalwatch.data.downloads.FileFetcher
import app.shoalwatch.data.downloads.HlsFetcher
import app.shoalwatch.data.downloads.Volumes
import app.shoalwatch.data.downloads.SavedCopies
import app.shoalwatch.data.downloads.SharingRecheck
import app.shoalwatch.data.downloads.LocalPlaying
import app.shoalwatch.data.downloads.Housekeeping
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.browse.connectionRegained
import app.shoalwatch.data.downloads.savedRows
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.playback.LocalPlaybackReports
import app.shoalwatch.data.playback.RoutedPlaybackReports
import app.shoalwatch.data.playback.SavedFirstPlaybackGateway
import app.shoalwatch.data.sync.OfflinePlays
import app.shoalwatch.data.sync.OnlineEvents
import app.shoalwatch.data.sync.OutboxStore
import app.shoalwatch.data.sync.SyncLanding
import app.shoalwatch.data.sync.Sync
import app.shoalwatch.data.sync.SyncRepository
import app.shoalwatch.data.sync.SyncScheduler
import app.shoalwatch.data.sync.SyncSender
import app.shoalwatch.data.sync.StopsInFlight
import app.shoalwatch.data.sync.api.SyncGateway
import app.shoalwatch.data.sync.api.SyncGatewayImpl
import app.shoalwatch.data.downloads.api.DownloadGateway
import app.shoalwatch.data.downloads.api.DownloadGatewayImpl
import app.shoalwatch.data.playback.engine.SavedMediaProbe
import androidx.work.WorkManager
import app.shoalwatch.core.SystemMonotonicClock
import app.shoalwatch.data.db.library.LocalChangeLog
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.LibraryRepository
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.PlaystateLanding
import app.shoalwatch.data.library.PlaystateRepository
import app.shoalwatch.data.library.PrefsMaintenanceLog
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.library.api.LibraryGatewayImpl
import app.shoalwatch.data.playback.ActivePlayback
import app.shoalwatch.data.playback.AndroidDeviceCapabilities
import app.shoalwatch.data.playback.EpisodeNavigator
import app.shoalwatch.data.playback.PlaybackController
import app.shoalwatch.data.playback.QualityDefault
import app.shoalwatch.data.playback.engine.ExoPlayerEngine
import app.shoalwatch.data.playback.engine.PlayerEngine
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.playback.ServerPlaybackReports
import app.shoalwatch.data.playback.PlaybackResolver
import app.shoalwatch.data.playback.SubtitleLoader
import app.shoalwatch.data.playback.SubtitleSettings
import app.shoalwatch.data.playback.TrackPreferences
import app.shoalwatch.data.playback.systemCaptionDefaults
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.api.PlaybackGatewayImpl
import app.shoalwatch.data.servers.AddressSelector
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.AuthenticatorImpl
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGateway
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.ui.library.BrowseMemory
import app.shoalwatch.net.AndroidNetworkMonitor
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.NetworkMonitor
import app.shoalwatch.security.KeystoreKeyProvider
import app.shoalwatch.security.SecretBox
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Holds the app's lazy singletons (no DI framework). Later tasks add the rest of the data layer here. */
class AppGraph(internal val appContext: Context) {
    /** Lives as long as the process; for work that must outlive any screen (monitoring, address probing). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.open(appContext) }
    val secretBox: SecretBox by lazy { SecretBox(KeystoreKeyProvider()) }
    val serverClients: ServerClients by lazy { ServerClients(httpClientFactory) }
    val serverStore: ServerStore by lazy { ServerStore(
        database, secretBox, serverClients::evict,
        onServerRemoved = {
            activePlayback.end(it)
            addressSelector.forget(it); libraryRepository.forget(it); artworkEviction.onServerRemoved(it)
            downloadHousekeeping.onServerRemoved(it); syncSender.forget(it) // its drains (and any request in flight) stop; its rows cascade
            forgetGoneForgotTries()
        },
        onAccountGone = { server, user ->
            activePlayback.end(server, user)
            libraryRepository.forget(server, user); artworkEviction.onAccountGone(server)
            downloadHousekeeping.onAccountGone(server, user) // the ownership rule; a 401 keeps the account row, so nothing goes
            syncSender.forget(server, user) // its drain (and any request in flight) stops; removed: its rows cascade; a 401: they wait
            forgetGoneForgotTries()
        },
        onAccountSignedIn = { server, user -> libraryRepository.forget(server, user); syncScheduler.signedIn(server, user) },
    ) }
    val httpClientFactory: HttpClientFactory by lazy { HttpClientFactory(appContext) }
    val deviceIdentity: DeviceIdentity by lazy { DeviceIdentity.load(appContext) }
    val jellyfinGateway: JellyfinGateway by lazy { JellyfinGatewayImpl(serverClients) }
    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext, appScope) }
    val addressSelector: AddressSelector by lazy { AddressSelector(jellyfinGateway, networkMonitor, appScope, deviceIdentity, serverStore, foreground) }

    /** True while one of the app's screens is started (the app is in the foreground); ends with the process like [appScope]. */
    private val foreground: Flow<Boolean> by lazy {
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
    }
    /** The one path for every signed-in request; nothing in the UI uses it yet. */
    val serverApi: ServerApi by lazy { ServerApi(serverStore, addressSelector, serverClients, deviceIdentity) }
    /** Saved pictures, one folder per server in `cacheDir/artwork` (so a removed server's pictures can go as a unit). */
    val artworkCaches: ArtworkDiskCaches by lazy { ArtworkDiskCaches(File(appContext.cacheDir, "artwork")) }
    /** The one image loader: it loads `ArtworkRef`s and nothing else (see "Artwork" in docs/ARCHITECTURE.md). Provided to every screen as `LocalArtworkLoader` and also set as Coil's singleton in `ShoalwatchApp`. */
    val imageLoader: ImageLoader by lazy {
        ArtworkLoader.build(appContext, serverApi, { s -> serverStore.awaitWarmed(); serverStore.cachedToken(s.serverId, s.userId) != null }, artworkCaches, pinned = pinnedArtwork)
    }
    /** Where download files go (internal storage, or an SD card by choice); the only code that names them. */
    internal val downloadVolumes: Volumes by lazy { AndroidVolumes(appContext) }
    internal val downloadPaths: DownloadPaths by lazy { DownloadPaths(downloadVolumes) }
    /** The download rows, owners and pins; every pin is written under the library's write lock. */
    internal val downloadStore: DownloadStore by lazy { DownloadStore(database, System::currentTimeMillis, libraryWriteLock) }
    /** Every write of `items.pinned` (downloads, watch-and-delete) runs under the library's write gate. */
    internal val libraryWriteLock: LibraryWriteLock = object : LibraryWriteLock {
        override suspend fun <T> locked(block: suspend () -> T): T = libraryRepository.writeGated(block)
    }
    /** Device download settings (mobile data, storage, default quality) in the private `download_prefs` file. */
    val downloadPrefs: DownloadPrefs by lazy { DownloadPrefs(appContext.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)) }
    internal val downloadGateway: DownloadGateway by lazy { DownloadGatewayImpl(serverApi) }
    /** One item at a time; see "Download queue" in docs/ARCHITECTURE.md. Every byte through the account's guarded, pinned client. */
    internal val downloadQueue: DownloadQueue by lazy {
        DownloadQueue(
            downloadStore, playbackResolver, { s, id -> serverApi.downloadRoute(s, id) }, downloadGateway,
            FileFetcher(Dispatchers.IO, downloadVolumes), HlsFetcher(Dispatchers.IO, SystemMonotonicClock, downloadPaths, downloadVolumes),
            ExtrasFetcher(playbackGateway, serverApi, database.itemDao(), database.downloadDao(), downloadPaths, pinnedArtwork),
            DownloadVerifier(SavedMediaProbe(appContext), AndroidDeviceCapabilities(appContext), Dispatchers.IO),
            downloadPaths, downloadVolumes, { s -> serverStore.cachedToken(s.serverId, s.userId) != null }, trackPreferences,
            { systemCaptionDefaults(appContext) }, SystemMonotonicClock,
        )
    }
    internal val downloadNotifications: DownloadNotifications by lazy { DownloadNotifications(appContext) }
    internal val downloadPlanner: DownloadPlanner by lazy {
        DownloadPlanner(library, database.itemDao(), downloadGateway, { s, id -> playbackResolver.checkOriginal(s, id) }, database.downloadDao(), downloadVolumes, downloadPrefs)
    }
    /** What screens use for downloads (see "Downloads seam" in docs/ARCHITECTURE.md). */
    val downloads: Downloads by lazy {
        DownloadRepository(
            downloadStore, database.offlineLibraryDao(), database.itemDao(), downloadPlanner, downloadScheduler, downloadPrefs, downloadVolumes, downloadPaths, appScope,
            removed = { downloadQueue.forget(it) }, household = lockParts.householdGuard,
        )
    }
    /** The removal rules and the start-up tidy-up (see "Removal rules" in docs/ARCHITECTURE.md). */
    internal val downloadHousekeeping: DownloadHousekeeping by lazy {
        DownloadHousekeeping(
            downloadStore, downloadPaths, downloadQueue, downloadScheduler, library, downloadGateway, appScope,
            reachable = { id -> addressSelector.connection(id).value is Connection.Online },
        )
    }
    /** WorkManager is reached only from here, on first use (initialised on demand with `ShoalwatchWorkerFactory`). */
    val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler({ WorkManager.getInstance(appContext) }, downloadPrefs) }

    private val anyQueued: suspend () -> Boolean = { database.downloadDao().queuedCount() > 0 }

    /** From app start: a change of "Use mobile data" replaces the work (WorkManager is reached only once something is queued). */
    fun watchDownloads(): Job = downloadScheduler.watchSettings(appScope, anyQueued)

    /** From app start: the sharing re-ask for the active profile on each session change and each time its server comes back. */
    fun watchSharing(): Job = sharingRecheck.watch(serverCatalog.activeSession(), connectionRegained(serverCatalog, addressSelector))

    /**
     * The app's activity started: kick the download worker if anything is queued, and re-ask the sharing confirmations of the
     * active profile (rate limited), off the main thread.
     */
    fun downloadsInFront() {
        appScope.launch { downloadScheduler.inFront(anyQueued) }
        appScope.launch { sharingRecheck.inFront(serverCatalog.activeSession()) }
    }

    /** When the strict sharing rule's 7-day re-ask runs, whichever screen is open (see "Sharing rule" in docs/ARCHITECTURE.md). */
    internal val sharingRecheck: SharingRecheck by lazy {
        SharingRecheck({ s, first -> downloadHousekeeping.reconfirm(s, first) }, ::serverReachable, appScope, SystemMonotonicClock)
    }
    private fun serverReachable(s: Session): Boolean = addressSelector.connection(s.serverId).value is Connection.Online

    /** "Forgot PIN" tries are kept only for profiles that still have an account row (security review M8 F5; a 401 keeps its row). */
    private fun forgetGoneForgotTries() {
        appScope.launch(Dispatchers.IO) {
            try {
                lockParts.forgotThrottle.keepOnly(database.accountDao().getAll().map { Session(it.serverId, it.userId) }.toSet())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                app.shoalwatch.core.AppLog.w("Lock", "Forgetting Forgot PIN tries failed", e)
            }
        }
    }
    /** What `ShoalwatchWorkerFactory` hands the download worker. */
    internal val downloadWorkerParts: DownloadWorker.Parts by lazy {
        DownloadWorker.Parts(
            downloadQueue, downloadNotifications, { downloadScheduler.report(it) }, { since -> database.downloadDao().completedSince(since) },
            { downloadPrefs.settings.value.mobileData }, System::currentTimeMillis,
        )
    }
    /** Pictures kept with downloads, consulted by the image loader after a disk-cache miss. */
    val pinnedArtwork: PinnedArtwork by lazy { PinnedArtwork(database.downloadDao(), downloadPaths) }
    /** Clears a server's pictures when the server, or its last signed-in account, goes. */
    val artworkEviction: ArtworkEviction by lazy {
        ArtworkEviction(artworkCaches, { imageLoader.memoryCache }, { id -> serverStore.accounts(id).first().isNotEmpty() }, appScope, Dispatchers.IO)
    }
    /** Library endpoints as safe models; only `library` uses it. */
    val libraryGateway: LibraryGateway by lazy { LibraryGatewayImpl(serverApi) }
    /** PlaybackInfo, playstate reports, segments and subtitle files as validated models (see "Playback endpoints" in docs/ARCHITECTURE.md). */
    internal val playbackGateway: PlaybackGateway by lazy { PlaybackGatewayImpl(serverApi) }
    /** PlaybackInfo, then direct play, direct stream or transcode, with a route to the address verified now (see "Playback decision" in docs/ARCHITECTURE.md). */
    val playbackResolver: PlaybackResolver by lazy {
        PlaybackResolver(
            playbackGateway, AndroidDeviceCapabilities(appContext), serverApi::mediaRoute,
            saved = savedCopies::plan,
            savedFailed = { s, id -> appScope.launch { downloadQueue.recheck(DownloadKey(s.serverId, id)) } },
        )
    }
    /** The one lookup of a saved copy a session may play (see "Offline playback" in docs/ARCHITECTURE.md). */
    internal val savedCopies: SavedCopies by lazy {
        SavedCopies(
            database.offlineLibraryDao(), database.downloadDao().savedRows(), downloadPaths,
            reachable = ::serverReachable, recheck = { s, itemId -> sharingRecheck.request(s, itemId) },
        )
    }
    /** What the player and its subtitle loader ask: saved subtitles and skip markers for a saved copy, else the server. */
    internal val playerGateway: PlaybackGateway by lazy { SavedFirstPlaybackGateway(playbackGateway, savedCopies::media, database.downloadDao().savedRows(), downloadPaths) }
    /** What a LOCAL play keeps (`offline_plays`) until its outbox row is confirmed; recovered at start (`ShoalwatchApp`). */
    val offlinePlays: OfflinePlays by lazy { OfflinePlays(database.offlinePlayDao(), System::currentTimeMillis, outboxStore, database.itemDao()) }
    /** Which saved copies the player has open (memory only). */
    internal val localPlaying: LocalPlaying by lazy { LocalPlaying() }
    /** The controller's reports: LOCAL plays stay on the phone, everything else goes to the server. */
    internal val routedReports: RoutedPlaybackReports by lazy {
        RoutedPlaybackReports(playbackReports, LocalPlaybackReports(offlinePlays, playstateRepository, System::currentTimeMillis, localPlaying, housekeepingParts.repository::onLocalFinished))
    }
    internal val housekeepingParts: HousekeepingParts by lazy { HousekeepingParts(this) }
    /** Watch-and-delete and the per-profile download settings (see "Watch-and-delete" in docs/ARCHITECTURE.md). */
    val housekeeping: Housekeeping get() = housekeepingParts.repository
    /** What every browsing screen reads: saved rows first, refreshed behind them (see "Library repository" in docs/ARCHITECTURE.md). */
    /** The one record of local changes, shared by everything that writes server data or user changes: ordering by sequence, expiry by the monotonic clock. */
    val localChanges: LocalChangeLog by lazy { LocalChangeLog(SystemMonotonicClock) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(libraryGateway, database, appScope, Dispatchers.IO, System::currentTimeMillis, PrefsMaintenanceLog(appContext), localChanges) }
    val library: Library get() = libraryRepository

    /** What the user chose to sort and filter each library by, for as long as the app is open (never saved to disk). */
    val browseMemory: BrowseMemory by lazy { BrowseMemory() }
    /** The tidy-up actions (remove from Continue Watching, mark watched, favourite): saved on the phone and queued in the outbox at once (see "Tidy-up actions" in docs/ARCHITECTURE.md). */
    val playstate: Playstate get() = playstateRepository
    internal val localPlaystate: app.shoalwatch.data.library.LocalPlaystate get() = playstateRepository
    private val playstateRepository: PlaystateRepository by lazy {
        PlaystateRepository(libraryRepository, outboxStore, database.offlinePlayDao(), { s -> syncScheduler.changed(s) }, appScope, Dispatchers.IO, System::currentTimeMillis)
    }
    /** Progress reports and the final stop (also saved through `playstate`, queued when the server missed it); see "Progress reports and teardown" in docs/ARCHITECTURE.md. */
    val playbackReports: ServerPlaybackReports by lazy { ServerPlaybackReports(playbackGateway, playstate, stopsInFlight) { s -> syncScheduler.changed(s) } }
    /** Items whose stop report is in flight: the sender passes over their rows until it lands (Tasks 9-10 review I1). */
    internal val stopsInFlight: StopsInFlight by lazy { StopsInFlight() }
    /** Who is playing: ended by the store hooks above and whenever the active session changes. */
    val activePlayback: ActivePlayback by lazy {
        ActivePlayback({ s -> serverStore.cachedToken(s.serverId, s.userId) != null }).also { a ->
            appScope.launch { serverCatalog.activeSession().collect(a::sessionChanged) }
        }
    }
    /** A new Media3 player for one player screen (main thread), telling the app lock when it plays; see "Player engine" in docs/ARCHITECTURE.md. */
    val playerEngineFactory: () -> PlayerEngine = { ExoPlayerEngine(appContext, lockParts.nowPlaying.reporter()) }
    /**
     * The player's state machine for one screen and one [session] ([scope]: the screen's main scope). Screens get it from here,
     * so they never name the gateway, the resolver or a route.
     */
    fun playbackController(session: Session, scope: CoroutineScope, autoplayNext: () -> Boolean): PlaybackController = PlaybackController(
        session, playerEngineFactory(), playbackResolver, routedReports, playerGateway, library, EpisodeNavigator(library),
        activePlayback, scope, appScope, SystemMonotonicClock, autoplayNext, trackPreferences, subtitleLoader, { systemCaptionDefaults(appContext) },
    )
    /** Audio and subtitle choices per (server, user, series), in Room (see "Track choices" in docs/ARCHITECTURE.md). */
    val trackPreferences: TrackPreferences by lazy { TrackPreferences(database.trackChoiceDao(), System::currentTimeMillis) }
    /** Subtitle size, colour, background and edge: a device setting, following the phone's caption settings until changed. */
    val subtitleSettings: SubtitleSettings by lazy {
        SubtitleSettings(appContext.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)) { systemCaptionDefaults(appContext) }
    }
    /** The quality a film starts at: a device setting in the same private prefs file (see "Player controller" in docs/ARCHITECTURE.md). */
    val qualityDefault: QualityDefault by lazy {
        QualityDefault(appContext.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)) { QualityDefault.activeNetworkMetered(appContext) }
    }
    /** Fetches and parses text subtitle files, off the main thread. */
    internal val subtitleLoader: SubtitleLoader by lazy { SubtitleLoader(playerGateway, Dispatchers.Default) }
    /** Loads saved tokens into the store's live lookup. Started at app start; anything authenticated waits on `serverStore.awaitWarmed()` (or uses `awaitActive()`). */
    val tokensWarmed: Job by lazy { appScope.launch { serverStore.warmTokens() } }
    /** What the trust prompt reads; the UI never touches `HttpClientFactory` itself. */
    val certificateProber: CertificateProber by lazy { CertificateProber(httpClientFactory::probeCertificate) }
    val authRepository: AuthRepository by lazy { AuthRepository(jellyfinGateway, serverStore, deviceIdentity, addressSelector) }

    /** What ViewModels use: the same stores without access tokens. */
    val serverCatalog: ServerCatalog by lazy { StoreCatalog(serverStore, { lockParts.profileLocks.isProtected(it) }, { lockParts.householdGuard }) }
    val authenticator: Authenticator by lazy { AuthenticatorImpl(authRepository) { lockParts.householdGuard } }
    /** Profile PINs, switching, the household check and the app lock (see "Profile PINs" in docs/ARCHITECTURE.md). */
    internal val lockParts by lazy { LockParts(this) }

    // ---- Sync-back (Milestone 5, data/sync): see "Sync outbox" in docs/ARCHITECTURE.md.
    internal val outboxStore: OutboxStore by lazy { OutboxStore(database, System::currentTimeMillis) }
    internal val syncGateway: SyncGateway by lazy { SyncGatewayImpl(serverApi, libraryGateway) }
    /** What this phone does when a row is confirmed or overruled: markers clear, lists (and an overruled item) refresh. */
    private val syncLanding: SyncLanding by lazy { PlaystateLanding(libraryRepository, outboxStore, database.offlinePlayDao(), System::currentTimeMillis) }
    /** Sends each profile's rows in order, as that profile; what the sync job awaits (`ShoalwatchWorkerFactory`). */
    internal val syncSender: SyncSender by lazy {
        SyncSender(outboxStore, syncGateway, syncLanding, { s -> serverStore.cachedToken(s.serverId, s.userId) != null }, { serverStore.awaitWarmed() }, appScope, inFlight = stopsInFlight)
    }
    internal val onlineEvents: OnlineEvents by lazy { OnlineEvents(addressSelector, outboxStore.serversWithWork(), appScope) }
    /** When sending happens: after each change, at start (`ShoalwatchApp`), after a sign-in, on reconnection, and by the `shoalwatch-sync` job. */
    val syncScheduler: SyncScheduler by lazy { SyncScheduler({ WorkManager.getInstance(appContext) }, syncSender, outboxStore, onlineEvents, appScope) }
    /** What screens read and ask (see "Sync seam" in docs/ARCHITECTURE.md): each profile's waiting changes, and "send them now". */
    val sync: Sync by lazy { SyncRepository(outboxStore, syncSender, serverStore.usableAccounts) }

    /** App start: offline plays no outbox row covers are queued first (never a PLAY), then the scheduler starts. */
    fun startSync(): Job = appScope.launch {
        try {
            offlinePlays.recoverUnqueued()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            app.shoalwatch.core.AppLog.w("Sync", "Recovering offline plays failed", e)
        }
        syncScheduler.start()
    }

    // ---- Server admin (Milestone 6, data/admin). Live only: nothing here is cached, saved or kept for another profile.
    private val adminGateway: AdminGateway by lazy { AdminGateway(serverApi, deviceIdentity.deviceId) { s -> adminRights.allows(s) } }
    /** What admin screens call: every request gated on [adminRights] (see "Server admin" in docs/ARCHITECTURE.md). */
    val admin: Admin get() = adminGateway
    /** Whether the active account is a server-confirmed administrator; forgets on every active-session change. */
    val adminRights: AdminRights by lazy {
        AdminAccess(adminGateway::me).also { a -> appScope.launch { serverCatalog.activeSession().collect(a::sessionChanged) } }
    }
    /** Opens the server's own web dashboard in the browser, on the verified address, with no sign-in passed. */
    val browserDashboard: DashboardOpener by lazy { BrowserDashboard(addressSelector) }

    // ---- Library management (Milestone 10, data/manage) and Quick Connect approval. Live only, as the admin block above.
    /** The active profile now, for a check made just before a change is written (`sendIf`). */
    private val activeNow: kotlinx.coroutines.flow.StateFlow<app.shoalwatch.data.servers.Session?> by lazy {
        serverCatalog.activeSession().stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }
    private val manageGateway: app.shoalwatch.data.manage.api.ManageGateway by lazy {
        app.shoalwatch.data.manage.api.ManageGateway(serverApi, { s -> adminRights.allows(s) }, { s -> adminRights.allowsDelete(s) })
    }
    /** What the management screens call: every request gated on [adminRights] (see "Library management" in docs/ARCHITECTURE.md). */
    val manage: app.shoalwatch.data.manage.Manage get() = manageGateway
    // ---- Playlists and collections (Milestone 11, data/lists). Live only; the one cache is the collection index, in memory.
    private val listsGateway: app.shoalwatch.data.lists.api.ListsGateway by lazy { app.shoalwatch.data.lists.api.ListsGateway(serverApi, SystemMonotonicClock) }
    /** What the playlist and collection screens call. */
    val lists: app.shoalwatch.data.lists.Lists get() = listsGateway
    // ---- Discovery (Milestone 11, data/discover). Live only, reads only: nothing is cached.
    private val discoverGateway: app.shoalwatch.data.discover.api.DiscoverGateway by lazy { app.shoalwatch.data.discover.api.DiscoverGateway(serverApi) }
    /** What the detail extras, person, filtered-browse, genre, studio and search-extras screens call. */
    val discover: app.shoalwatch.data.discover.Discover get() = discoverGateway
    /** Pictures from the photo picker, read through the content resolver and re-encoded on the phone. */
    val pickedImages: app.shoalwatch.data.manage.PickedImages by lazy { app.shoalwatch.data.manage.PickedImages(appContext.contentResolver::openInputStream) }
    /** Approving a Quick Connect code from Settings: only while the asking profile is still the active one. */
    val quickConnectApprover: app.shoalwatch.data.servers.QuickConnectApprover by lazy {
        app.shoalwatch.data.servers.QuickConnectApprover(serverApi, activeNow)
    }
}
