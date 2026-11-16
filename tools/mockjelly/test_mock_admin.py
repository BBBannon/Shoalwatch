"""Run from anywhere: python -I tools/mockjelly/test_mock_admin.py   (the admin routes of the test stand-in)"""
import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I does not add the script folder
import mock_jellyfin as m  # noqa: E402
import mock_admin as a  # noqa: E402

m.build_catalogue()
m.seed_state()

KID_ID, GUEST_ID = m.guid("user-kid"), m.guid("user-guest")
BEN_DEVICE, KID_DEVICE = "ben-phone-1", "kid-phone-1"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def auth(device, token=None):
    head = f'MediaBrowser Client="Shoalwatch", Device="Test phone", DeviceId="{device}", Version="0.6.0"'
    return head + (f', Token="{token}"' if token else "")


class AdminServed(unittest.TestCase):
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
        self.fresh()

    def fresh(self, **config):
        """Every test starts from the seeded admin state, with the flags given, and fresh sign-ins for ben and kid."""
        a.CONFIG.update({"ben_admin": True, "pending_restart": False, "admin_forbidden": False, **config})
        a.setup(m.SERVER_ID, m.USER_ID, m.USER_NAME, m.guid, m.NOW, [(m.FILMS_ID, "Films", "movies"), (m.TV_ID, "TV", "tvshows")],
                tokens=m.TOKENS, items=m.ITEMS)
        self.ben = self.sign_in(m.USER_NAME, m.PASSWORD, BEN_DEVICE)
        self.kid = self.sign_in("kid", "kid-password", KID_DEVICE)

    def sign_in(self, name, password, device):
        status, body = self.json("POST", "/Users/AuthenticateByName", {"Username": name, "Pw": password}, token=None, device=device)
        return body["AccessToken"] if status == 200 else None

    def call(self, method, path, body=None, token=None, device=BEN_DEVICE):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", auth(device, token))
        try:
            with OPENER.open(req) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def json(self, method, path, body=None, token=None, device=BEN_DEVICE):
        status, raw = self.call(method, path, body, token, device)
        return status, json.loads(raw) if raw else None

    def as_ben(self, method, path, body=None):
        return self.json(method, path, body, self.ben, BEN_DEVICE)

    def as_kid(self, method, path, body=None):
        return self.json(method, path, body, self.kid, KID_DEVICE)


ADMIN_ROUTES = [("GET", "/Users/Me"), ("GET", "/Users"), ("GET", f"/Users/{m.USER_ID}"), ("POST", f"/Users/{KID_ID}/Policy"),
                ("GET", "/Sessions"), ("POST", "/Sessions/x/Message"), ("POST", "/Sessions/x/Playing/Pause"),
                ("GET", "/Library/VirtualFolders"), ("POST", "/Library/Refresh"), ("POST", f"/Items/{m.FILMS_ID}/Refresh"),
                ("GET", "/ScheduledTasks"), ("POST", "/ScheduledTasks/Running/x"), ("DELETE", "/ScheduledTasks/Running/x"),
                ("GET", "/System/ActivityLog/Entries"), ("GET", "/Devices"), ("DELETE", "/Devices?id=x"), ("GET", "/System/Info"),
                ("POST", "/System/Restart"), ("POST", "/System/Shutdown"), ("POST", "/mock/demote"), ("POST", "/mock/promote"),
                ("GET", "/mock/admin-events")]


