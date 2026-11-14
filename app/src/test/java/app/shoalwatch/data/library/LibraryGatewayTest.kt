package app.shoalwatch.data.library

import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Each endpoint: method, path, every query parameter (exact case) and the mapped result. */
class LibraryGatewayTest : LibraryGatewayTestBase() {
    private val nameAsc = Sort(SortBy.NAME, descending = false)

    // ---- views

    @Test fun viewsAsksForTheUsersViewsAndKeepsOnlyFilmsTvCollectionsAndMixed() {
        enqueueFixture("views.json")
        val views = runBlocking { gateway.views(session) }
        assertRequest("GET", "/UserViews", mapOf("userId" to U1))
        assertEquals(
            listOf(
                LibraryView(FILMS, "Films", LibraryKind.MOVIES),
                LibraryView(TV, "TV Shows", LibraryKind.TV),
                LibraryView("c0ffee00000000000000000000000004", "Collections", LibraryKind.BOX_SETS),
                LibraryView("c0ffee00000000000000000000000006", "Mixed stuff", LibraryKind.MIXED),
            ),
            views,
        )
    }

    // ---- children

    @Test fun childrenSendsEveryParameterAndMapsTheWholePage() {
        enqueueFixture("items_page.json")
        val page = runBlocking { gateway.children(session, FILMS.uppercase(), 0, 50, nameAsc, ItemFilter()) }
        assertRequest(
            "GET", "/Items",
            mapOf("userId" to U1, "parentId" to FILMS, "startIndex" to "0", "limit" to "50", "sortBy" to "SortName", "sortOrder" to "Ascending",
                "recursive" to "false", "fields" to "ChildCount") + IMAGE_QUERY,
        )
        assertEquals(0, page.startIndex)
        assertEquals(57, page.total)
        assertEquals(listOf(MOVIE_A, MOVIE_B, MOVIE_C, SERIES, BOX_SET), page.items.map { it.item.id })
        assertEquals(listOf(ItemKind.MOVIE, ItemKind.MOVIE, ItemKind.MOVIE, ItemKind.SERIES, ItemKind.BOX_SET), page.items.map { it.item.kind })
    }

    @Test fun aFilmIsMappedFieldByFieldWithTicksAsMilliseconds() {
        enqueueFixture("items_page.json")
        val (item, data) = runBlocking { gateway.children(session, FILMS, 0, 50, nameAsc, ItemFilter()) }.items[0]
        assertEquals(
            Item(
                id = MOVIE_A, kind = ItemKind.MOVIE, name = "Alien Harbour", sortName = "alien harbour", parentId = FILMS, seriesId = null,
                seriesName = null, seasonId = null, indexNumber = null, parentIndexNumber = null, overview = null, runtimeMs = 6_300_000L,
                year = 1979, premiereDate = Instant.parse("1979-06-01T00:00:00Z"), communityRating = 7.4f, officialRating = "12A", childCount = null,
                images = ItemImages("abc123def456", "bd7788aa", "thumb1234", null),
            ),
            item,
        )
        assertEquals(UserData(true, 0L, null, 2, false, Instant.parse("2026-01-05T20:30:00Z"), null), data)
    }

    @Test fun resumePositionFavouriteAndMissingFieldsMapCleanly() {
        enqueueFixture("items_page.json")
        val items = runBlocking { gateway.children(session, FILMS, 0, 50, nameAsc, ItemFilter()) }.items
        val partWatched = items[1].userData!!
        assertEquals(1_800_000L, partWatched.positionMs)
        assertEquals(28.57f, partWatched.playedPercentage)
        assertTrue(partWatched.favourite)
        assertNull(items[1].item.communityRating)
        assertNull(items[1].item.officialRating)
        val bare = items[2].item // nothing optional present: explicit nulls and absent fields
        assertEquals(ItemImages(null, null, null, null), bare.images)
        assertNull(bare.runtimeMs)
        assertNull(bare.premiereDate)
        val series = items[3]
        assertEquals(2, series.item.childCount)
        assertEquals(7, series.userData!!.unplayedItemCount)
        assertEquals("serbd01", series.item.images.backdropTag)
    }

