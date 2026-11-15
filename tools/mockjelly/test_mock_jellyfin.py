"""Run from this folder: python -I test_mock_jellyfin.py   (or: python -m unittest test_mock_jellyfin)"""
import http.client
import email.utils
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

m.build_catalogue()
m.seed_state()

HALF_WATCHED = m.guid("episode-0-2-2")  # Harbour Lights S2 E2, seeded part way through


def names(q):
    return [i["Id"] for i in m.next_up(q)["Items"]]


class NextUp(unittest.TestCase):
    def test_a_part_watched_episode_is_next_up_by_default(self):
        self.assertIn(HALF_WATCHED, names({}))

    def test_enable_resumable_false_leaves_part_watched_episodes_out(self):
        self.assertNotIn(HALF_WATCHED, names({"enableresumable": ["false"]}))

    def test_enable_resumable_false_keeps_series_with_a_clean_next_episode(self):
        before = names({})
        later = m.guid("episode-1-1-2")
        # The Long Orbit: S1E1 watched, nothing started, so S1E2 is next up whether or not resumable ones are allowed.
        m.user_state(m.guid("episode-1-1-1")).update(played=True)
        try:
            self.assertIn(later, names({"enableresumable": ["false"]}))
        finally:
            m.user_state(m.guid("episode-1-1-1")).update(played=False)
        self.assertEqual(before, names({}))


# ------------------------------------------------- playback routes, over real HTTP on a free local port
FILM = m.guid("film-0")
EPISODE = m.guid("episode-0-1-1")
NOT_PLAYABLE = m.guid("series-0")
CLIP = m.MEDIA_DIR / "clip.mp4"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