class Rights(AdminServed):
    def test_every_admin_route_needs_a_token(self):
        for method, path in ADMIN_ROUTES:
            with self.subTest(path=path, method=method):
                self.assertEqual(401, self.call(method, path, {} if method == "POST" else None)[0])

    def test_users_me_carries_the_policy(self):
        _, ben = self.as_ben("GET", "/Users/Me")
        _, kid = self.as_kid("GET", "/Users/Me")
        self.assertEqual((m.USER_ID, True), (ben["Id"], ben["Policy"]["IsAdministrator"]))
        self.assertEqual((KID_ID, "kid", False), (kid["Id"], kid["Name"], kid["Policy"]["IsAdministrator"]))
        for policy in (ben["Policy"], kid["Policy"]):
            self.assertEqual(7, policy["MockFutureField"])
            self.assertIn("DefaultAuthenticationProvider", policy["AuthenticationProviderId"])
            self.assertIn("DefaultPasswordResetProvider", policy["PasswordResetProviderId"])

    def test_kid_is_refused_the_elevated_routes(self):
        for method, path in [("GET", "/ScheduledTasks"), ("GET", "/Devices"), ("GET", "/System/ActivityLog/Entries"), ("POST", "/Library/Refresh"),
                             ("POST", "/System/Restart"), ("POST", f"/Users/{m.USER_ID}/Policy")]:
            with self.subTest(path=path):
                self.assertEqual(403, self.as_kid(method, path, {} if method == "POST" else None)[0])

    def test_users_list_is_everyone_for_an_admin_and_only_yourself_otherwise(self):
        self.assertEqual({m.USER_ID, a.ANNA_ID, KID_ID, GUEST_ID}, {u["Id"] for u in self.as_ben("GET", "/Users")[1]})
        self.assertEqual([KID_ID], [u["Id"] for u in self.as_kid("GET", "/Users")[1]])
        self.assertEqual(403, self.as_kid("GET", f"/Users/{m.USER_ID}")[0])
        self.assertEqual(200, self.as_ben("GET", f"/Users/{KID_ID}")[0])

    def test_guest_is_disabled_and_cannot_sign_in(self):
        self.assertIsNone(self.sign_in("guest", "", "guest-1"))
        guest = [u for u in self.as_ben("GET", "/Users")[1] if u["Id"] == GUEST_ID][0]
        self.assertTrue(guest["Policy"]["IsDisabled"])

    def test_ben_not_admin_flag_and_demote_both_say_false(self):
        self.as_ben("POST", "/mock/demote")
        self.assertFalse(self.as_ben("GET", "/Users/Me")[1]["Policy"]["IsAdministrator"])
        self.assertEqual(403, self.as_ben("GET", "/ScheduledTasks")[0])
        self.as_ben("POST", "/mock/promote")
        self.assertTrue(self.as_ben("GET", "/Users/Me")[1]["Policy"]["IsAdministrator"])
        self.assertEqual(403, self.as_kid("POST", "/mock/promote")[0])
        self.fresh(ben_admin=False)
        self.assertFalse(self.as_ben("GET", "/Users/Me")[1]["Policy"]["IsAdministrator"])

    def test_admin_forbidden_refuses_elevated_routes_while_me_still_says_admin(self):
        self.fresh(admin_forbidden=True)
        self.assertTrue(self.as_ben("GET", "/Users/Me")[1]["Policy"]["IsAdministrator"])
        self.assertEqual(403, self.as_ben("GET", "/ScheduledTasks")[0])
        self.assertEqual(403, self.as_ben("GET", "/Devices")[0])
        self.assertEqual(200, self.as_ben("GET", "/Sessions")[0])


class Policy(AdminServed):
    def policy(self, user_id):
        return self.as_ben("GET", f"/Users/{user_id}")[1]["Policy"]

    def test_a_policy_without_the_future_field_is_refused(self):
        body = self.policy(KID_ID)
        del body["MockFutureField"]
        body["IsDisabled"] = True
        self.assertEqual(400, self.as_ben("POST", f"/Users/{KID_ID}/Policy", body)[0])
        self.assertFalse(self.policy(KID_ID)["IsDisabled"])

    def test_the_policy_as_read_disables_kid_and_kid_is_then_signed_out(self):
        body = self.policy(KID_ID)
        body["IsDisabled"] = True
        body["IsAdministrator"] = True  # only IsDisabled is applied
        self.assertEqual(204, self.as_ben("POST", f"/Users/{KID_ID}/Policy", body)[0])
        self.assertTrue(self.policy(KID_ID)["IsDisabled"])
        self.assertFalse(self.policy(KID_ID)["IsAdministrator"])
        self.assertEqual(401, self.as_kid("GET", "/Users/Me")[0])
        self.assertIsNone(self.sign_in("kid", "kid-password", KID_DEVICE))
        body["IsDisabled"] = False
        self.assertEqual(204, self.as_ben("POST", f"/Users/{KID_ID}/Policy", body)[0])
        self.assertIsNotNone(self.sign_in("kid", "kid-password", KID_DEVICE))