    @Test fun childrenWithFiltersKindsAndRecursionUsesTheReferenceNames() {
        enqueueFixture("items_page.json")
        runBlocking {
            gateway.children(
                session, TV, 100, 20, Sort(SortBy.RELEASE_DATE, descending = true), ItemFilter(unwatchedOnly = true, favouritesOnly = true),
                kinds = setOf(ItemKind.SERIES), recursive = true,
            )
        }
        assertRequest(
            "GET", "/Items",
            mapOf("userId" to U1, "parentId" to TV, "startIndex" to "100", "limit" to "20", "sortBy" to "PremiereDate", "sortOrder" to "Descending",
                "filters" to "IsUnplayed,IsFavorite", "includeItemTypes" to "Series", "recursive" to "true", "fields" to "ChildCount") + IMAGE_QUERY,
        )
    }

    @Test fun everySortAndFilterMapsToItsReferenceValue() {
        val expected = mapOf(SortBy.NAME to "SortName", SortBy.DATE_ADDED to "DateCreated", SortBy.RELEASE_DATE to "PremiereDate", SortBy.RATING to "CommunityRating")
        expected.forEach { (by, wire) ->
            web.enqueue(json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""))
            runBlocking { gateway.children(session, FILMS, 0, 10, Sort(by, false), ItemFilter(unwatchedOnly = true)) }
            val url = nextRequest().requestUrl!!
            assertEquals(wire, url.queryParameter("sortBy"))
            assertEquals("IsUnplayed", url.queryParameter("filters"))
        }
        web.enqueue(json("""{"Items":[]}"""))
        runBlocking { gateway.children(session, FILMS, 0, 10, nameAsc, ItemFilter(favouritesOnly = true)) }
        assertEquals("IsFavorite", nextRequest().requestUrl!!.queryParameter("filters"))
    }

    @Test fun pageStartAndLimitAreClamped() {
        web.enqueue(json("""{"Items":[]}"""))
        runBlocking { gateway.children(session, FILMS, -5, 5000, nameAsc, ItemFilter()) }
        val big = nextRequest().requestUrl!!
        assertEquals("0", big.queryParameter("startIndex"))
        assertEquals("200", big.queryParameter("limit"))
        web.enqueue(json("""{"Items":[]}"""))
        runBlocking { gateway.children(session, FILMS, 0, 0, nameAsc, ItemFilter()) }
        assertEquals("1", nextRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test fun sortAndFilterKeysAreStableAndSafeForCacheKeys() {
        assertEquals("name_asc", Sort(SortBy.NAME, false).key)
        assertEquals("date_added_desc", Sort(SortBy.DATE_ADDED, true).key)
        assertEquals("all", ItemFilter().key)
        assertEquals("unwatched", ItemFilter(unwatchedOnly = true).key)
        assertEquals("favourites", ItemFilter(favouritesOnly = true).key)
        assertEquals("unwatched_favourites", ItemFilter(true, true).key)
        val keys = SortBy.entries.flatMap { listOf(Sort(it, true).key, Sort(it, false).key) } + listOf(ItemFilter().key, ItemFilter(true, true).key)
        assertTrue(keys.all { Regex("[a-z0-9_]+").matches(it) })
        assertEquals(keys.size, keys.toSet().size)
    }

    // ---- item, seasons, episodes

    @Test fun itemFetchesOneItemWithItsOverviewAndNewlinesKept() {
        enqueueFixture("item_movie.json")
        val result = runBlocking { gateway.item(session, MOVIE_A.uppercase()) }
        assertRequest("GET", "/Items/$MOVIE_A", mapOf("userId" to U1))
        assertEquals("In a quiet harbour town,\nsomething stirs beneath the pier.\n\nA thriller.", result.item.overview)
        assertEquals(1_234_567L, result.userData!!.positionMs)
        assertEquals(19.6f, result.userData!!.playedPercentage)
        assertEquals(Instant.parse("2026-03-04T21:15:42.5Z"), result.userData!!.lastPlayed)
    }

    @Test fun seasonsRequestsChildCountAndImages() {
        enqueueFixture("seasons.json")
        val seasons = runBlocking { gateway.seasons(session, SERIES) }
        assertRequest("GET", "/Shows/$SERIES/Seasons", mapOf("userId" to U1, "fields" to "ChildCount") + IMAGE_QUERY)
        assertEquals(listOf(SEASON_1, SEASON_2), seasons.map { it.item.id })
        val first = seasons[0].item
        assertEquals(ItemKind.SEASON, first.kind)
        assertEquals(1, first.indexNumber)
        assertEquals(SERIES, first.seriesId)
        assertEquals(6, first.childCount)
        assertEquals("serprim01", first.images.seriesPrimaryTag)
        assertEquals(5, seasons[1].userData!!.unplayedItemCount)
    }

    @Test fun episodesOfAWholeSeriesSendNoSeasonAndAskForOverviews() {
        enqueueFixture("episodes.json")
        val episodes = runBlocking { gateway.episodes(session, SERIES, null) }
        assertRequest("GET", "/Shows/$SERIES/Episodes", mapOf("userId" to U1, "fields" to "Overview") + IMAGE_QUERY)
        assertEquals(listOf(EPISODE_11, EPISODE_12, EPISODE_21), episodes.map { it.item.id })
        val e = episodes[0].item
        assertEquals(ItemKind.EPISODE, e.kind)
        assertEquals(1, e.indexNumber)
        assertEquals(1, e.parentIndexNumber)
        assertEquals(SEASON_1, e.seasonId)
        assertEquals("Harbour Lights", e.seriesName)
        assertEquals("Episode 1 of season 1.", e.overview)
        assertEquals(2_700_000L, e.runtimeMs)
        assertEquals(900_000L, episodes[2].userData!!.positionMs)
    }

    @Test fun episodesOfOneSeasonSendThatSeason() {
        enqueueFixture("episodes.json")
        runBlocking { gateway.episodes(session, SERIES, SEASON_2.uppercase()) }
        assertRequest("GET", "/Shows/$SERIES/Episodes", mapOf("userId" to U1, "seasonId" to SEASON_2, "fields" to "Overview") + IMAGE_QUERY)
    }

    // ---- home rows

    @Test fun resumeIsAQueryResultOfFilmsAndEpisodes() {
        enqueueFixture("resume.json")
        val items = runBlocking { gateway.resume(session, 12) }
        assertRequest("GET", "/UserItems/Resume", mapOf("userId" to U1, "limit" to "12", "includeItemTypes" to "Movie,Episode") + IMAGE_QUERY)
        assertEquals(listOf(MOVIE_B, EPISODE_21), items.map { it.item.id })
        assertEquals(Instant.parse("2026-04-02T20:00:00Z"), items[1].userData!!.lastPlayed)
    }

    @Test fun nextUpTurnsOffResumableAndRewatching() {
        enqueueFixture("nextup.json")
        val items = runBlocking { gateway.nextUp(session, 30) }
        assertRequest(
            "GET", "/Shows/NextUp",
            mapOf("userId" to U1, "limit" to "30", "enableResumable" to "false", "enableRewatching" to "false") + IMAGE_QUERY,
        )
        assertEquals(listOf(EPISODE_12), items.map { it.item.id })
    }

    @Test fun latestIsABareArrayNotAQueryResult() {
        enqueueFixture("latest.json")
        val items = runBlocking { gateway.latest(session, FILMS.uppercase(), 16) }
        assertRequest("GET", "/Items/Latest", mapOf("userId" to U1, "parentId" to FILMS, "limit" to "16") + IMAGE_QUERY)
        assertEquals(listOf(MOVIE_C, MOVIE_A), items.map { it.item.id })
    }

    // ---- search

    @Test fun searchUsesTheTermRecursivelyForFilmsSeriesAndEpisodes() {
        enqueueFixture("items_page.json")
        val items = runBlocking { gateway.search(session, "  harbour ", 20) }
        assertRequest(
            "GET", "/Items",
            mapOf("userId" to U1, "searchTerm" to "harbour", "recursive" to "true", "includeItemTypes" to "Movie,Series,Episode", "limit" to "20") + IMAGE_QUERY,
        )
        assertEquals(5, items.size)
    }

    @Test fun searchLimitIsAtMostFiftyAndTheTermAtMostAHundredCharacters() {
        web.enqueue(json("""{"Items":[]}"""))
        runBlocking { gateway.search(session, "a".repeat(150), 500) }
        val url = nextRequest().requestUrl!!
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("a".repeat(100), url.queryParameter("searchTerm"))
    }

    @Test fun aBlankSearchMakesNoRequest() {
        assertEquals(emptyList<ItemWithUserData>(), runBlocking { gateway.search(session, "   \n\t ", 20) })
        assertEquals(emptyList<ItemWithUserData>(), runBlocking { gateway.search(session, "", 20) })
        assertEquals(0, web.requestCount)
    }

    // ---- empty lists

    @Test fun emptyAnswersAreEmptyLists() {
        web.enqueue(json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""))
        web.enqueue(json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""))
        web.enqueue(json("[]"))
        web.enqueue(json("""{"TotalRecordCount":0}"""))
        runBlocking {
            assertEquals(Page(emptyList<ItemWithUserData>(), 0, 0), gateway.children(session, FILMS, 0, 10, nameAsc, ItemFilter()))
            assertEquals(emptyList<ItemWithUserData>(), gateway.resume(session, 10))
            assertEquals(emptyList<ItemWithUserData>(), gateway.latest(session, FILMS, 10))
            assertEquals(emptyList<LibraryView>(), gateway.views(session))
        }
    }

    // ---- mutations

    @Test fun markWatchedAndUnwatchedUsePostAndDeleteOnUserPlayedItems() {
        web.enqueue(json("{}"))
        web.enqueue(json("{}"))
        runBlocking {
            gateway.setPlayed(session, MOVIE_A.uppercase(), true)
            gateway.setPlayed(session, MOVIE_A, false)
        }
        assertRequest("POST", "/UserPlayedItems/$MOVIE_A", mapOf("userId" to U1))
        assertRequest("DELETE", "/UserPlayedItems/$MOVIE_A", mapOf("userId" to U1))
    }

    @Test fun favouriteAndUnfavouriteUsePostAndDeleteOnUserFavoriteItems() {
        web.enqueue(json("{}"))
        web.enqueue(json("{}"))
        runBlocking {
            gateway.setFavourite(session, MOVIE_B, true)
            gateway.setFavourite(session, MOVIE_B, false)
        }
        assertRequest("POST", "/UserFavoriteItems/$MOVIE_B", mapOf("userId" to U1))
        assertRequest("DELETE", "/UserFavoriteItems/$MOVIE_B", mapOf("userId" to U1))
    }

    @Test fun clearResumePostsAZeroPositionToUserData() {
        web.enqueue(json("{}"))
        runBlocking { gateway.clearResume(session, MOVIE_B) }
        val request = assertRequest("POST", "/UserItems/$MOVIE_B/UserData", mapOf("userId" to U1))
        assertEquals("""{"PlaybackPositionTicks":0}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun theMutationsAreIdempotentSoOneNetworkFailureIsRetried() {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(json("{}"))
        runBlocking { gateway.setPlayed(session, MOVIE_A, true) }
        assertEquals(2, web.requestCount)
    }

    // ---- errors

    @Test fun serverAnswersMapToTheRightErrorKinds() {
        web.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AppError.Kind.UNAUTHORISED, failure { gateway.resume(session, 5) }.kind)
        web.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.item(session, MOVIE_A) }.kind)
        web.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.views(session) }.kind)
        web.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AppError.Kind.UNAUTHORISED, failure { gateway.setFavourite(session, MOVIE_A, true) }.kind)
        web.enqueue(json("this is not json"))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.nextUp(session, 5) }.kind)
    }

    @Test fun offlineIsOfflineAndNothingIsSent() {
        goOffline()
        assertEquals(AppError.Kind.OFFLINE, failure { gateway.views(session) }.kind)
        assertEquals(AppError.Kind.OFFLINE, failure { gateway.clearResume(session, MOVIE_A) }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun anIdThatIsNotAGuidIsRefusedBeforeAnyRequest() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.item(session, "../Users") }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.seasons(session, "x") }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.children(session, "abc", 0, 10, nameAsc, ItemFilter()) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.episodes(session, SERIES, "nope") }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.setPlayed(session, "1/2", true) }.kind)
        assertEquals(0, web.requestCount)
    }
}
