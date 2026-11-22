"""Run from anywhere: python -I tools/mockjelly/test_mock_lists.py   (the Milestone 11 routes of the test stand-in)"""
import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I does not add the script folder
import mock_jellyfin as m  # noqa: E402
import mock_admin as a  # noqa: E402
import mock_lists as g  # noqa: E402

m.build_catalogue()
m.seed_state()

RICH = m.guid("film-12")  # people, a local and an online trailer, extras, two versions
PLAIN = m.guid("film-5")
SATURDAY, FAMILY = g.gid("playlist-saturday"), g.gid("playlist-family")  # ben's: one private, one public
HARBOUR_BOX = g.gid("box-0")
MARIA = g.gid("person-0")


class Served(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), m.Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        a.CONFIG.update({"ben_admin": True, "pending_restart": False, "admin_forbidden": False})
        a.setup(m.SERVER_ID, m.USER_ID, m.USER_NAME, m.guid, m.NOW, [(m.FILMS_ID, "Films", "movies"), (m.TV_ID, "TV", "tvshows")],
                tokens=m.TOKENS, items=m.ITEMS)
        m.setup_manage()
        self.saved = {k: dict(v) for k, v in m.ITEMS.items()}
        self.ben = self.sign_in(m.USER_NAME, m.PASSWORD)
        self.kid = self.sign_in("kid", "kid-password")

    def tearDown(self):
        for k, v in self.saved.items():
            m.ITEMS[k].clear()
            m.ITEMS[k].update(v)

    def call(self, method, path, body=None, token=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        head = 'MediaBrowser Client="Shoalwatch", Device="Test", DeviceId="dev-lists", Version="1.2.0"'
        if token:
            head += f', Token="{token}"'
        req.add_header("Authorization", head)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def json(self, method, path, body=None, token=None):
        status, data = self.call(method, path, body, token or self.ben)
        return status, (json.loads(data) if data else None)

    def sign_in(self, name, password):
        status, data = self.call("POST", "/Users/AuthenticateByName", {"Username": name, "Pw": password})
        return json.loads(data)["AccessToken"] if status == 200 else None

    def names(self, path, token=None):
        return [i["Name"] for i in self.json("GET", path, token=token)[1]["Items"]]


class Playlists(Served):
    LIST = "/Items?includeItemTypes=Playlist&recursive=true&sortBy=SortName&fields=ChildCount"

    def test_lists_are_the_owners_and_public_ones(self):
        status, body = self.json("GET", self.LIST)
        self.assertEqual(200, status)
        self.assertEqual(["Family favourites", "Saturday films"], [i["Name"] for i in body["Items"]])
        self.assertEqual([3, 4], [i["ChildCount"] for i in body["Items"]])
        self.assertEqual(["Family favourites"], self.names(self.LIST, self.kid), "a private playlist is its owner's alone")
        self.assertEqual(401, self.call("GET", self.LIST)[0])

    def test_items_carry_playlist_item_ids_and_page(self):
        status, body = self.json("GET", f"/Playlists/{SATURDAY}/Items?userId={m.USER_ID}&startIndex=1&limit=2")
        self.assertEqual(200, status)
        self.assertEqual((4, 1, 2), (body["TotalRecordCount"], body["StartIndex"], len(body["Items"])))
        self.assertTrue(all(i["PlaylistItemId"] for i in body["Items"]))
        self.assertEqual(["Movie", "Episode"], [i["Type"] for i in body["Items"]])
        self.assertEqual(404, self.json("GET", f"/Playlists/{SATURDAY}/Items", token=self.kid)[0])
        self.assertEqual(404, self.json("GET", f"/Playlists/{'0' * 32}/Items")[0])

    def test_create_makes_a_private_playlist_owned_by_the_caller(self):
        status, body = self.json("POST", "/Playlists", {"Name": "Quiet night", "Ids": [RICH, PLAIN], "UserId": m.USER_ID, "IsPublic": False})
        self.assertEqual(200, status)
        self.assertRegex(body["Id"], r"^[0-9a-f]{32}$")
        self.assertEqual(2, self.json("GET", f"/Playlists/{body['Id']}/Items")[1]["TotalRecordCount"])
        self.assertIn("Quiet night", self.names(self.LIST))
        self.assertNotIn("Quiet night", self.names(self.LIST, self.kid))
        self.assertEqual("CreatePlaylist", g.EVENTS[-1]["Kind"])

    def test_create_is_checked(self):
        self.assertEqual(400, self.json("POST", "/Playlists", {"Name": "  ", "Ids": []})[0])
        self.assertEqual(400, self.json("POST", "/Playlists", {"Name": "x", "Ids": ["not-an-id"]})[0])
        self.assertEqual(403, self.json("POST", "/Playlists", {"Name": "x", "Ids": [], "UserId": m.ANNA_ID})[0])

    def test_owner_renames_adds_removes_and_moves(self):
        self.assertEqual(204, self.json("POST", f"/Playlists/{SATURDAY}", {"Name": "Sunday films"})[0])
        self.assertIn("Sunday films", self.names(self.LIST))
        self.assertEqual(400, self.json("POST", f"/Playlists/{SATURDAY}", {"Name": ""})[0])
        self.assertEqual(204, self.json("POST", f"/Playlists/{SATURDAY}/Items?ids={RICH},{PLAIN}&userId={m.USER_ID}")[0])
        entries = self.json("GET", f"/Playlists/{SATURDAY}/Items")[1]["Items"]
        self.assertEqual(6, len(entries))
        last = entries[-1]["PlaylistItemId"]
        self.assertEqual(204, self.json("POST", f"/Playlists/{SATURDAY}/Items/{last}/Move/0")[0])
        moved = self.json("GET", f"/Playlists/{SATURDAY}/Items")[1]["Items"]
        self.assertEqual(last, moved[0]["PlaylistItemId"])
        self.assertEqual(404, self.json("POST", f"/Playlists/{SATURDAY}/Items/nosuchentry/Move/0")[0])
        self.assertEqual(400, self.json("POST", f"/Playlists/{SATURDAY}/Items/{last}/Move/99")[0])
        self.assertEqual(204, self.json("DELETE", f"/Playlists/{SATURDAY}/Items?entryIds={last},{moved[1]['PlaylistItemId']}")[0])
        self.assertEqual(4, self.json("GET", f"/Playlists/{SATURDAY}/Items")[1]["TotalRecordCount"])
        self.assertEqual(400, self.json("DELETE", f"/Playlists/{SATURDAY}/Items")[0])
        self.assertEqual(400, self.json("POST", f"/Playlists/{SATURDAY}/Items?ids=oops")[0])

    def test_a_non_owner_is_refused_with_403(self):
        entry = self.json("GET", f"/Playlists/{FAMILY}/Items")[1]["Items"][0]["PlaylistItemId"]
        for method, path, body in (("POST", f"/Playlists/{FAMILY}", {"Name": "Mine now"}), ("POST", f"/Playlists/{FAMILY}/Items?ids={RICH}", None),
                                   ("DELETE", f"/Playlists/{FAMILY}/Items?entryIds={entry}", None), ("POST", f"/Playlists/{FAMILY}/Items/{entry}/Move/1", None)):
            self.assertEqual(403, self.json(method, path, body, token=self.kid)[0], path)
        self.assertEqual(3, self.json("GET", f"/Playlists/{FAMILY}/Items")[1]["TotalRecordCount"])
        self.assertEqual(404, self.json("POST", f"/Playlists/{SATURDAY}", {"Name": "Mine now"}, token=self.kid)[0])  # a private one is not even seen

    def test_delete_is_401_when_refused_and_the_session_stays(self):
        row = self.json("GET", f"/Items/{FAMILY}?userId={m.KID_ID}", token=self.kid)[1]
        self.assertFalse(row["CanDelete"])
        self.assertEqual(401, self.json("DELETE", f"/Items/{FAMILY}", token=self.kid)[0])
        self.assertEqual(200, self.json("GET", "/Users/Me", token=self.kid)[0], "the sign-in is still good")
        self.assertTrue(self.json("GET", f"/Items/{SATURDAY}", token=self.ben)[1]["CanDelete"])
        self.assertEqual(204, self.json("DELETE", f"/Items/{SATURDAY}")[0])
        self.assertNotIn("Saturday films", self.names(self.LIST))
        self.assertEqual(404, self.json("GET", f"/Playlists/{SATURDAY}/Items")[0])


class Collections(Served):
    LIST = "/Items?includeItemTypes=BoxSet&recursive=true&sortBy=SortName"

    def test_collections_list_and_open(self):
        self.assertEqual(["Family Night", "Harbour Collection"], self.names(self.LIST))
        status, body = self.json("GET", f"/Items?parentId={HARBOUR_BOX}")
        self.assertEqual(200, status)
        self.assertEqual(3, body["TotalRecordCount"])
        self.assertEqual({"Movie"}, {i["Type"] for i in body["Items"]})

    def test_create_add_and_remove_need_collection_management(self):
        status, body = self.json("POST", f"/Collections?name=Cosy&ids={RICH},{PLAIN}&isLocked=false")
        self.assertEqual(200, status)
        cid = body["Id"]
        self.assertEqual(2, self.json("GET", f"/Items?parentId={cid}")[1]["TotalRecordCount"])
        self.assertEqual(204, self.json("POST", f"/Collections/{cid}/Items?ids={m.guid('film-9')}")[0])
        self.assertEqual(3, self.json("GET", f"/Items?parentId={cid}")[1]["TotalRecordCount"])
        self.assertEqual(204, self.json("DELETE", f"/Collections/{cid}/Items?ids={RICH},{PLAIN}")[0])
        self.assertEqual(1, self.json("GET", f"/Items?parentId={cid}")[1]["TotalRecordCount"])
        self.assertEqual(404, self.json("POST", f"/Collections/{'0' * 32}/Items?ids={RICH}")[0])
        self.assertEqual(400, self.json("POST", "/Collections?name=")[0])
        self.assertEqual(400, self.json("POST", f"/Collections/{cid}/Items?ids=bad")[0])
        for method, path in (("POST", f"/Collections?name=Nope&ids={RICH}"), ("POST", f"/Collections/{cid}/Items?ids={RICH}"),
                             ("DELETE", f"/Collections/{cid}/Items?ids={RICH}")):
            self.assertEqual(403, self.json(method, path, token=self.kid)[0], path)
        self.assertEqual(200, self.json("GET", "/Users/Me", token=self.kid)[0])
        self.assertNotIn("Nope", self.names(self.LIST))


class Details(Served):
    def test_detail_has_people_trailers_and_versions(self):
        status, row = self.json("GET", f"/Items/{RICH}?userId={m.USER_ID}")
        self.assertEqual(200, status)
        self.assertEqual(5, len(row["People"]))
        self.assertEqual({"Actor", "Director", "Writer"}, {p["Type"] for p in row["People"]})
        self.assertTrue(row["People"][0]["PrimaryImageTag"])
        self.assertNotIn("PrimaryImageTag", row["People"][4], "one person has no picture")
        self.assertEqual("https://www.youtube.com/watch?v=aqz-KE-bpKQ", row["RemoteTrailers"][0]["Url"])
        self.assertEqual((1, 2), (row["LocalTrailerCount"], row["SpecialFeatureCount"]))
        self.assertEqual(2, len(row["MediaSources"]))
        self.assertNotEqual(*[s["Id"] for s in row["MediaSources"]])
        self.assertEqual(1, len(self.json("GET", f"/Items/{PLAIN}")[1]["MediaSources"]))

    def test_trailers_extras_and_similar(self):
        status, trailers = self.json("GET", f"/Items/{RICH}/LocalTrailers?userId={m.USER_ID}")
        self.assertEqual((200, ["Trailer"]), (status, [t["Type"] for t in trailers]))
        extras = self.json("GET", f"/Items/{RICH}/SpecialFeatures")[1]
        self.assertEqual(["BehindTheScenes", "DeletedScene"], [e["ExtraType"] for e in extras])
        self.assertEqual([], self.json("GET", f"/Items/{PLAIN}/LocalTrailers")[1])
        similar = self.json("GET", f"/Items/{RICH}/Similar?userId={m.USER_ID}&limit=12")[1]
        self.assertTrue(0 < len(similar["Items"]) <= 12)
        self.assertNotIn(RICH, [i["Id"] for i in similar["Items"]])
        self.assertEqual(3, len(self.json("GET", f"/Items/{RICH}/Similar?limit=3")[1]["Items"]))
        self.assertEqual(404, self.json("GET", f"/Items/{'0' * 32}/Similar")[0])

    def test_a_trailer_plays_and_a_version_can_be_chosen(self):
        trailer = self.json("GET", f"/Items/{RICH}/LocalTrailers")[1][0]["Id"]
        self.assertEqual(200, self.json("POST", f"/Items/{trailer}/PlaybackInfo", {"EnableDirectPlay": True})[0])
        both = self.json("POST", f"/Items/{RICH}/PlaybackInfo", {"EnableDirectPlay": True})[1]["MediaSources"]
        self.assertEqual(2, len(both))
        second = both[1]["Id"]
        one = self.json("POST", f"/Items/{RICH}/PlaybackInfo", {"EnableDirectPlay": True, "MediaSourceId": second})[1]["MediaSources"]
        self.assertEqual([second], [s["Id"] for s in one])

    def test_person_page_and_works(self):
        person = self.json("GET", f"/Items/{MARIA}?userId={m.USER_ID}")[1]
        self.assertEqual(("Person", "Maria Hale"), (person["Type"], person["Name"]))
        self.assertTrue(person["ImageTags"]["Primary"])
        self.assertEqual(200, self.call("GET", f"/Items/{MARIA}/Images/Primary?maxWidth=200")[0])
        works = self.json("GET", f"/Items?personIds={MARIA}&recursive=true&includeItemTypes=Movie,Series&startIndex=0&limit=2")[1]
        self.assertEqual((3, 2), (works["TotalRecordCount"], len(works["Items"])))
        self.assertEqual(400, self.json("GET", "/Items?personIds=nope")[0])

    def test_people_are_not_plain_list_rows(self):
        everything = self.json("GET", "/Items?recursive=true")[1]["Items"]
        self.assertFalse({"Person", "Trailer"} & {i["Type"] for i in everything})


class Browsing(Served):
    def test_filter_options_and_lists(self):
        options = self.json("GET", f"/Items/Filters?parentId={m.FILMS_ID}&userId={m.USER_ID}")[1]
        self.assertEqual(["Adventure", "Comedy", "Drama", "Family", "Mystery"], options["Genres"])
        self.assertEqual(["classic", "feelgood"], options["Tags"])
        self.assertEqual(["12A", "15", "PG", "U"], options["OfficialRatings"])
        self.assertIn(1975, options["Years"])
        genres = self.json("GET", f"/Genres?parentId={m.FILMS_ID}&userId={m.USER_ID}&limit=3")[1]
        self.assertEqual((5, 3), (genres["TotalRecordCount"], len(genres["Items"])))
        studios = self.json("GET", f"/Studios?parentId={m.FILMS_ID}")[1]
        self.assertEqual(["Harbour Pictures", "Northlight Studios"], [s["Name"] for s in studios["Items"]])
        self.assertEqual(["Drama"], self.names("/Genres?searchTerm=dra&limit=10"))

    def film_list(self, extra):
        return self.json("GET", f"/Items?parentId={m.FILMS_ID}&recursive=true&includeItemTypes=Movie&limit=100&{extra}")[1]

    def test_each_filter_narrows_the_list(self):
        genre = self.json("GET", f"/Genres?parentId={m.FILMS_ID}")[1]["Items"][0]
        studio = self.json("GET", f"/Studios?parentId={m.FILMS_ID}")[1]["Items"][0]
        self.assertEqual(8, self.film_list(f"genreIds={genre['Id']}")["TotalRecordCount"])
        self.assertTrue(0 < self.film_list(f"studioIds={studio['Id']}")["TotalRecordCount"] < 40)
        self.assertEqual(8, self.film_list("tags=classic")["TotalRecordCount"])
        self.assertEqual(10, self.film_list("officialRatings=PG")["TotalRecordCount"])
        self.assertEqual({1975}, {i["ProductionYear"] for i in self.film_list("years=1975")["Items"]})
        self.assertEqual(400, self.json("GET", "/Items?years=abc")[0])
        self.assertEqual(400, self.json("GET", "/Items?genreIds=zzz")[0])

    def test_jump_by_letter(self):
        starts = self.film_list("nameStartsWith=The%20Golden")["Items"]
        self.assertEqual(5, len(starts))
        self.assertTrue(all(i["Name"].startswith("The Golden") for i in starts))
        photos = self.json("GET", f"/Items?parentId={g.gid('library-photos')}&nameLessThan=A")[1]
        self.assertEqual(["2019 beach"], [i["Name"] for i in photos["Items"]])
        self.assertEqual(0, self.film_list("nameLessThan=A")["TotalRecordCount"])

    def test_search_extras(self):
        self.assertEqual(["Maria Hale"], self.names("/Persons?searchTerm=maria&limit=10&userId=" + m.USER_ID))
        self.assertEqual(["Harbour Collection"], self.names("/Items?includeItemTypes=BoxSet&recursive=true&searchTerm=harbour&limit=10"))
        self.assertEqual(401, self.call("GET", "/Persons?searchTerm=a")[0])


class Subtitles(Served):
    def setUp(self):
        super().setUp()
        g.S["delay"] = 0.2

    def subtitle_count(self):
        row = self.json("GET", f"/Items/{PLAIN}?userId={m.USER_ID}")[1]
        return len([s for s in row["MediaSources"][0]["MediaStreams"] if s["Type"] == "Subtitle"])

    def test_search_gives_text_only_results(self):
        status, rows = self.json("GET", f"/Items/{PLAIN}/RemoteSearch/Subtitles/eng")
        self.assertEqual(200, status)
        self.assertEqual(3, len(rows))
        self.assertTrue(all(r["ThreeLetterISOLanguageName"] == "eng" for r in rows))
        self.assertTrue({"ProviderName", "Name", "Format", "CommunityRating", "DownloadCount", "HearingImpaired", "Forced"} <= set(rows[0]))
        self.assertNotIn("http", json.dumps(rows), "no address in a result")
        self.assertEqual(1, len(self.json("GET", f"/Items/{PLAIN}/RemoteSearch/Subtitles/eng?isPerfectMatch=true")[1]))
        self.assertEqual(400, self.json("GET", f"/Items/{PLAIN}/RemoteSearch/Subtitles/en")[0])
        self.assertEqual(404, self.json("GET", f"/Items/{'0' * 32}/RemoteSearch/Subtitles/eng")[0])

    def test_download_adds_a_subtitle_stream_after_a_short_delay(self):
        before = self.subtitle_count()
        sid = self.json("GET", f"/Items/{PLAIN}/RemoteSearch/Subtitles/fra")[1][0]["Id"]
        self.assertEqual(204, self.json("POST", f"/Items/{PLAIN}/RemoteSearch/Subtitles/{sid}")[0])
        self.assertEqual(before, self.subtitle_count(), "not there at once")
        time.sleep(0.4)
        self.assertEqual(before + 1, self.subtitle_count())
        row = self.json("GET", f"/Items/{PLAIN}")[1]
        self.assertEqual("fra", row["MediaStreams"][-1]["Language"])
        info = self.json("POST", f"/Items/{PLAIN}/PlaybackInfo", {"EnableDirectPlay": True})[1]["MediaSources"][0]
        self.assertEqual(before + 1, len([s for s in info["MediaStreams"] if s["Type"] == "Subtitle"]))
        self.assertEqual(200, self.call("GET", f"/Videos/{PLAIN}/{PLAIN}/Subtitles/4/Stream.srt", None, self.ben)[0])
        self.assertEqual(404, self.json("POST", f"/Items/{PLAIN}/RemoteSearch/Subtitles/neveroffered")[0])

    def test_without_subtitle_management_it_is_403(self):
        self.assertEqual(403, self.json("GET", f"/Items/{PLAIN}/RemoteSearch/Subtitles/eng", token=self.kid)[0])
        sid = self.json("GET", f"/Items/{PLAIN}/RemoteSearch/Subtitles/eng")[1][0]["Id"]
        self.assertEqual(403, self.json("POST", f"/Items/{PLAIN}/RemoteSearch/Subtitles/{sid}", token=self.kid)[0])
        before = self.subtitle_count()
        time.sleep(0.4)
        self.assertEqual(before, self.subtitle_count(), "nothing was added")
        self.assertEqual(200, self.json("GET", "/Users/Me", token=self.kid)[0])

    def test_profile_language_and_policies_are_on_users_me(self):
        me = self.json("GET", "/Users/Me")[1]
        self.assertEqual("eng", me["Configuration"]["SubtitleLanguagePreference"])
        self.assertTrue(me["Policy"]["EnableSubtitleManagement"] and me["Policy"]["EnableCollectionManagement"])
        kid = self.json("GET", "/Users/Me", token=self.kid)[1]
        self.assertFalse(kid["Policy"]["EnableSubtitleManagement"] or kid["Policy"]["EnableCollectionManagement"])


class PhotosAndHomeVideos(Served):
    def test_libraries_are_offered(self):
        views = self.json("GET", "/UserViews")[1]["Items"]
        self.assertEqual([("Films", "movies"), ("TV", "tvshows"), ("Photos", "photos"), ("Home videos", "homevideos")],
                         [(v["Name"], v["CollectionType"]) for v in views])

    def test_photos_list_direct_children_and_draw_at_screen_size(self):
        rows = self.json("GET", f"/Items?parentId={g.gid('library-photos')}&sortBy=SortName")[1]["Items"]
        self.assertEqual(["2019 beach", "Orchard", "Summer trip"], [r["Name"] for r in rows])
        self.assertEqual("PhotoAlbum", rows[2]["Type"])
        album = self.json("GET", f"/Items?parentId={rows[2]['Id']}")[1]["Items"]
        self.assertEqual({"Photo"}, {p["Type"] for p in album})
        self.assertEqual("Photo", album[0]["MediaType"])
        status, data = self.call("GET", f"/Items/{album[0]['Id']}/Images/Primary?tag={album[0]['ImageTags']['Primary']}&maxWidth=1920&maxHeight=1080&quality=90&format=Webp")
        self.assertEqual(200, status)
        self.assertEqual((1620, 1080), (int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")))

    def test_home_videos_play_and_folders_open(self):
        rows = self.json("GET", f"/Items?parentId={g.gid('library-home')}&sortBy=SortName")[1]["Items"]
        self.assertEqual(["Birthday party", "Family picnic", "Holiday clips", "Sports day"], [r["Name"] for r in rows])
        video = next(r for r in rows if r["Type"] == "Video")
        self.assertEqual("Video", video["MediaType"])
        self.assertEqual(200, self.json("POST", f"/Items/{video['Id']}/PlaybackInfo", {"EnableDirectPlay": True})[0])
        self.assertEqual(1, self.json("GET", f"/Items?parentId={g.gid('folder-clips')}")[1]["TotalRecordCount"])
        self.assertEqual(1, len(self.json("GET", f"/Items/{video['Id']}")[1]["MediaSources"]))


class State(Served):
    def test_events_are_kept_and_state_resets(self):
        self.json("POST", "/Playlists", {"Name": "Temp", "Ids": []})
        events = self.json("GET", "/mock/lists-events")[1]
        self.assertEqual("CreatePlaylist", events[-1]["Kind"])
        m.setup_manage()
        self.assertEqual([], g.EVENTS)
        self.assertNotIn("Temp", self.names(Playlists.LIST))


if __name__ == "__main__":
    unittest.main()