class Served(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), m.Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        signed_in = cls.call("POST", "/Users/AuthenticateByName", {"Username": m.USER_NAME, "Pw": m.PASSWORD}, token=None)[2]
        cls.token = json.loads(signed_in)["AccessToken"]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    @classmethod
    def call(cls, method, path, body=None, token="default", headers=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(cls.base + path, data=data, method=method, headers=dict(headers or {}))
        if data is not None:
            req.add_header("Content-Type", "application/json")
        token = cls.token if token == "default" else token
        if token:
            req.add_header("Authorization", f'MediaBrowser Client="t", Token="{token}"')
        try:
            with OPENER.open(req) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def json(self, method, path, body=None):
        status, _, raw = self.call(method, path, body)
        return status, json.loads(raw) if raw else None


class PlaybackInfo(Served):
    def test_needs_a_token(self):
        self.assertEqual(401, self.call("POST", f"/Items/{FILM}/PlaybackInfo", {}, token=None)[0])

    def test_direct_play_source_has_the_documented_shape(self):
        status, body = self.json("POST", f"/Items/{FILM}/PlaybackInfo", {"EnableDirectPlay": True, "EnableDirectStream": True})
        self.assertEqual(200, status)
        self.assertRegex(body["PlaySessionId"], r"^[0-9a-f]{32}$")
        (src,) = body["MediaSources"]
        self.assertEqual((FILM, "mp4", 100000000), (src["Id"], src["Container"], src["RunTimeTicks"]))
        self.assertTrue(src["SupportsDirectPlay"] and src["SupportsDirectStream"] and src["HasSegments"])
        self.assertNotIn("TranscodingUrl", src)
        video, audio, srt, ass = src["MediaStreams"]
        self.assertEqual(("Video", "h264", "SDR"), (video["Type"], video["Codec"], video["VideoRangeType"]))
        self.assertEqual((1, "Audio", "aac", "eng"), (audio["Index"], audio["Type"], audio["Codec"], audio["Language"]))
        self.assertEqual((2, "subrip", "eng"), (srt["Index"], srt["Codec"], srt["Language"]))
        self.assertEqual((3, "ass", "jpn"), (ass["Index"], ass["Codec"], ass["Language"]))
        for sub in (srt, ass):
            self.assertTrue(sub["IsExternal"] and sub["IsTextSubtitleStream"] and sub["SupportsExternalStream"])

    def test_the_support_flags_follow_the_request(self):
        _, body = self.json("POST", f"/Items/{FILM}/PlaybackInfo", {"EnableDirectPlay": False, "EnableDirectStream": True})
        src = body["MediaSources"][0]
        self.assertEqual((False, True), (src["SupportsDirectPlay"], src["SupportsDirectStream"]))

    def test_the_flags_are_also_read_from_the_query_as_the_reference_lists_them(self):
        _, body = self.json("POST", f"/Items/{FILM}/PlaybackInfo?enableDirectPlay=false&enableDirectStream=false", {})
        src = body["MediaSources"][0]
        self.assertEqual((False, False), (src["SupportsDirectPlay"], src["SupportsDirectStream"]))
        self.assertIn("TranscodingUrl", src)

    def test_transcode_points_at_the_hls_master_and_carries_the_deliberate_api_key(self):
        _, body = self.json("POST", f"/Items/{EPISODE}/PlaybackInfo", {"EnableDirectPlay": False, "EnableDirectStream": False})
        src = body["MediaSources"][0]
        self.assertEqual(
            f"/videos/{EPISODE}/master.m3u8?MediaSourceId={EPISODE}&PlaySessionId={body['PlaySessionId']}"
            "&TranscodeReasons=ContainerBitrateExceedsLimit&api_key=MOCKLEAK", src["TranscodingUrl"])
        self.assertEqual("hls", src["TranscodingSubProtocol"])

    def test_play_session_ids_are_fresh(self):
        ids = {self.json("POST", f"/Items/{FILM}/PlaybackInfo", {})[1]["PlaySessionId"] for _ in range(3)}
        self.assertEqual(3, len(ids))

    def test_an_item_that_is_not_playable_is_404(self):
        self.assertEqual(404, self.call("POST", f"/Items/{NOT_PLAYABLE}/PlaybackInfo", {})[0])
        self.assertEqual(404, self.call("POST", f"/Items/{'f' * 32}/PlaybackInfo", {})[0])

    def test_refuse_mode_answers_not_allowed(self):
        m.CONFIG["refuse"] = True
        try:
            status, body = self.json("POST", f"/Items/{FILM}/PlaybackInfo", {})
        finally:
            m.CONFIG["refuse"] = False
        self.assertEqual((200, "NotAllowed"), (status, body["ErrorCode"]))
        self.assertEqual([], body.get("MediaSources", []))


class Stream(Served):
    URL = f"/Videos/{FILM}/stream?static=true&mediaSourceId={FILM}&tag=abc"

    def test_needs_a_token(self):
        self.assertEqual(401, self.call("GET", self.URL, token=None)[0])

    def test_whole_file_is_served_with_accept_ranges(self):
        status, headers, body = self.call("GET", self.URL)
        self.assertEqual(200, status)
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual(CLIP.read_bytes(), body)

    def test_a_range_gets_exactly_those_bytes_as_206(self):
        status, headers, body = self.call("GET", self.URL, headers={"Range": "bytes=100-199"})
        self.assertEqual(206, status)
        self.assertEqual(100, len(body))
        self.assertEqual(CLIP.read_bytes()[100:200], body)
        self.assertEqual(f"bytes 100-199/{CLIP.stat().st_size}", headers["Content-Range"])

    def test_open_ended_and_suffix_ranges(self):
        data = CLIP.read_bytes()
        _, _, tail = self.call("GET", self.URL, headers={"Range": "bytes=%d-" % (len(data) - 10)})
        self.assertEqual(data[-10:], tail)
        _, _, suffix = self.call("GET", self.URL, headers={"Range": "bytes=-25"})
        self.assertEqual(data[-25:], suffix)

    def test_a_range_past_the_end_is_416(self):
        self.assertEqual(416, self.call("GET", self.URL, headers={"Range": f"bytes={CLIP.stat().st_size + 5}-"})[0])

    def test_any_api_key_like_query_name_is_a_400(self):
        for name in ("api_key", "ApiKey", "apikey", "API_KEY"):
            self.assertEqual(400, self.call("GET", self.URL + f"&{name}=x")[0], name)

    def test_an_unknown_item_is_404(self):
        self.assertEqual(404, self.call("GET", f"/Videos/{'f' * 32}/stream?static=true")[0])


class HlsAndSubtitles(Served):
    def test_hls_files_need_a_token(self):
        self.assertEqual(401, self.call("GET", f"/videos/{FILM}/master.m3u8", token=None)[0])

    def test_master_playlist_points_at_the_media_playlist(self):
        status, headers, body = self.call("GET", f"/videos/{FILM}/master.m3u8?MediaSourceId={FILM}")
        self.assertEqual(200, status)
        self.assertIn("mpegurl", headers["Content-Type"])
        self.assertIn(b"hls/main.m3u8", body)

    def test_media_playlist_and_segments(self):
        _, _, playlist = self.call("GET", f"/videos/{FILM}/hls/main.m3u8")
        self.assertIn(b"seg4.ts", playlist)
        status, headers, seg = self.call("GET", f"/videos/{FILM}/hls/seg3.ts")
        self.assertEqual((200, "video/mp2t"), (status, headers["Content-Type"]))
        self.assertEqual((m.MEDIA_DIR / "hls" / "seg3.ts").read_bytes(), seg)
        self.assertEqual(404, self.call("GET", f"/videos/{FILM}/hls/seg9.ts")[0])
        self.assertEqual(404, self.call("GET", f"/videos/{FILM}/hls/..%2Fclip.mp4")[0])

    def test_subtitle_index_2_is_the_srt_and_3_the_ass(self):
        for index, fmt, fixture, ctype in ((2, "srt", "sample.srt", "text/plain"), (3, "ass", "styled.ass", None)):
            status, headers, body = self.call("GET", f"/Videos/{FILM}/{FILM}/Subtitles/{index}/Stream.{fmt}")
            self.assertEqual(200, status)
            self.assertEqual((m.MEDIA_DIR / fixture).read_bytes(), body)
            if ctype:
                self.assertTrue(headers["Content-Type"].startswith(ctype))

    def test_other_subtitle_indexes_are_404_and_a_token_is_needed(self):
        self.assertEqual(404, self.call("GET", f"/Videos/{FILM}/{FILM}/Subtitles/4/Stream.srt")[0])
        self.assertEqual(401, self.call("GET", f"/Videos/{FILM}/{FILM}/Subtitles/2/Stream.srt", token=None)[0])


class Reports(Served):
    def setUp(self):
        m.REPORTS.clear()
        self.item = m.guid("film-20")
        m.user_state(self.item).update(played=False, pos=0, last=None, count=0)

    def report(self, route, **fields):
        return self.call("POST", route, {"ItemId": self.item, "PlayMethod": "DirectPlay", "IsPaused": False, **fields})[0]

    def test_all_four_report_routes_answer_204_and_need_a_token(self):
        for route in ("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"):
            self.assertEqual(204, self.report(route, PositionTicks=1), route)
            self.assertEqual(401, self.call("POST", route, {}, token=None)[0], route)
        self.assertEqual(204, self.call("POST", "/Sessions/Playing/Ping?playSessionId=abc")[0])
        self.assertEqual(401, self.call("POST", "/Sessions/Playing/Ping?playSessionId=abc", token=None)[0])

    def test_the_recorded_fields_are_listed_at_mock_reports_without_tokens(self):
        self.report("/Sessions/Playing/Progress", PositionTicks=50, IsPaused=True)
        self.report("/Sessions/Playing/Stopped", PositionTicks=60, Failed=True)
        status, headers, raw = self.call("GET", "/mock/reports")
        self.assertEqual(200, status)
        rows = json.loads(raw)
        self.assertEqual([("progress", 50, True, None), ("stopped", 60, False, True)],
                         [(r["Kind"], r["PositionTicks"], r["IsPaused"], r.get("Failed")) for r in rows])
        self.assertEqual({self.item}, {r["ItemId"] for r in rows})
        self.assertEqual("DirectPlay", rows[0]["PlayMethod"])
        self.assertNotIn(self.token, raw.decode())
        self.assertNotIn("Token", raw.decode())

    def test_a_stop_at_95_percent_marks_the_item_played(self):
        self.report("/Sessions/Playing/Stopped", PositionTicks=int(m.CLIP_TICKS * 0.95))
        data = self.json("GET", f"/Items/{self.item}")[1]["UserData"]
        self.assertTrue(data["Played"])
        self.assertEqual(0, data["PlaybackPositionTicks"])

    def test_a_stop_at_30_percent_sets_the_resume_position_for_continue_watching(self):
        ticks = int(m.CLIP_TICKS * 0.30)
        self.report("/Sessions/Playing/Stopped", PositionTicks=ticks)
        data = self.json("GET", f"/Items/{self.item}")[1]["UserData"]
        self.assertEqual((False, ticks), (data["Played"], data["PlaybackPositionTicks"]))
        resume_ids = [i["Id"] for i in self.json("GET", "/UserItems/Resume")[1]["Items"]]
        self.assertIn(self.item, resume_ids)

    def test_a_failed_stop_does_not_move_the_position(self):
        self.report("/Sessions/Playing/Stopped", PositionTicks=int(m.CLIP_TICKS * 0.30), Failed=True)
        self.assertEqual(0, self.json("GET", f"/Items/{self.item}")[1]["UserData"]["PlaybackPositionTicks"])

    def test_a_bad_body_is_a_400(self):
        req = urllib.request.Request(self.base + "/Sessions/Playing/Stopped", data=b"not json", method="POST")
        req.add_header("Authorization", f'MediaBrowser Token="{self.token}"')
        with self.assertRaises(urllib.error.HTTPError) as caught:
            OPENER.open(req)
        self.assertEqual(400, caught.exception.code)

    def test_mock_reports_needs_a_token(self):
        self.assertEqual(401, self.call("GET", "/mock/reports", token=None)[0])


class MediaSegments(Served):
    def test_needs_a_token(self):
        self.assertEqual(401, self.call("GET", f"/MediaSegments/{FILM}", token=None)[0])

    def test_returns_an_intro_and_an_outro(self):
        status, body = self.json("GET", f"/MediaSegments/{FILM}")
        self.assertEqual(200, status)
        self.assertEqual((2, 0), (body["TotalRecordCount"], body["StartIndex"]))
        intro, outro = body["Items"]
        self.assertEqual(("Intro", 10000000, 30000000, FILM), (intro["Type"], intro["StartTicks"], intro["EndTicks"], intro["ItemId"]))
        self.assertEqual(("Outro", 80000000, 100000000), (outro["Type"], outro["StartTicks"], outro["EndTicks"]))
        self.assertNotEqual(intro["Id"], outro["Id"])

    def test_an_unknown_item_is_404(self):
        self.assertEqual(404, self.call("GET", f"/MediaSegments/{'f' * 32}")[0])


# ------------------------------------------------- downloads (Milestone 4): the default behaviour and one test per flag
DOWNLOAD = f"/Items/{FILM}/Download"
SIZE = CLIP.stat().st_size


class Downloads(Served):
    """Each test sets its flags through m.CONFIG and m.reset_downloads() puts every download flag back."""

    def setUp(self):
        m.reset_downloads()

    def tearDown(self):
        m.reset_downloads()

    def raw(self, path, headers=None, token="default"):
        """Like call, but a cut connection comes back as (status, headers, bytes so far, cut=True)."""
        req = urllib.request.Request(self.base + path, headers=dict(headers or {}))
        token = self.token if token == "default" else token
        if token:
            req.add_header("Authorization", f'MediaBrowser Client="t", Token="{token}"')
        try:
            with OPENER.open(req) as r:
                try:
                    return r.status, dict(r.headers), r.read(), False
                except http.client.IncompleteRead as cut:
                    return r.status, dict(r.headers), cut.partial, True
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read(), False

    def items(self, ids=(FILM,), fields="CanDownload,MediaSources"):
        status, body = self.json("GET", f"/Items?ids={','.join(ids)}&fields={fields}&userId={m.USER_ID}")
        self.assertEqual(200, status)
        return body["Items"]

    def playlist(self, episode=EPISODE):
        """PlaybackInfo with direct play off, then the master and media playlists: (session id, media playlist text, its URL folder)."""
        _, info = self.json("POST", f"/Items/{episode}/PlaybackInfo", {"EnableDirectPlay": False, "EnableDirectStream": False})
        url = info["MediaSources"][0]["TranscodingUrl"].replace("&api_key=MOCKLEAK", "")
        _, _, master = self.call("GET", url)
        media_path = [line for line in master.decode().splitlines() if line and not line.startswith("#")][0]
        folder = url.split("?")[0].rsplit("/", 1)[0] + "/" + media_path.split("?")[0].rsplit("/", 1)[0]
        _, _, media = self.call("GET", url.split("?")[0].rsplit("/", 1)[0] + "/" + media_path)
        return info["PlaySessionId"], media.decode(), folder

    @staticmethod
    def segments(media):
        return [line for line in media.splitlines() if line and not line.startswith("#")]

    # --- /Items with the download fields
    def test_items_report_can_download_and_the_media_source_size_and_etag(self):
        (item,) = self.items()
        self.assertIs(True, item["CanDownload"])
        (src,) = item["MediaSources"]
        self.assertEqual((FILM, "mp4", SIZE, "a1b2c3"), (src["Id"], src["Container"], src["Size"], src["ETag"]))

    def test_items_leave_the_download_fields_out_unless_asked(self):
        (item,) = self.items(fields="ChildCount")
        self.assertNotIn("CanDownload", item)
        self.assertNotIn("MediaSources", item)

    def test_a_series_cannot_be_downloaded(self):
        (item,) = self.items(ids=(NOT_PLAYABLE,))
        self.assertIs(False, item["CanDownload"])
        self.assertNotIn("MediaSources", item)

    # --- /Items/{id}/Download, default behaviour
    def test_download_needs_a_token(self):
        self.assertEqual(401, self.raw(DOWNLOAD, token=None)[0])

    def test_download_serves_the_whole_clip_with_validators(self):
        status, headers, body, _ = self.raw(DOWNLOAD)
        self.assertEqual((200, "video/mp4", "bytes", '"a1b2c3"'), (status, headers["Content-Type"], headers["Accept-Ranges"], headers["ETag"]))
        self.assertIn("GMT", headers["Last-Modified"])
        self.assertEqual(CLIP.read_bytes(), body)

    def test_a_range_resumes_with_an_exact_content_range(self):
        status, headers, body, _ = self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": '"a1b2c3"'})
        self.assertEqual(206, status)
        self.assertEqual(f"bytes 100-{SIZE - 1}/{SIZE}", headers["Content-Range"])
        self.assertEqual(SIZE - 100, len(body))
        self.assertEqual(CLIP.read_bytes()[100:], body)

    def test_a_last_modified_if_range_that_matches_also_resumes(self):
        modified = self.raw(DOWNLOAD)[1]["Last-Modified"]
        self.assertEqual(206, self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": modified})[0])

    def test_a_mismatched_if_range_gets_the_whole_file_as_200(self):
        status, headers, body, _ = self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": '"stale"'})
        self.assertEqual(200, status)
        self.assertNotIn("Content-Range", headers)
        self.assertEqual(SIZE, len(body))

    def test_a_download_with_an_api_key_is_a_400(self):
        for name in ("api_key", "ApiKey", "X-Emby-Token"):
            self.assertEqual(400, self.raw(f"{DOWNLOAD}?{name}={self.token}")[0], name)

    def test_an_unknown_item_is_404_and_one_that_cannot_be_downloaded_is_400(self):
        # Jellyfin's GetDownload throws ArgumentException (400) for a known item with no file to download (spike section 2).
        self.assertEqual(404, self.raw(f"/Items/{'f' * 32}/Download")[0])
        self.assertEqual(400, self.raw(f"/Items/{NOT_PLAYABLE}/Download")[0])

    # --- --no-etag: what real Jellyfin sends (Last-Modified only; ASP.NET PhysicalFile)
    def test_no_etag_sends_only_last_modified(self):
        m.CONFIG["no_etag"] = True
        status, headers, body, _ = self.raw(DOWNLOAD)
        self.assertEqual(200, status)
        self.assertNotIn("ETag", headers)
        self.assertIn("GMT", headers["Last-Modified"])
        self.assertEqual(CLIP.read_bytes(), body)

    def test_no_etag_ignores_an_entity_tag_if_range_and_serves_the_range(self):
        m.CONFIG["no_etag"] = True
        status, headers, body, _ = self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": '"anything"'})
        self.assertEqual(206, status)
        self.assertEqual(f"bytes 100-{SIZE - 1}/{SIZE}", headers["Content-Range"])

    def test_no_etag_date_if_range_resumes_only_when_not_modified_since(self):
        m.CONFIG["no_etag"] = True
        modified = self.raw(DOWNLOAD)[1]["Last-Modified"]
        later = email.utils.formatdate(email.utils.parsedate_to_datetime(modified).timestamp() + 60, usegmt=True)
        earlier = email.utils.formatdate(email.utils.parsedate_to_datetime(modified).timestamp() - 60, usegmt=True)
        self.assertEqual(206, self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": modified})[0])
        self.assertEqual(206, self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": later})[0])
        self.assertEqual(200, self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": earlier})[0])

    def test_mock_downloads_lists_each_media_request_without_secrets(self):
        self.raw(DOWNLOAD, {"Range": "bytes=10-", "If-Range": '"a1b2c3"'})
        self.raw(DOWNLOAD, token=None)
        status, _, raw = self.call("GET", "/mock/downloads")
        self.assertEqual(200, status)
        rows = json.loads(raw)
        self.assertEqual([(DOWNLOAD, "bytes=10-", '"a1b2c3"', "yes"), (DOWNLOAD, None, None, "no")],
                         [(r["Path"], r["Range"], r["IfRange"], r["Token"]) for r in rows])
        self.assertNotIn(self.token, raw.decode())
        self.assertEqual(401, self.call("GET", "/mock/downloads", token=None)[0])

    # --- PlaybackInfo and HLS for a converted download
    def test_playback_info_without_transcoding_has_no_transcoding_url(self):
        _, body = self.json("POST", f"/Items/{EPISODE}/PlaybackInfo", {"EnableDirectPlay": False, "EnableTranscoding": False})
        self.assertNotIn("TranscodingUrl", body["MediaSources"][0])

    def test_the_hls_playlist_is_vod_with_the_session_in_its_path_and_a_key_to_strip(self):
        session, media, folder = self.playlist()
        self.assertIn("#EXT-X-PLAYLIST-TYPE:VOD", media)
        self.assertIn("#EXT-X-ENDLIST", media)
        self.assertIn(session, folder)
        uris = self.segments(media)
        self.assertEqual(5, len(uris))
        for uri in uris:
            self.assertFalse(uri.startswith("/") or "://" in uri, uri)
            self.assertTrue(uri.endswith("api_key=MOCKLEAK"), uri)
        status, headers, seg = self.call("GET", f"{folder}/{uris[0].split('?')[0]}")
        self.assertEqual((200, "video/mp2t"), (status, headers["Content-Type"]))
        self.assertEqual((m.MEDIA_DIR / "hls" / "seg0.ts").read_bytes(), seg)
        self.assertEqual(400, self.call("GET", f"{folder}/{uris[0]}")[0])  # the key left in is refused
        log = json.loads(self.call("GET", "/mock/downloads")[2])
        self.assertEqual(session, log[-1]["PlaySessionId"])

    # --- one test per flag
    def test_drop_after_cuts_only_the_first_media_response(self):
        m.CONFIG["drop_after"] = 1000
        status, _, first, cut = self.raw(DOWNLOAD)
        self.assertEqual((200, True, 1000), (status, cut, len(first)))
        _, _, second, cut = self.raw(DOWNLOAD)
        self.assertEqual((False, SIZE), (cut, len(second)))

    def test_no_range_always_answers_200_with_no_accept_ranges(self):
        m.CONFIG["no_range"] = True
        status, headers, body, _ = self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": '"a1b2c3"'})
        self.assertEqual((200, SIZE), (status, len(body)))
        self.assertNotIn("Accept-Ranges", headers)
        self.assertNotIn("Content-Range", headers)

    def test_etag_changes_gives_new_validators_every_time_so_if_range_never_matches(self):
        m.CONFIG["etag_changes"] = True
        first = self.raw(DOWNLOAD)[1]
        status, second, _, _ = self.raw(DOWNLOAD, {"Range": "bytes=100-", "If-Range": first["ETag"]})
        self.assertEqual(200, status)
        self.assertNotEqual(first["ETag"], second["ETag"])
        self.assertNotEqual(first["Last-Modified"], second["Last-Modified"])
        self.assertRegex(second["ETag"], r'^"[A-Za-z0-9]{1,64}"$')

    def test_octet_stream_changes_the_content_type(self):
        m.CONFIG["octet_stream"] = True
        self.assertEqual("application/octet-stream", self.raw(DOWNLOAD)[1]["Content-Type"])

    def test_no_download_says_false_and_refuses(self):
        m.CONFIG["no_download"] = True
        self.assertIs(False, self.items()[0]["CanDownload"])
        self.assertEqual(403, self.raw(DOWNLOAD)[0])

    def test_candownload_missing_leaves_the_field_out_and_still_serves(self):
        m.CONFIG["candownload_missing"] = True
        self.assertNotIn("CanDownload", self.items()[0])
        self.assertEqual(200, self.raw(DOWNLOAD)[0])

    def test_download_forbidden_refuses_while_items_say_true(self):
        m.CONFIG["download_forbidden"] = True
        self.assertIs(True, self.items()[0]["CanDownload"])
        self.assertEqual(403, self.raw(DOWNLOAD)[0])

    def test_legacy_auth_only_refuses_header_auth_for_media_but_not_for_the_api(self):
        m.CONFIG["legacy_auth_only"] = True
        self.assertEqual(200, self.call("GET", f"/Items?ids={FILM}")[0])
        self.assertEqual(200, self.call("POST", f"/Items/{EPISODE}/PlaybackInfo", {})[0])
        self.assertEqual(401, self.raw(DOWNLOAD)[0])
        _, media, folder = self.playlist()
        segment = f"{folder}/{self.segments(media)[0].split('?')[0]}"
        self.assertEqual(401, self.raw(segment)[0])
        # what an older app does: the key in the query, which the mock then accepts (and does not 400)
        self.assertEqual(200, self.raw(f"{DOWNLOAD}?api_key={self.token}", token=None)[0])
        self.assertEqual(200, self.raw(f"{segment}?api_key={self.token}", token=None)[0])
        self.assertEqual(401, self.raw(f"{segment}?api_key=MOCKLEAK", token=None)[0])

    def test_hls_recut_gives_each_play_session_a_different_shape(self):
        m.CONFIG["hls_recut"] = True
        shapes = []
        for _ in range(2):
            _, media, _ = self.playlist()
            shapes.append((len(self.segments(media)), [line for line in media.splitlines() if line.startswith("#EXTINF")]))
        self.assertNotEqual(shapes[0], shapes[1])
        self.assertNotEqual(shapes[0][0], shapes[1][0])

    def test_a_stable_server_cuts_two_sessions_the_same(self):
        first, second = self.playlist(), self.playlist()
        self.assertEqual(self.segments(first[1]), self.segments(second[1]))

    def test_hls_idle_kill_404s_a_session_left_idle(self):
        m.CONFIG["hls_idle_kill"] = 2
        _, media, folder = self.playlist()
        uris = [u.split("?")[0] for u in self.segments(media)]
        self.assertEqual(200, self.call("GET", f"{folder}/{uris[0]}")[0])
        self.assertEqual(200, self.call("GET", f"{folder}/{uris[1]}")[0])
        time.sleep(2.2)
        self.assertEqual(404, self.call("GET", f"{folder}/{uris[2]}")[0])
        self.assertEqual(404, self.call("GET", f"{folder}/{uris[0]}")[0])  # it stays dead
        _, media, folder = self.playlist()  # a new session works
        self.assertEqual(200, self.call("GET", f"{folder}/{self.segments(media)[0].split('?')[0]}")[0])


if __name__ == "__main__":
    unittest.main()
