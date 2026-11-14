package app.shoalwatch.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import app.shoalwatch.testId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import javax.crypto.KeyGenerator

/** In-memory Room with two servers (S1 has users U1 and U2, S2 has U1) saved through the real [ServerStore]. */
@RunWith(RobolectricTestRunner::class)
abstract class LibraryTestBase {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    protected lateinit var db: AppDatabase
    protected lateinit var servers: ServerStore
    protected lateinit var library: LibraryStore

    protected val t0: Instant = Instant.parse("2026-11-14T10:00:00Z")
    protected val s1u1 = Session(S1, U1)
    protected val s1u2 = Session(S1, U2)
    protected val s2u1 = Session(S2, U1)

    @Before fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        servers = ServerStore(db, SecretBox { key })
        library = LibraryStore(db)
        runBlocking {
            listOf(S1, S2).forEach { id ->
                servers.upsertServer(Server(id, "Den", "10.10.3", ServerAddress.parse("https://den.example"), null, false, null, null, emptyMap()))
            }
            servers.upsertAccount(Account(S1, U1, "ann", "tok-1"), makeActive = true)
            servers.upsertAccount(Account(S1, U2, "bob", "tok-2"), makeActive = false)
            servers.upsertAccount(Account(S2, U1, "ann", "tok-3"), makeActive = false)
        }
    }

    @After fun closeDatabase() = db.close()

    protected fun id(label: String) = testId(label)

    protected fun item(
        label: String,
        kind: ItemKind = ItemKind.MOVIE,
        name: String = "Film $label",
        sortName: String? = null,
        parentId: String? = null,
        seriesId: String? = null,
        seasonId: String? = null,
        indexNumber: Int? = null,
        parentIndexNumber: Int? = null,
        overview: String? = null,
        childCount: Int? = null,
        primaryTag: String? = "tag1",
    ) = Item(
        id = id(label), kind = kind, name = name, sortName = sortName, parentId = parentId?.let(::id), seriesId = seriesId?.let(::id),
        seriesName = null, seasonId = seasonId?.let(::id), indexNumber = indexNumber, parentIndexNumber = parentIndexNumber,
        overview = overview, runtimeMs = 5_400_000, year = 2020, premiereDate = Instant.parse("2020-05-01T00:00:00Z"),
        communityRating = 7.5f, officialRating = "PG", childCount = childCount,
        images = ItemImages(primaryTag, null, null, null),
    )

    protected fun watched(played: Boolean = false, positionMs: Long = 0, favourite: Boolean = false) =
        UserData(played, positionMs, null, if (played) 1 else 0, favourite, null, null)

    protected fun row(item: Item, userData: UserData? = null) = ItemWithUserData(item, userData)

    protected fun episode(label: String, season: Int?, number: Int?, series: String = "show", seasonLabel: String = "season${season ?: "x"}") =
        item(label, ItemKind.EPISODE, "Episode $label", seriesId = series, seasonId = seasonLabel, indexNumber = number, parentIndexNumber = season)

    protected fun sql(statement: String, vararg args: Any) = db.openHelper.writableDatabase.execSQL(statement, arrayOf(*args))

    protected fun count(table: String, where: String = "1=1"): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }
}