class Sessions(AdminServed):
    def sessions(self, who="ben"):
        return (self.as_ben if who == "ben" else self.as_kid)("GET", "/Sessions?activeWithinSeconds=960")[1]

    def seeded(self, device):
        return [s for s in self.sessions() if s["DeviceId"] == device][0]

    def test_ben_sees_the_seeded_sessions_and_his_own(self):
        devices = [s["DeviceId"] for s in self.sessions()]
        for device in ("tv-living-room-1", "web-ben-laptop", "tablet-kid", BEN_DEVICE):
            self.assertIn(device, devices)
        mine = self.seeded(BEN_DEVICE)
        self.assertEqual(("Shoalwatch", "Test phone", "0.6.0", m.USER_ID), (mine["Client"], mine["DeviceName"], mine["ApplicationVersion"], mine["UserId"]))

    def test_kid_sees_only_kids_sessions(self):
        self.assertEqual({KID_ID}, {s["UserId"] for s in self.sessions("kid")})

    def test_the_living_room_session_is_transcoding_with_a_reason(self):
        tv = self.seeded("tv-living-room-1")
        self.assertEqual(("Transcode", 7_500_000_000), (tv["PlayState"]["PlayMethod"], tv["PlayState"]["PositionTicks"]))
        self.assertEqual(["VideoCodecNotSupported"], tv["TranscodingInfo"]["TranscodeReasons"])
        self.assertIn("DisplayMessage", tv["SupportedCommands"])
        self.assertEqual("192.168.1.20", tv["RemoteEndPoint"])

    def test_pause_and_stop(self):
        sid = self.seeded("tv-living-room-1")["Id"]
        self.assertEqual(204, self.as_ben("POST", f"/Sessions/{sid}/Playing/Pause")[0])
        self.assertTrue(self.seeded("tv-living-room-1")["PlayState"]["IsPaused"])
        self.assertEqual(204, self.as_ben("POST", f"/Sessions/{sid}/Playing/Stop")[0])
        tv = self.seeded("tv-living-room-1")
        self.assertNotIn("NowPlayingItem", tv)
        self.assertNotIn("TranscodingInfo", tv)
        self.assertNotIn("PositionTicks", tv["PlayState"])
        self.assertEqual(["Pause", "Stop"], [e["Kind"] for e in self.as_ben("GET", "/mock/admin-events")[1]])

    def test_messages(self):
        sid = self.seeded("tv-living-room-1")["Id"]
        self.assertEqual(400, self.as_ben("POST", f"/Sessions/{sid}/Message", {"Header": "Message from ben"})[0])
        self.assertEqual(404, self.as_ben("POST", "/Sessions/nosuchsession/Message", {"Text": "hi"})[0])
        laptop = self.seeded("web-ben-laptop")["Id"]
        self.assertEqual(403, self.as_kid("POST", f"/Sessions/{laptop}/Message", {"Text": "hi"})[0])
        self.assertEqual(403, self.as_kid("POST", f"/Sessions/{laptop}/Playing/Stop")[0])
        self.assertEqual(204, self.as_ben("POST", f"/Sessions/{sid}/Message", {"Header": "Message from ben", "Text": "Bed time", "TimeoutMs": 10000})[0])
        self.assertEqual("Bed time", self.as_ben("GET", "/mock/admin-events")[1][-1]["Text"])


