package app.shoalwatch

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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
import app.shoalwatch.data.downloads.api.DownloadGateway
import app.shoalwatch.data.downloads.api.DownloadGatewayImpl
import app.shoalwatch.data.playback.engine.SavedMediaProbe
import androidx.work.WorkManager
import app.shoalwatch.core.SystemMonotonicClock
import app.shoalwatch.data.db.library.LocalChangeLog
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.LibraryRepository
import app.shoalwatch.data.library.Playstate
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
import kotlinx.coroutines.launch
import java.io.File

/** Holds the app's lazy singletons (no DI framework). Later tasks add the rest of the data layer here. */
class AppGraph(private val appContext: Context) {
    /** Lives as long as the process; for work that must outlive any screen (monitoring, address probing). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.open(appContext) }
    val secretBox: SecretBox by lazy { SecretBox(KeystoreKeyProvider()) }
    val serverClients: ServerClients by lazy { ServerClients(httpClientFactory) }
    val serverStore: ServerStore by lazy { ServerStore(
        database, secretBox, serverClients::evict,
        onServerRemoved = {
            activePlayback.end(it); playbackReports.forget(it)
            addressSelector.forget(it); libraryRepository.forget(it); artworkEviction.onServerRemoved(it)
            downloadHousekeeping.onServerRemoved(it)
        },
        onAccountGone = { server, user ->
            activePlayback.end(server, user); playbackReports.forget(server, user)
            libraryRepository.forget(server, user); artworkEviction.onAccountGone(server)
            downloadHousekeeping.onAccountGone(server, user) // the ownership rule; a 401 keeps the account row, so nothing goes
        },
        onAccountSignedIn = { server, user -> libraryRepository.forget(server, user) },
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
    internal val downloadStore: DownloadStore by lazy {
        DownloadStore(database, System::currentTimeMillis, object : LibraryWriteLock {
            override suspend fun <T> locked(block: suspend () -> T): T = libraryRepository.writeGated(block)
        })
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
        DownloadRepository(downloadStore, database.offlineLibraryDao(), database.itemDao(), downloadPlanner, downloadScheduler, downloadPrefs, downloadVolumes, downloadPaths, appScope)
    }
    /** The removal rules and the start-up tidy-up (see "Removal rules" in docs/ARCHITECTURE.md). */
    internal val downloadHousekeeping: DownloadHousekeeping by lazy {
        DownloadHousekeeping(downloadStore, downloadPaths, downloadQueue, downloadScheduler, library, appScope)
    }
    /** WorkManager is reached only from here, on first use (initialised on demand with `ShoalwatchWorkerFactory`). */
    val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler({ WorkManager.getInstance(appContext) }, downloadPrefs) }

    private val anyQueued: suspend () -> Boolean = { database.downloadDao().queuedCount() > 0 }

    /** From app start: a change of "Use mobile data" replaces the work (WorkManager is reached only once something is queued). */
    fun watchDownloads(): Job = downloadScheduler.watchSettings(appScope, anyQueued)

    /** The app's activity started: kick the download worker if anything is queued (off the main thread). */
    fun downloadsInFront() { appScope.launch { downloadScheduler.inFront(anyQueued) } }
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
    val playbackResolver: PlaybackResolver by lazy { PlaybackResolver(playbackGateway, AndroidDeviceCapabilities(appContext), serverApi::mediaRoute) }
    /** What every browsing screen reads: saved rows first, refreshed behind them (see "Library repository" in docs/ARCHITECTURE.md). */
    /** The one record of local changes, shared by everything that writes server data or user changes: ordering by sequence, expiry by the monotonic clock. */
    val localChanges: LocalChangeLog by lazy { LocalChangeLog(SystemMonotonicClock) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(libraryGateway, database, appScope, Dispatchers.IO, System::currentTimeMillis, PrefsMaintenanceLog(appContext), localChanges) }
    val library: Library get() = libraryRepository

    /** What the user chose to sort and filter each library by, for as long as the app is open (never saved to disk). */
    val browseMemory: BrowseMemory by lazy { BrowseMemory() }
    /** The tidy-up actions (remove from Continue Watching, mark watched, favourite): Room first, then the server, rolled back on failure (see "Tidy-up actions" in docs/ARCHITECTURE.md). */
    val playstate: Playstate by lazy { PlaystateRepository(libraryGateway, libraryRepository, appScope, Dispatchers.IO, System::currentTimeMillis) }
    /** Progress reports and the final stop (also saved through `playstate`), best effort; see "Progress reports and teardown" in docs/ARCHITECTURE.md. */
    val playbackReports: ServerPlaybackReports by lazy { ServerPlaybackReports(playbackGateway, playstate, addressSelector, appScope) }
    /** Who is playing: ended by the store hooks above and whenever the active session changes. */
    val activePlayback: ActivePlayback by lazy {
        ActivePlayback({ s -> serverStore.cachedToken(s.serverId, s.userId) != null }).also { a ->
            appScope.launch { serverCatalog.activeSession().collect(a::sessionChanged) }
        }
    }
    /** A new Media3 player for one player screen (main thread); see "Player engine" in docs/ARCHITECTURE.md. */
    val playerEngineFactory: () -> PlayerEngine = { ExoPlayerEngine(appContext) }
    /**
     * The player's state machine for one screen and one [session] ([scope]: the screen's main scope). Screens get it from here,
     * so they never name the gateway, the resolver or a route.
     */
    fun playbackController(session: Session, scope: CoroutineScope, autoplayNext: () -> Boolean): PlaybackController = PlaybackController(
        session, playerEngineFactory(), playbackResolver, playbackReports, playbackGateway, library, EpisodeNavigator(library),
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
    internal val subtitleLoader: SubtitleLoader by lazy { SubtitleLoader(playbackGateway, Dispatchers.Default) }
    /** Loads saved tokens into the store's live lookup. Started at app start; anything authenticated waits on `serverStore.awaitWarmed()` (or uses `awaitActive()`). */
    val tokensWarmed: Job by lazy { appScope.launch { serverStore.warmTokens() } }
    /** What the trust prompt reads; the UI never touches `HttpClientFactory` itself. */
    val certificateProber: CertificateProber by lazy { CertificateProber(httpClientFactory::probeCertificate) }
    val authRepository: AuthRepository by lazy { AuthRepository(jellyfinGateway, serverStore, deviceIdentity, addressSelector) }

    /** What ViewModels use: the same stores without access tokens. */
    val serverCatalog: ServerCatalog by lazy { StoreCatalog(serverStore) }
    val authenticator: Authenticator by lazy { AuthenticatorImpl(authRepository) }
}
