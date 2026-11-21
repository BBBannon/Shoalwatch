package app.shoalwatch.data.manage

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import app.shoalwatch.data.manage.api.ItemEdit
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Base64

/** Every management call: path, query and body as sent, the answer as mapped, and the gate (Milestone 10). */
class ManageGatewayTest : ManageGatewayTestBase() {
    private val searchAnswer = """[
        {"Name":"The Silent Harbour","ProductionYear":1999,"PremiereDate":"1999-05-04T00:00:00.0000000Z","SearchProviderName":"TheMovieDb",
         "ProviderIds":{"Tmdb":"123","Imdb":"tt0123"},"ImageUrl":"https://image.tmdb.org/t/p/original/x.jpg","Overview":"A film."},
        {"Name":"No ids","ProviderIds":{}},
        {"Name":"The Silent Harbour","ProductionYear":1999,"SearchProviderName":"TheMovieDb","ProviderIds":{"Tmdb":"123","Imdb":"tt0123"}}
    ]"""

    @Test fun identifySearchesAsTheKindWithNameYearAndItem() = runBlocking {
        web.enqueue(json(searchAnswer))
        val matches = gateway.identify(session, ITEM, IdentifyKind.MOVIE, "  Silent Harbour ", 1999)
        val r = nextRequest()
        assertEquals("POST", r.method)
        assertEquals("/Items/RemoteSearch/Movie", r.path)
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("Silent Harbour", body["SearchInfo"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
        assertEquals("1999", body["SearchInfo"]!!.jsonObject["Year"]!!.jsonPrimitive.content)
        assertEquals(ITEM, body["ItemId"]!!.jsonPrimitive.content)
        assertEquals("one kept: no ids is dropped, the repeat is dropped", 1, matches.size)
        val m = matches.single()
        assertEquals("The Silent Harbour", m.name)
        assertEquals(1999, m.year)
        assertEquals(LocalDate.of(1999, 5, 4), m.premiereDate)
        assertEquals("TheMovieDb", m.providerName)
        assertEquals(mapOf("Tmdb" to "123", "Imdb" to "tt0123"), m.providerIds)
        assertFalse("the picture address is not kept anywhere", m.toString().contains("tmdb") || m.key.contains("image"))
    }

    @Test fun seriesSearchUsesTheSeriesPath() = runBlocking {
        web.enqueue(json("[]"))
        gateway.identify(session, ITEM, IdentifyKind.SERIES, "Harbour Lights", null)
        val r = nextRequest()
        assertEquals("/Items/RemoteSearch/Series", r.path)
        assertTrue("no year is sent when none was typed", !r.body.readUtf8().contains("Year"))
    }

    @Test fun applyPostsATypedResultOnce() = runBlocking {
        web.enqueue(json(searchAnswer))
        val m = gateway.identify(session, ITEM, IdentifyKind.MOVIE, "x", null).single()
        nextRequest()
        web.enqueue(noContent())
        gateway.applyMatch(session, ITEM, m, replaceAllImages = true)
        val r = nextRequest()
        assertEquals("/Items/RemoteSearch/Apply/$ITEM?replaceAllImages=true", r.path)
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals(setOf("Name", "ProductionYear", "PremiereDate", "ProviderIds", "SearchProviderName"), body.keys)
        assertEquals("1999-05-04T00:00:00.0000000Z", body["PremiereDate"]!!.jsonPrimitive.content)
        assertEquals("123", body["ProviderIds"]!!.jsonObject["Tmdb"]!!.jsonPrimitive.content)
    }

    @Test fun imagesAreListedWithoutPathsAndInOrder() = runBlocking {
        web.enqueue(json("""[
            {"ImageType":"Backdrop","ImageIndex":1,"Path":"/srv/media/secret/backdrop1.jpg","Width":1920,"Height":1080,"Size":1000},
            {"ImageType":"Primary","Path":"/srv/media/secret/poster.jpg","Width":1000,"Height":1500,"Size":2000},
            {"ImageType":"Backdrop","ImageIndex":0,"Width":1920,"Height":1080},
            {"ImageType":"Disc","ImageIndex":0},
            {"ImageType":"Primary","ImageIndex":"0"}
        ]"""))
        val images = gateway.images(session, ITEM)
        assertEquals("/Items/$ITEM/Images", nextRequest().path)
        assertEquals(listOf(ImageKind.PRIMARY to 0, ImageKind.BACKDROP to 0, ImageKind.BACKDROP to 1), images.map { it.kind to it.index })
        assertEquals(1000, images.first().width)
        assertFalse(images.toString().contains("srv"))
    }

    @Test fun remoteImagesAskForTheKindAndKeepTheAddressOnlyForTheServer() = runBlocking {
        web.enqueue(json("""{"Images":[
            {"ProviderName":"TheMovieDb","Url":"https://image.tmdb.org/a.jpg","ThumbnailUrl":"https://image.tmdb.org/w300/a.jpg","Width":1000,"Height":1500,"CommunityRating":7.4,"VoteCount":12,"Language":"en","Type":"Primary"},
            {"ProviderName":"Fanart","Url":"javascript:alert(1)","Type":"Primary"},
            {"ProviderName":"Fanart","Url":"https://x.example/b.jpg","Type":"Backdrop"},
            {"ProviderName":"TheMovieDb","Url":"https://image.tmdb.org/a.jpg","Type":"Primary"}
        ],"TotalRecordCount":4,"Providers":["TheMovieDb","Fanart"]}"""))
        val remote = gateway.remoteImages(session, ITEM, ImageKind.PRIMARY, allLanguages = false)
        assertEquals("/Items/$ITEM/RemoteImages?type=Primary&startIndex=0&limit=100&includeAllLanguages=false", nextRequest().path)
        val only = remote.images.single()
        assertEquals("TheMovieDb", only.providerName)
        assertEquals(1000, only.width)
        assertEquals(7.4, only.rating!!, 0.001)
        assertEquals("en", only.language)
        assertEquals(listOf("TheMovieDb", "Fanart"), remote.providers)
        assertEquals("RemoteImage(PRIMARY)", only.toString())

        web.enqueue(noContent())
        gateway.useRemoteImage(session, ITEM, only)
        val r = nextRequest()
        assertEquals("POST", r.method)
        assertEquals("/Items/$ITEM/RemoteImages/Download?type=Primary&imageUrl=https%3A%2F%2Fimage.tmdb.org%2Fa.jpg", r.path)
        assertEquals(0L, r.bodySize)
    }

    @Test fun uploadSendsTheJpegAsBase64WithItsType() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        web.enqueue(noContent())
        gateway.uploadImage(session, ITEM, ImageKind.LOGO, PreparedImage(jpeg, 10, 10))
        val r = nextRequest()
        assertEquals("/Items/$ITEM/Images/Logo", r.path)
        assertEquals("image/jpeg", r.getHeader("Content-Type"))
        assertArrayEquals(jpeg, Base64.getDecoder().decode(r.body.readUtf8()))
    }

    @Test fun aPictureThatIsNotAPreparedJpegIsNeverSent() {
        assertTrue(runCatching { runBlocking { gateway.uploadImage(session, ITEM, ImageKind.PRIMARY, PreparedImage("GIF89a".toByteArray(), 1, 1)) } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, web.requestCount)
    }

    @Test fun deleteImageNamesKindAndIndex() = runBlocking {
        web.enqueue(noContent())
        gateway.deleteImage(session, ITEM, ImageKind.BACKDROP, 2)
        val r = nextRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/Items/$ITEM/Images/Backdrop/2", r.path)
    }

    @Test fun refreshSendsTheDashboardsChoices() = runBlocking {
        fun asked(mode: RefreshMode, images: Boolean): String {
            web.enqueue(noContent())
            runBlocking { gateway.refresh(session, ITEM, mode, images) }
            return nextRequest().path!!
        }
        val base = "/Items/$ITEM/Refresh?"
        assertEquals(base + "metadataRefreshMode=Default&imageRefreshMode=Default&replaceAllMetadata=false&replaceAllImages=false&regenerateTrickplay=false", asked(RefreshMode.SCAN, true))
        assertEquals(base + "metadataRefreshMode=FullRefresh&imageRefreshMode=FullRefresh&replaceAllMetadata=false&replaceAllImages=true&regenerateTrickplay=false", asked(RefreshMode.MISSING, true))
        assertEquals(base + "metadataRefreshMode=FullRefresh&imageRefreshMode=FullRefresh&replaceAllMetadata=true&replaceAllImages=false&regenerateTrickplay=false", asked(RefreshMode.REPLACE_ALL, false))
    }

    private val serverItem = """{"Id":"$DASHED","Type":"Movie","Name":"Old name","ForcedSortName":"old","OriginalTitle":null,
        "Overview":"Old.","ProductionYear":1999,"PremiereDate":"1999-05-04T00:00:00.0000000Z","OfficialRating":"PG","CommunityRating":6.5,
        "Genres":["Drama"],"Tags":[],"Studios":[{"Name":"Old Studio","Id":"abc"}],"LockedFields":["Name","Bogus"],"LockData":false,
        "ProviderIds":{"Tmdb":"1"},"FutureField":{"Deep":[1,2.50,null]},"RunTimeTicks":72000000000,"UserData":{"Played":true}}"""

    @Test fun metadataReadsTheEditorAndTheItem() = runBlocking {
        web.enqueue(json("""{"ParentalRatingOptions":[{"Name":"U","Value":0},{"Name":"PG","Value":10},{"Name":"PG"}]}"""))
        web.enqueue(json(serverItem))
        val form = gateway.metadata(session, ITEM)
        assertEquals("/Items/$ITEM/MetadataEditor", nextRequest().path)
        assertEquals("/Items/$ITEM?userId=${session.userId}", nextRequest().path)
        assertEquals("Old name", form.name)
        assertEquals("old", form.sortName)
        assertEquals("", form.originalTitle)
        assertEquals(1999, form.year)
        assertEquals(6.5, form.communityRating!!, 0.0001)
        assertEquals(listOf("Old Studio"), form.studios)
        assertEquals(setOf(LockableField.NAME), form.locked)
        assertEquals(listOf("U", "PG"), form.ratingOptions)
    }

    @Test fun saveReadsTheItemAgainAndPostsItWholeWithOnlyTheFormReplaced() = runBlocking {
        web.enqueue(json(serverItem))
        web.enqueue(noContent())
        val edit = MetadataEdit(
            name = " New\u202E name ", sortName = "", originalTitle = "Original", overview = "Line one.\n\nLine two.", year = 2001,
            premiereDate = LocalDate.of(2001, 2, 3), officialRating = "12A", communityRating = 7.25,
            genres = listOf("Drama", "drama", " ", "Mystery"), tags = listOf("Mine"), studios = listOf("New Studio"),
            locked = setOf(LockableField.OVERVIEW, LockableField.NAME), lockAll = true,
        )
        gateway.saveMetadata(session, ITEM, edit, shownForm())
        assertEquals("/Items/$ITEM?userId=${session.userId}", nextRequest().path)
        val r = nextRequest()
        assertEquals("POST", r.method)
        assertEquals("/Items/$ITEM", r.path)
        val sent = Json.parseToJsonElement(r.body.readUtf8()) as JsonObject
        assertEquals("New name", sent["Name"]!!.jsonPrimitive.content)
        assertEquals("null", sent["ForcedSortName"].toString())
        assertEquals("2001", sent["ProductionYear"].toString())
        assertEquals("2001-02-03T00:00:00.0000000Z", sent["PremiereDate"]!!.jsonPrimitive.content)
        assertEquals("7.3", sent["CommunityRating"].toString())
        assertEquals(listOf("Drama", "Mystery"), sent["Genres"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("""[{"Name":"New Studio"}]""", sent["Studios"].toString())
        assertEquals("""["Name","Overview"]""", sent["LockedFields"].toString())
        assertEquals("true", sent["LockData"].toString())
        assertEquals("kept as it came", """{"Deep":[1,2.50,null]}""", sent["FutureField"].toString())
        assertEquals("72000000000", sent["RunTimeTicks"].toString())
        assertEquals(DASHED, sent["Id"]!!.jsonPrimitive.content)
    }

    @Test fun anItemThatIsNotTheOneAskedForIsNeverPostedBack() {
        web.enqueue(json(serverItem.replace(DASHED, "ffffffffffffffffffffffffffffffff")))
        val e = failure { gateway.saveMetadata(session, ITEM, MetadataEdit("a", "", "", "", null, null, "", null, emptyList(), emptyList(), emptyList(), emptySet(), false), shownForm()) }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        assertEquals(1, web.requestCount)
    }

    // ---- saving only what changed (review finding 6)

    /** An item whose values the form cannot show exactly: a studio with a comma, a rating with two decimals, an overview with edges. */
    private val awkwardItem = """{"Id":"$DASHED","Type":"Movie","Name":"Old name","ForcedSortName":null,"OriginalTitle":null,
        "Overview":"  Old overview.   ","ProductionYear":1999,"PremiereDate":"1999-05-04T00:00:00.0000000Z","OfficialRating":"PG",
        "CommunityRating":6.54,"Genres":["Drama"],"Tags":[],"Studios":[{"Name":"Lions Gate Films, Inc.","Id":"abc"}],
        "LockedFields":[],"LockData":false}"""

    private fun shownForm(item: String = serverItem): MetadataForm =
        ItemEdit.form(Json.parseToJsonElement(item) as JsonObject, ITEM, emptyList())!!

    @Test fun savingAnUntouchedFormSendsNothing() = runBlocking {
        val shown = shownForm(awkwardItem)
        assertEquals(listOf("Lions Gate Films, Inc."), shown.studios)
        gateway.saveMetadata(session, ITEM, shown.asEdit(), shown)
        assertEquals("nothing read, nothing written", 0, web.requestCount)
    }

    @Test fun onlyTheChangedFieldIsWrittenAndEveryOtherValueGoesBackAsItCame() = runBlocking {
        val shown = shownForm(awkwardItem)
        web.enqueue(json(awkwardItem))
        web.enqueue(noContent())
        gateway.saveMetadata(session, ITEM, shown.asEdit().copy(name = "New name"), shown)
        nextRequest()
        val sent = Json.parseToJsonElement(nextRequest().body.readUtf8()) as JsonObject
        assertEquals("New name", sent["Name"]!!.jsonPrimitive.content)
        assertEquals("not rounded", "6.54", sent["CommunityRating"].toString())
        assertEquals("not trimmed", "  Old overview.   ", sent["Overview"]!!.jsonPrimitive.content)
        assertEquals("not split, not rebuilt", """[{"Name":"Lions Gate Films, Inc.","Id":"abc"}]""", sent["Studios"].toString())
        assertEquals("null", sent["ForcedSortName"].toString())
    }

    @Test fun eachTypedChangeIsWritten() {
        val shown = shownForm(awkwardItem)
        fun sent(edit: MetadataEdit): JsonObject = runBlocking {
            web.enqueue(json(awkwardItem))
            web.enqueue(noContent())
            gateway.saveMetadata(session, ITEM, edit, shown)
            nextRequest()
            Json.parseToJsonElement(nextRequest().body.readUtf8()) as JsonObject
        }
        assertEquals("7.0", sent(shown.asEdit().copy(communityRating = 7.0))["CommunityRating"].toString())
        assertEquals("""[{"Name":"Lions Gate Films, Inc."},{"Name":"Other"}]""", sent(shown.asEdit().copy(studios = shown.studios + "Other"))["Studios"].toString())
        assertEquals("""["Name"]""", sent(shown.asEdit().copy(locked = setOf(LockableField.NAME)))["LockedFields"].toString())
        assertEquals("New.", sent(shown.asEdit().copy(overview = "New."))["Overview"]!!.jsonPrimitive.content)
    }

    // ---- delete (review finding 1)

    private fun itemThat(canDelete: String?) =
        json("""{"Id":"$DASHED","Type":"Movie","Name":"Film"${canDelete?.let { ",\"CanDelete\":$it" } ?: ""}}""")

    @Test fun deleteAsksTheItemsOwnCanDeleteForThisUserJustBeforeSending() = runBlocking {
        allowed = false
        web.enqueue(itemThat("true"))
        web.enqueue(noContent())
        gateway.delete(session, ITEM)
        assertEquals("/Items/$ITEM?userId=${session.userId}", nextRequest().path)
        val r = nextRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/Items/$ITEM", r.path)
    }

    @Test fun anItemThisProfileMayNotDeleteIsNeverSentAndSaysSo() {
        for (answer in listOf("false", null, "\"true\"")) {
            web.enqueue(itemThat(answer))
            val e = failure { gateway.delete(session, ITEM) }
            assertEquals(answer, AppError.Kind.FORBIDDEN, e.kind)
            assertEquals("The server doesn't let this profile delete that.", e.userMessage)
        }
        assertEquals("only the reads", 3, web.requestCount)
    }

    @Test fun withoutTheDeleteRightNothingIsAskedOrSent() {
        mayDelete = false
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.delete(session, ITEM) }.kind)
        assertEquals(false, runBlocking { gateway.canDelete(session, ITEM) })
        assertEquals(0, web.requestCount)
    }

    @Test fun canDeleteIsTheItemsOwnAnswer() = runBlocking {
        web.enqueue(itemThat("true"))
        assertEquals(true, gateway.canDelete(session, ITEM))
        assertEquals("/Items/$ITEM?userId=${session.userId}", nextRequest().path)
        web.enqueue(itemThat("false"))
        assertEquals(false, gateway.canDelete(session, ITEM))
        web.enqueue(json("""{"Id":"ffffffffffffffffffffffffffffffff","CanDelete":true}"""))
        assertEquals("another item's answer is not this one's", false, gateway.canDelete(session, ITEM))
    }

    @Test fun aRefused401OnTheDeleteDoesNotSignTheProfileOut() {
        web.enqueue(itemThat("true"))
        web.enqueue(MockResponse().setResponseCode(401))
        web.enqueue(json("""{"Id":"$DASHED"}"""))
        val e = failure { gateway.delete(session, ITEM) }
        assertEquals(AppError.Kind.FORBIDDEN, e.kind)
        assertEquals("tok-1", store.cachedToken(session.serverId, session.userId))
        nextRequest(); nextRequest()
        assertEquals("/Users/Me", nextRequest().path)
    }

    @Test fun nothingIsSentWithoutTheAdministrator() {
        allowed = false
        val calls: List<suspend () -> Unit> = listOf(
            { gateway.identify(session, ITEM, IdentifyKind.MOVIE, "x", null) },
            { gateway.images(session, ITEM) },
            { gateway.remoteImages(session, ITEM, ImageKind.PRIMARY, true) },
            { gateway.deleteImage(session, ITEM, ImageKind.PRIMARY, 0) },
            { gateway.metadata(session, ITEM) },
            { gateway.refresh(session, ITEM, RefreshMode.SCAN, false) },
            { gateway.uploadImage(session, ITEM, ImageKind.PRIMARY, PreparedImage(byteArrayOf(-1, -40, -1, 0), 1, 1)) },
        )
        for (call in calls) assertEquals(AppError.Kind.FORBIDDEN, failure(call).kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun aChangeIsNotWrittenWhenTheGateClosedWhileItWaited() {
        whileWaitingForTheAddress = { allowed = false }
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.refresh(session, ITEM, RefreshMode.SCAN, false) }.kind)
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.deleteImage(session, ITEM, ImageKind.PRIMARY, 0) }.kind)
        assertEquals(0, web.requestCount)
        allowed = true
        web.enqueue(itemThat("true"))
        whileWaitingForTheAddress = { mayDelete = false }
        assertEquals(AppError.Kind.FORBIDDEN, failure { gateway.delete(session, ITEM) }.kind)
        assertEquals("the read only; no DELETE", listOf("GET"), (1..web.requestCount).map { nextRequest().method })
    }