class Libraries(AdminServed):
    def folders(self):
        return {f["ItemId"]: f for f in self.as_ben("GET", "/Library/VirtualFolders")[1]}

    def test_idle_libraries_with_paths_the_app_must_not_show(self):
        films = self.folders()[m.FILMS_ID]
        self.assertEqual(("Films", "movies", "Idle", None), (films["Name"], films["CollectionType"], films["RefreshStatus"], films["RefreshProgress"]))
        self.assertEqual(["/srv/media/films"], films["Locations"])
        self.assertEqual(403, self.as_kid("GET", "/Library/VirtualFolders")[0])

    def test_a_full_scan_progresses_on_each_get(self):
        self.assertEqual(204, self.as_ben("POST", "/Library/Refresh")[0])
        seen = [(f["RefreshStatus"], f["RefreshProgress"]) for f in (self.folders()[m.TV_ID] for _ in range(4))]
        self.assertEqual([("Active", 25), ("Active", 50), ("Active", 75), ("Idle", None)], seen)

    def test_a_full_scan_starts_the_scan_task(self):
        self.as_ben("POST", "/Library/Refresh")
        task = [t for t in self.as_ben("GET", "/ScheduledTasks")[1] if t["Key"] == "RefreshLibrary"][0]
        self.assertEqual(("Running", 25), (task["State"], task["CurrentProgressPercentage"]))

    def test_one_library_scan(self):
        q = "metadataRefreshMode=Default&imageRefreshMode=Default&replaceAllMetadata=false&replaceAllImages=false&regenerateTrickplay=false"
        self.assertEqual(204, self.as_ben("POST", f"/Items/{m.FILMS_ID}/Refresh?{q}")[0])
        folders = self.folders()
        self.assertEqual(("Active", 25), (folders[m.FILMS_ID]["RefreshStatus"], folders[m.FILMS_ID]["RefreshProgress"]))
        self.assertEqual("Idle", folders[m.TV_ID]["RefreshStatus"])
        self.assertEqual("Default", self.as_ben("GET", "/mock/admin-events")[1][-1]["Query"]["metadatarefreshmode"])
        self.assertEqual(204, self.as_ben("POST", f"/Items/{m.guid('film-0')}/Refresh")[0])
        self.assertEqual(404, self.as_ben("POST", f"/Items/{'f' * 32}/Refresh")[0])


class Tasks(AdminServed):
    def tasks(self, query=""):
        return {t["Key"]: t for t in self.as_ben("GET", "/ScheduledTasks" + query)[1]}

    def test_the_seeded_tasks(self):
        tasks = self.tasks()
        self.assertEqual("Completed", tasks["RefreshLibrary"]["LastExecutionResult"]["Status"])
        self.assertEqual(("Failed", "ffmpeg exited with code 1"),
                         (tasks["RefreshChapterImages"]["LastExecutionResult"]["Status"], tasks["RefreshChapterImages"]["LastExecutionResult"]["ErrorMessage"]))
        self.assertIsNone(tasks["CleanActivityLog"]["LastExecutionResult"])
        self.assertIn("MockHiddenTask", tasks)
        self.assertNotIn("MockHiddenTask", self.tasks("?isHidden=false"))

    def test_start_then_stop(self):
        tid = self.tasks()["DeleteCacheFiles"]["Id"]
        self.assertEqual(204, self.as_ben("POST", f"/ScheduledTasks/Running/{tid}")[0])
        running = self.tasks()["DeleteCacheFiles"]
        self.assertEqual(("Running", 25), (running["State"], running["CurrentProgressPercentage"]))
        self.assertEqual(204, self.as_ben("DELETE", f"/ScheduledTasks/Running/{tid}")[0])
        self.assertEqual("Cancelling", self.tasks()["DeleteCacheFiles"]["State"])
        after = self.tasks()["DeleteCacheFiles"]
        self.assertEqual(("Idle", "Cancelled"), (after["State"], after["LastExecutionResult"]["Status"]))
        self.assertEqual(404, self.as_ben("POST", "/ScheduledTasks/Running/nosuchtask")[0])

    def test_a_running_task_completes(self):
        tid = self.tasks()["CleanActivityLog"]["Id"]
        self.as_ben("POST", f"/ScheduledTasks/Running/{tid}")
        states = [(t["State"], t.get("CurrentProgressPercentage")) for t in (self.tasks()["CleanActivityLog"] for _ in range(4))]
        self.assertEqual([("Running", 25), ("Running", 50), ("Running", 75), ("Idle", None)], states)
        self.assertEqual("Completed", self.tasks()["CleanActivityLog"]["LastExecutionResult"]["Status"])


