"""Run from anywhere: python -I tools/mockjelly/test_mock_manage.py   (the library management routes of the test stand-in)"""
import base64
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
import mock_manage as g  # noqa: E402

m.build_catalogue()
m.seed_state()

FILM = m.guid("film-4")  # has a poster and a backdrop
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 32 + b"\xff\xd9"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


class ManageServed(unittest.TestCase):
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
        m.CONFIG["quick_connect"] = False
        self.saved = {k: dict(v) for k, v in m.ITEMS.items()}
        self.ben = self.sign_in(m.USER_NAME, m.PASSWORD)
        self.kid = self.sign_in("kid", "kid-password")
        self.anna = self.sign_in("anna", "test-password-2")

    def tearDown(self):
        for k, v in self.saved.items():  # the catalogue is shared with the other test files: put it back
            m.ITEMS[k].clear()
            m.ITEMS[k].update(v)

    def request(self, method, path, body=None, token=None, raw=None, ctype="application/json"):
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data, method=method)
        head = 'MediaBrowser Client="Shoalwatch", Device="Test", DeviceId="dev-manage", Version="1.2.0"'
        if token:
            head += f', Token="{token}"'
        req.add_header("Authorization", head)
        if data is not None:
            req.add_header("Content-Type", ctype)
        try:
            with OPENER.open(req, timeout=5) as r:
                return r.status, r.read(), r.headers.get("Content-Type")
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers.get("Content-Type")

    def json(self, method, path, body=None, token=None):
        status, data, _ = self.request(method, path, body, token)
        return status, (json.loads(data) if data else None)

    def sign_in(self, name, password):
        status, body = self.json("POST", "/Users/AuthenticateByName", {"Username": name, "Pw": password})
        return body["AccessToken"] if status == 200 else None

    def item(self, token=None):
        return self.json("GET", f"/Items/{FILM}", token=token or self.ben)[1]

    # ---- identify
    def test_remote_search_answers_matches_with_provider_ids(self):
        status, body = self.json("POST", "/Items/RemoteSearch/Movie", {"SearchInfo": {"Name": "Harbour", "Year": 1999}, "ItemId": FILM}, self.ben)
        self.assertEqual(200, status)
        self.assertEqual(3, len(body))
        self.assertEqual({"Tmdb": "1000", "Imdb": "tt001000"}, body[0]["ProviderIds"])
        self.assertTrue(body[0]["ImageUrl"].startswith("https://image.tmdb.org/"))
        self.assertEqual(400, self.json("POST", "/Items/RemoteSearch/Series", {"ItemId": FILM}, self.ben)[0])

    def test_apply_changes_the_name_and_needs_an_administrator(self):
        result = {"Name": "Harbour Lights Returns", "ProductionYear": 2003, "ProviderIds": {"Tmdb": "1002"}, "SearchProviderName": "TheMovieDb"}
        self.assertEqual(403, self.json("POST", f"/Items/RemoteSearch/Apply/{FILM}?replaceAllImages=true", result, self.kid)[0])
        self.assertEqual(204, self.json("POST", f"/Items/RemoteSearch/Apply/{FILM}?replaceAllImages=true", result, self.ben)[0])
        self.assertEqual("Harbour Lights Returns", self.item()["Name"])
        self.assertEqual(2003, self.item()["ProductionYear"])
        event = g.EVENTS[-1]
        self.assertEqual(("Apply", FILM, "true"), (event["Kind"], event["Target"], event["Query"]["replaceallimages"]))

    # ---- pictures
    def test_images_list_has_paths_like_jellyfin(self):
        status, body = self.json("GET", f"/Items/{FILM}/Images", token=self.ben)
        self.assertEqual(200, status)
        self.assertEqual(["Primary", "Backdrop"], [r["ImageType"] for r in body])
        self.assertTrue(all("Path" in r for r in body))

    def test_remote_images_point_at_third_party_hosts(self):
        status, body = self.json("GET", f"/Items/{FILM}/RemoteImages?type=Backdrop&includeAllLanguages=true", token=self.ben)
        self.assertEqual(200, status)
        self.assertEqual(9, len(body["Images"]))
        self.assertTrue(all(i["Type"] == "Backdrop" and i["Url"].startswith("https://image.tmdb.org/") for i in body["Images"]))
        self.assertEqual(3, len(self.json("GET", f"/Items/{FILM}/RemoteImages?type=Primary", token=self.ben)[1]["Images"]))

    def test_downloading_a_remote_image_changes_the_tag_and_the_bytes(self):
        before = self.item()["ImageTags"]["Primary"]
        url = "https://image.tmdb.org/t/p/original/x.jpg"
        self.assertEqual(204, self.request("POST", f"/Items/{FILM}/RemoteImages/Download?type=Primary&imageUrl={urllib.parse.quote(url, safe='')}", token=self.ben)[0])
        after = self.item()["ImageTags"]["Primary"]
        self.assertNotEqual(before, after)
        status, data, ctype = self.request("GET", f"/Items/{FILM}/Images/Primary")
        self.assertEqual((200, "image/png"), (status, ctype))
        self.assertTrue(data.startswith(b"\x89PNG"))

    def test_upload_takes_base64_jpeg_only(self):
        body = base64.b64encode(JPEG)
        self.assertEqual(403, self.request("POST", f"/Items/{FILM}/Images/Logo", token=self.kid, raw=body, ctype="image/jpeg")[0])
        self.assertEqual(400, self.request("POST", f"/Items/{FILM}/Images/Logo", token=self.ben, raw=JPEG, ctype="image/jpeg")[0], "raw bytes are not base64")
        self.assertEqual(400, self.request("POST", f"/Items/{FILM}/Images/Logo", token=self.ben, raw=base64.b64encode(b"GIF89a"), ctype="image/jpeg")[0])
        self.assertEqual(204, self.request("POST", f"/Items/{FILM}/Images/Logo", token=self.ben, raw=body, ctype="image/jpeg")[0])
        self.assertIn("Logo", self.item()["ImageTags"])
        status, data, ctype = self.request("GET", f"/Items/{FILM}/Images/Logo")
        self.assertEqual((200, "image/jpeg", JPEG), (status, ctype, data))

    def test_removing_a_picture(self):
        self.assertEqual(404, self.request("DELETE", f"/Items/{FILM}/Images/Backdrop/3", token=self.ben)[0])
        self.assertEqual(204, self.request("DELETE", f"/Items/{FILM}/Images/Backdrop/0", token=self.ben)[0])
        self.assertEqual([], self.item()["BackdropImageTags"])
        self.assertEqual(404, self.request("GET", f"/Items/{FILM}/Images/Backdrop")[0])
        self.assertIn("Primary", self.item()["ImageTags"], "the poster stays")

    # ---- details
    def test_metadata_editor_lists_ratings(self):
        status, body = self.json("GET", f"/Items/{FILM}/MetadataEditor", token=self.ben)
        self.assertEqual(200, status)
        self.assertIn("PG", [r["Name"] for r in body["ParentalRatingOptions"]])
        self.assertEqual(403, self.json("GET", f"/Items/{FILM}/MetadataEditor", token=self.kid)[0])

    def test_saving_needs_the_whole_item(self):
        self.assertEqual(400, self.json("POST", f"/Items/{FILM}", {"Name": "Only a name"}, self.ben)[0])
        whole = self.item()
        whole.update({"Name": "New Name", "Overview": "New.", "Genres": ["Drama", "Mystery"], "Tags": ["Mine"], "Studios": [{"Name": "S"}],
                      "LockedFields": ["Name"], "LockData": True, "ProductionYear": 2001, "OfficialRating": None})
        self.assertEqual(204, self.json("POST", f"/Items/{FILM}", whole, self.ben)[0])
        after = self.item()
        self.assertEqual(("New Name", "New.", ["Drama", "Mystery"], ["Name"], True, 2001), (after["Name"], after["Overview"], after["Genres"], after["LockedFields"], after["LockData"], after["ProductionYear"]))
        self.assertNotIn("OfficialRating", after)

    # ---- delete
    def test_delete_needs_the_right_and_then_the_item_is_gone(self):
        self.assertEqual(401, self.request("DELETE", f"/Items/{FILM}", token=self.kid)[0], "Jellyfin answers 401 when CanDelete is false")
        self.assertEqual(200, self.json("GET", "/Users/Me", token=self.kid)[0], "and the kid's sign-in is still good")
        self.assertEqual(204, self.request("DELETE", f"/Items/{FILM}", token=self.anna)[0], "anna may delete, though no administrator")
        self.assertEqual(404, self.request("GET", f"/Items/{FILM}", token=self.ben)[0])
        self.assertEqual(404, self.request("GET", f"/Items/{FILM}/Images/Primary")[0])
        ids = [i["Id"] for i in self.json("GET", f"/Items?parentId={m.FILMS_ID}&limit=100", token=self.ben)[1]["Items"]]
        self.assertNotIn(FILM, ids)

    def test_an_item_says_whether_this_user_may_delete_it(self):
        self.assertTrue(self.json("GET", f"/Items/{FILM}?userId={m.USER_ID}", token=self.ben)[1]["CanDelete"])
        self.assertTrue(self.json("GET", f"/Items/{FILM}", token=self.anna)[1]["CanDelete"])
        self.assertFalse(self.json("GET", f"/Items/{FILM}", token=self.kid)[1]["CanDelete"])

    def test_an_administrator_without_the_deletion_policy_is_refused_with_401(self):
        a.S["users"][m.USER_ID]["Policy"]["EnableContentDeletion"] = False
        self.assertFalse(self.json("GET", f"/Items/{FILM}", token=self.ben)[1]["CanDelete"])
        self.assertEqual(401, self.request("DELETE", f"/Items/{FILM}", token=self.ben)[0])
        self.assertEqual(200, self.json("GET", f"/Items/{FILM}", token=self.ben)[0], "not deleted")

    def test_users_me_says_who_may_delete(self):
        self.assertTrue(self.json("GET", "/Users/Me", token=self.anna)[1]["Policy"]["EnableContentDeletion"])
        self.assertFalse(self.json("GET", "/Users/Me", token=self.kid)[1]["Policy"]["EnableContentDeletion"])

    # ---- Quick Connect approval
    def test_authorize_is_refused_while_quick_connect_is_off(self):
        self.assertEqual(401, self.request("POST", "/QuickConnect/Authorize?code=123456", token=self.ben)[0])

    def test_authorize_approves_a_waiting_code_once(self):
        m.CONFIG["quick_connect"] = True
        status, started = self.json("POST", "/mock/quickconnect-code")
        self.assertEqual(200, status)
        self.assertEqual(404, self.request("POST", "/QuickConnect/Authorize?code=000000x", token=self.ben)[0])
        status, data, _ = self.request("POST", f"/QuickConnect/Authorize?code={started['Code']}", token=self.ben)
        self.assertEqual((200, b"true"), (status, data))
        status, polled = self.json("GET", f"/QuickConnect/Connect?secret={started['Secret']}")
        self.assertTrue(polled["Authenticated"])
        self.assertEqual("<redacted>", g.EVENTS[-1]["Query"]["code"], "the code is never kept")


if __name__ == "__main__":
    unittest.main()
