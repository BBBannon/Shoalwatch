package app.offlinefin

import android.content.Context
import app.offlinefin.data.db.AppDatabase
import app.offlinefin.data.servers.AuthRepository
import app.offlinefin.data.servers.DeviceIdentity
import app.offlinefin.data.servers.JellyfinGateway
import app.offlinefin.data.servers.JellyfinGatewayImpl
import app.offlinefin.data.servers.ServerStore
import app.offlinefin.net.HttpClientFactory
import app.offlinefin.security.KeystoreKeyProvider
import app.offlinefin.security.SecretBox

/** Holds the app's lazy singletons (no DI framework). Later tasks add the rest of the data layer here. */
class AppGraph(private val appContext: Context) {
    val database: AppDatabase by lazy { AppDatabase.open(appContext) }
    val secretBox: SecretBox by lazy { SecretBox(KeystoreKeyProvider()) }
    val serverStore: ServerStore by lazy { ServerStore(database, secretBox) }
    val httpClientFactory: HttpClientFactory by lazy { HttpClientFactory(appContext) }
    val deviceIdentity: DeviceIdentity by lazy { DeviceIdentity.load(appContext) }
    val jellyfinGateway: JellyfinGateway by lazy { JellyfinGatewayImpl(httpClientFactory) }
    val authRepository: AuthRepository by lazy { AuthRepository(jellyfinGateway, serverStore, deviceIdentity) }
}