class Activity(AdminServed):
    def test_paging_newest_first(self):
        _, body = self.as_ben("GET", "/System/ActivityLog/Entries?startIndex=50&limit=50")
        self.assertEqual(list(range(70, 20, -1)), [e["Id"] for e in body["Items"]])
        self.assertEqual(120, body["TotalRecordCount"])

    def test_severities_and_overviews(self):
        entries = {e["Id"]: e for e in self.as_ben("GET", "/System/ActivityLog/Entries")[1]["Items"]}
        self.assertEqual(120, len(entries))
        self.assertEqual(("Warning", "Error", "Error", "Information"), tuple(entries[i]["Severity"] for i in (10, 25, 50, 7)))
        self.assertTrue(any(e.get("ShortOverview") == "IP address: 192.168.1.20" for e in entries.values()))
        self.assertTrue(any(e["Name"] == "kid is playing Harbour Lights on Living room TV" for e in entries.values()))


class Devices(AdminServed):
    def devices(self):
        return self.as_ben("GET", "/Devices")[1]["Items"]

    def test_seeded_devices_and_one_per_token_all_carrying_a_token(self):
        ids = [d["Id"] for d in self.devices()]
        for device in ("tv-living-room-1", "web-ben-laptop", "tablet-kid", BEN_DEVICE, KID_DEVICE):
            self.assertIn(device, ids)
        self.assertTrue(all(d["AccessToken"].startswith("MOCKDEVICETOKEN-") for d in self.devices()))

    def test_delete_needs_an_id_and_a_known_one(self):
        self.assertEqual(400, self.as_ben("DELETE", "/Devices")[0])
        self.assertEqual(404, self.as_ben("DELETE", "/Devices?id=nosuchdevice")[0])
        self.assertEqual(204, self.as_ben("DELETE", "/Devices?id=tablet-kid")[0])
        self.assertNotIn("tablet-kid", [d["Id"] for d in self.devices()])

    def test_deleting_kids_device_signs_out_only_kid(self):
        self.assertEqual(204, self.as_ben("DELETE", f"/Devices?id={KID_DEVICE}")[0])
        self.assertEqual(401, self.as_kid("GET", "/Users/Me")[0])
        self.assertEqual(200, self.as_ben("GET", "/Users/Me")[0])

    def test_deleting_bens_own_device_signs_ben_out(self):
        self.assertEqual(204, self.as_ben("DELETE", f"/Devices?id={BEN_DEVICE}")[0])
        self.assertEqual(401, self.as_ben("GET", "/Users/Me")[0])


class System(AdminServed):
    def test_info_and_restart(self):
        self.fresh(pending_restart=True)
        _, info = self.as_ben("GET", "/System/Info")
        self.assertEqual(("Test Jellyfin", "12.4.0", True, False), (info["ServerName"], info["Version"], info["HasPendingRestart"], info["IsShuttingDown"]))
        self.assertEqual("http://192.168.1.10:8096", info["LocalAddress"])
        self.assertIn("LogPath", info)
        self.assertEqual(204, self.as_ben("POST", "/System/Restart")[0])
        self.assertFalse(self.as_ben("GET", "/System/Info")[1]["HasPendingRestart"])
        self.assertEqual(204, self.as_ben("POST", "/System/Shutdown")[0])
        self.assertEqual(["Restart", "Shutdown"], [e["Kind"] for e in self.as_ben("GET", "/mock/admin-events")[1]])


if __name__ == "__main__":
    unittest.main()
