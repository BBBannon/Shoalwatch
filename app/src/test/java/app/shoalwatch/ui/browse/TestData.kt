package app.shoalwatch.ui.browse

import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session

/** A normalised 32-digit id, so artwork refs can be built. */
fun id(n: Int): String = n.toString(16).padStart(32, '0')

val SERVER_ID = id(900)
val BEN = Session(SERVER_ID, id(901))
val AMY = Session(SERVER_ID, id(902))
val OTHER_SERVER = Session(id(903), id(904))

fun account(s: Session, name: String) = AccountSummary(s.serverId, s.userId, name)

val FILMS = LibraryView(id(1), "Films", LibraryKind.MOVIES)
val SHOWS = LibraryView(id(2), "TV Shows", LibraryKind.TV)

fun item(
    n: Int,
    kind: ItemKind = ItemKind.MOVIE,
    name: String = "Item $n",
    year: Int? = 2019,
    seriesId: String? = null,
    seriesName: String? = null,
    seasonId: String? = null,
    index: Int? = null,
    parentIndex: Int? = null,
    runtimeMs: Long? = 6_000_000,
    overview: String? = null,
    art: Boolean = true,
) = Item(
    id = id(n), kind = kind, name = name, sortName = name.lowercase(), parentId = seasonId, seriesId = seriesId, seriesName = seriesName,
    seasonId = seasonId, indexNumber = index, parentIndexNumber = parentIndex, overview = overview, runtimeMs = runtimeMs, year = year,
    premiereDate = null, communityRating = null, officialRating = null, childCount = null,
    images = if (art) ItemImages("p$n", "b$n", "t$n", null) else ItemImages(null, null, null, null),
)

fun userData(
    played: Boolean = false,
    positionMs: Long = 0,
    percent: Float? = null,
    favourite: Boolean = false,
    unplayed: Int? = null,
) = UserData(played, positionMs, percent, if (played) 1 else 0, favourite, null, unplayed)

fun entry(n: Int, kind: ItemKind = ItemKind.MOVIE, name: String = "Item $n", data: UserData? = userData(), year: Int? = 2019) =
    ItemWithUserData(item(n, kind, name, year), data)

fun episode(n: Int, season: Int = 1, number: Int = n, seriesN: Int = 50, name: String = "Episode $n", data: UserData? = userData()) =
    ItemWithUserData(
        item(n, ItemKind.EPISODE, name, seriesId = id(seriesN), seriesName = "Series $seriesN", seasonId = id(60 + season), index = number, parentIndex = season),
        data,
    )