    @Test fun anIdNotInTheLibrarysFormIsRefusedBeforeSending() {
        for (bad in listOf(DASHED, ITEM.uppercase(), "../System/Restart", "")) {
            assertEquals(bad, AppError.Kind.SERVER_NOT_FOUND, failure { gateway.refresh(session, bad, RefreshMode.SCAN, false) }.kind)
            assertEquals(bad, AppError.Kind.SERVER_NOT_FOUND, failure { gateway.delete(session, bad) }.kind)
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun aChangeIsSentOnceOnly() {
        web.enqueue(json("", code = 500))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.refresh(session, ITEM, RefreshMode.SCAN, false) }.kind)
        web.enqueue(itemThat("true"))
        web.enqueue(json("", code = 503).setHeader("Retry-After", "0"))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.delete(session, ITEM) }.kind)
        assertEquals(3, web.requestCount)
    }

    @Test fun hostileAnswersCostOnlyThemselves() = runBlocking {
        val many = (1..500).joinToString(",") { """{"Name":"N$it","ProviderIds":{"Tmdb":"$it"}}""" }
        web.enqueue(json("[$many, 7, null, \"x\", {\"Name\":\"\\u202Eevil\",\"ProviderIds\":{\"bad key\":\"1\",\"Tmdb\":\"a b\"}}]"))
        assertEquals(ManageLimits.RESULTS, gateway.identify(session, ITEM, IdentifyKind.MOVIE, "x", null).size)
        web.enqueue(json("""{"Images":[{"ProviderName":"\u0007P","Url":"https://ok.example/a.jpg","Width":"1000","CommunityRating":99}],"TotalRecordCount":-3}"""))
        val img = gateway.remoteImages(session, ITEM, ImageKind.THUMB, true).images.single()
        assertEquals("P", img.providerName)
        assertNull("a string is not a number", img.width)
        assertNull("out of range", img.rating)
    }
}
