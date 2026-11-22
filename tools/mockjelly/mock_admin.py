"""Admin routes for the Jellyfin TEST STAND-IN (mock_jellyfin.py). NOT Jellyfin; never expose it.

Users, sessions, library scans, scheduled tasks, the activity log, devices, system info, restart and shut down, with
shapes from the 12.4.0 OpenAPI reference. Imports nothing from mock_jellyfin: its values come in through setup().
Prints nothing; message text is kept only for GET /mock/admin-events.
"""
import copy
import json
import re
from datetime import timedelta

CONFIG = {"ben_admin": True, "pending_restart": False, "admin_forbidden": False}
EVENTS = []  # admin actions received, newest last: {"Kind", "Target", "Query"} (+ "Header"/"Text" for a message)
TICKS = 10_000_000
PROVIDERS = {"AuthenticationProviderId": "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider",
             "PasswordResetProviderId": "Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider"}
PAIR_RE = re.compile(r'(\w+)="([^"]*)"')
COMMANDS = {"stop", "pause", "unpause", "nexttrack", "previoustrack", "seek", "rewind", "fastforward", "playpause"}
S = {}  # all admin state, rebuilt by setup()
ANNA_ID = "b" * 32  # a second grown-up (Milestone 5): fixed, not derived from a seed


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.0000000Z")


def norm(raw):
    return raw.replace("-", "").lower()


def policy(admin, downloads, all_folders, folders, disabled=False, deletion=None, managing=None):
    return {"IsAdministrator": admin, "IsHidden": False, "IsDisabled": disabled, "EnableContentDownloading": downloads,
            "EnableContentDeletion": admin if deletion is None else deletion, "EnableContentDeletionFromFolders": [],
            "EnableCollectionManagement": admin if managing is None else managing, "EnableSubtitleManagement": admin if managing is None else managing,
            "EnableAllFolders": all_folders, "EnabledFolders": folders, "EnableRemoteControlOfOtherUsers": admin,
            "EnableMediaPlayback": True, "MaxActiveSessions": 0, **PROVIDERS, "MockFutureField": 7}


def setup(server_id, ben_id, ben_name, guid, now, libraries, tokens=None, items=None):
    """(Re)build every piece of admin state. `tokens` is mock_jellyfin's live token set (revoked here); `items` its catalogue."""
    kid, guest = guid("user-kid"), guid("user-guest")
    EVENTS.clear()
    S.clear()
    S.update(now=now, guid=guid, server_id=server_id, ben=ben_id, kid=kid, tokens=tokens if tokens is not None else set(),
             items=items if items is not None else {}, pending=CONFIG["pending_restart"], issued={})
    S["users"] = {
        ben_id: {"Name": ben_name, "Password": None, "Seen": now, "Policy": policy(CONFIG["ben_admin"], True, True, [])},
        ANNA_ID: {"Name": "anna", "Password": "test-password-2", "Seen": now - timedelta(hours=1), "Policy": policy(False, True, True, [], deletion=True)},
        kid: {"Name": "kid", "Password": "kid-password", "Seen": now - timedelta(minutes=5), "Policy": policy(False, True, True, [])},
        guest: {"Name": "guest", "Password": None, "Seen": None, "Policy": policy(False, True, True, [], disabled=True)},
    }
    S["libraries"] = [{"Id": lid, "Name": name, "Type": kind, "Progress": None} for lid, name, kind in libraries]
    S["sessions"] = seeded_sessions(guid, now, ben_id, ben_name, kid)
    S["tasks"] = seeded_tasks(guid, now)
    S["devices"] = [
        device("tv-living-room-1", "Living room TV", kid, "kid", "Jellyfin Android TV", "0.17.0", now - timedelta(minutes=5)),
        device("web-ben-laptop", "Ben's laptop", ben_id, ben_name, "Jellyfin Web", "10.10.7", now - timedelta(hours=2)),
        device("tablet-kid", "Kid's tablet", kid, "kid", "Jellyfin Android", "2.6.2", now - timedelta(days=3)),
    ]
    S["activity"] = seeded_activity(now, ben_id, ben_name, kid)


def seeded_sessions(guid, now, ben_id, ben_name, kid):
    episode = {"Id": guid("episode-0-2-3"), "Name": "Episode 3", "Type": "Episode", "SeriesName": "Harbour Lights", "ParentIndexNumber": 2,
               "IndexNumber": 3, "RunTimeTicks": 45 * 60 * TICKS, "MediaType": "Video"}
    film = {"Id": guid("film-0"), "Name": "The Silent Harbour", "Type": "Movie", "ProductionYear": 1975, "RunTimeTicks": 80 * 60 * TICKS,
            "MediaType": "Video"}
    commands = ["DisplayMessage", "SetVolume", "Mute", "Unmute", "SendString"]
    return [
        {"Id": guid("session-living-room"), "UserId": kid, "UserName": "kid", "Client": "Jellyfin Android TV", "ApplicationVersion": "0.17.0",
         "DeviceName": "Living room TV", "DeviceId": "tv-living-room-1", "LastActivityDate": iso(now - timedelta(seconds=20)),
         "NowPlayingItem": episode, "PlayState": {"PositionTicks": (12 * 60 + 30) * TICKS, "IsPaused": False, "CanSeek": True, "PlayMethod": "Transcode"},
         "TranscodingInfo": {"VideoCodec": "h264", "AudioCodec": "aac", "IsVideoDirect": False, "IsAudioDirect": True,
                             "TranscodeReasons": ["VideoCodecNotSupported"]},
         "SupportsMediaControl": True, "SupportsRemoteControl": True, "SupportedCommands": commands, "RemoteEndPoint": "192.168.1.20", "IsActive": True},
        {"Id": guid("session-ben-laptop"), "UserId": ben_id, "UserName": ben_name, "Client": "Jellyfin Web", "ApplicationVersion": "10.10.7",
         "DeviceName": "Ben's laptop", "DeviceId": "web-ben-laptop", "LastActivityDate": iso(now - timedelta(minutes=1)),
         "NowPlayingItem": film, "PlayState": {"PositionTicks": 40 * 60 * TICKS, "IsPaused": True, "CanSeek": True, "PlayMethod": "DirectPlay"},
         "SupportsMediaControl": True, "SupportsRemoteControl": True, "SupportedCommands": commands, "RemoteEndPoint": "192.168.1.30", "IsActive": True},
        {"Id": guid("session-kid-tablet"), "UserId": kid, "UserName": "kid", "Client": "Jellyfin Android", "ApplicationVersion": "2.6.2",
         "DeviceName": "Kid's tablet", "DeviceId": "tablet-kid", "LastActivityDate": iso(now - timedelta(minutes=10)), "PlayState": {},
         "SupportsMediaControl": False, "SupportsRemoteControl": False, "SupportedCommands": [], "RemoteEndPoint": "192.168.1.21", "IsActive": True},
    ]


def task(guid, name, key, category, description, last=None, error=None, when=None, hidden=False):
    result = None
    if last:
        result = {"StartTimeUtc": iso(when - timedelta(minutes=4)), "EndTimeUtc": iso(when), "Status": last, "Name": name, "Key": key,
                  "Id": guid("task-" + key)}
        if error:
            result["ErrorMessage"] = error
    return {"Name": name, "State": "Idle", "Id": guid("task-" + key), "LastExecutionResult": result, "Triggers": [], "Description": description,
            "Category": category, "IsHidden": hidden, "Key": key, "Progress": None, "Seen": False}


def seeded_tasks(guid, now):
    return [
        task(guid, "Scan Media Library", "RefreshLibrary", "Library", "Scans your media library for new files and refreshes metadata.",
             "Completed", when=now - timedelta(days=1)),
        task(guid, "Clean Cache Directory", "DeleteCacheFiles", "Maintenance", "Deletes cache files no longer needed by the system.",
             "Completed", when=now - timedelta(hours=6)),
        task(guid, "Extract Chapter Images", "RefreshChapterImages", "Library", "Creates thumbnails for videos that have chapters.",
             "Failed", "ffmpeg exited with code 1", now - timedelta(hours=10)),
        task(guid, "Clean Activity Log", "CleanActivityLog", "Maintenance", "Deletes activity log entries older than the configured age."),
        task(guid, "Mock hidden task", "MockHiddenTask", "Maintenance", "A hidden task.", hidden=True),
    ]


def device(dev_id, name, user_id, user_name, app, version, seen):
    return {"Id": dev_id, "Name": name, "LastUserId": user_id, "LastUserName": user_name, "AppName": app, "AppVersion": version,
            "DateLastActivity": iso(seen), "AccessToken": "MOCKDEVICETOKEN-" + S["guid"]("device-" + dev_id + user_id)[:16], "Capabilities": {}}


def seeded_activity(now, ben_id, ben_name, kid):
    rows = []
    for n in range(1, 121):
        row = {"Id": n, "Date": iso(now - timedelta(hours=2 * (120 - n))), "ItemId": None,
               "Severity": "Error" if n % 25 == 0 else "Warning" if n % 10 == 0 else "Information"}
        if n % 3 == 0:
            row.update(Name="kid is playing Harbour Lights on Living room TV", Type="VideoPlayback", UserId=kid)
        elif n % 3 == 1:
            row.update(Name=f"{ben_name} has signed in", Type="SessionStarted", UserId=ben_id, ShortOverview="IP address: 192.168.1.20")
        else:
            row.update(Name="Scan Media Library completed", Type="TaskCompleted", Overview="Took 4 minutes.")
        rows.append(row)
    return rows


# ------------------------------------------------------------------ sign-in and tokens
def sign_in(creds):
    """Anna's or kid's sign-in ({"Id", "Name"}); None for anything else (ben is mock_jellyfin's own; guest is disabled)."""
    for uid, user in S.get("users", {}).items():
        if (isinstance(creds, dict) and user["Password"] and creds.get("Username") == user["Name"] and creds.get("Pw") == user["Password"]
                and not user["Policy"]["IsDisabled"]):
            return {"Id": uid, "Name": user["Name"]}
    return None


def issued(token, user_id, authorization):
    """Remember who a fresh token belongs to and the device it was issued to (from the sign-in's Authorization header)."""
    pairs = dict(PAIR_RE.findall(authorization or ""))
    S["issued"][token] = {"User": user_id, "DeviceId": pairs.get("DeviceId", "unknown-device"), "Device": pairs.get("Device", ""),
                          "Client": pairs.get("Client", ""), "Version": pairs.get("Version", ""), "Seen": S["now"]}


def token_user(token):
    info = S["issued"].get(token)
    return info["User"] if info else S["ben"]


def user_named(name):
    """The id of the user with that name, or None."""
    return next((uid for uid, user in S.get("users", {}).items() if user["Name"] == name), None)


def may_delete(uid):
    """Milestone 10: the user's own Policy.EnableContentDeletion, as Jellyfin's BaseItem.CanDelete(user) reads it (an
    administrator's policy has it on by default here, but being one does not imply it)."""
    user = S.get("users", {}).get(uid)
    return bool(user and user["Policy"].get("EnableContentDeletion"))


def may_download(uid):
    """The user's own Policy.EnableContentDownloading (a token for no known user downloads as ben)."""
    user = S.get("users", {}).get(uid)
    return bool(user and user["Policy"]["EnableContentDownloading"])


def live_tokens():
    return [(t, i) for t, i in S["issued"].items() if t in S["tokens"]]


def revoke_device(dev_id):
    for token, info in list(S["issued"].items()):
        if info["DeviceId"] == dev_id:
            S["tokens"].discard(token)
            del S["issued"][token]


# ------------------------------------------------------------------ answers
def user_dto(uid):
    user = S["users"][uid]
    out = {"Name": user["Name"], "ServerId": S["server_id"], "Id": uid, "HasPassword": True, "HasConfiguredPassword": True,
           "Policy": copy.deepcopy(user["Policy"]), "Configuration": {"PlayDefaultAudioTrack": True, "SubtitleLanguagePreference": {"anna": "fra"}.get(user["Name"], "eng" if user["Name"] == "ben" else "")}}
    if user["Seen"]:
        out["LastActivityDate"] = out["LastLoginDate"] = iso(user["Seen"])
    return out


def all_sessions():
    rows = list(S["sessions"])
    for token, info in live_tokens():
        user = S["users"][info["User"]]
        rows.append({"Id": S["guid"]("session-" + token), "UserId": info["User"], "UserName": user["Name"], "Client": info["Client"],
                     "ApplicationVersion": info["Version"], "DeviceName": info["Device"], "DeviceId": info["DeviceId"],
                     "LastActivityDate": iso(info["Seen"]), "PlayState": {}, "SupportsMediaControl": False, "SupportsRemoteControl": False,
                     "SupportedCommands": [], "RemoteEndPoint": "127.0.0.1", "IsActive": True})
    return rows


def all_devices():
    rows = list(S["devices"])
    for _, info in live_tokens():
        rows.append(device(info["DeviceId"], info["Device"], info["User"], S["users"][info["User"]]["Name"], info["Client"], info["Version"], info["Seen"]))
    return rows


def folders():
    out = []
    for lib in S["libraries"]:
        if lib["Progress"] is not None:
            lib["Progress"] = lib["Progress"] + 25 if lib["Progress"] < 75 else None
        out.append({"Name": lib["Name"], "Locations": [f"/srv/media/{lib['Name'].lower()}"], "CollectionType": lib["Type"], "ItemId": lib["Id"],
                    "LibraryOptions": {}, "RefreshStatus": "Idle" if lib["Progress"] is None else "Active", "RefreshProgress": lib["Progress"]})
    return out


def finish(t, status):
    t.update(State="Idle", Progress=None, Seen=False)
    now = S["now"]
    t["LastExecutionResult"] = {"StartTimeUtc": iso(now), "EndTimeUtc": iso(now), "Status": status, "Name": t["Name"], "Key": t["Key"], "Id": t["Id"]}


def tasks(q):
    out = []
    for t in S["tasks"]:
        if t["State"] == "Running":
            t["Progress"] += 25
            if t["Progress"] >= 100:
                finish(t, "Completed")
        elif t["State"] == "Cancelling":
            if t["Seen"]:
                finish(t, "Cancelled")
            t["Seen"] = True
        if t["IsHidden"] and q.get("ishidden", [""])[0].lower() == "false":
            continue
        row = {k: v for k, v in t.items() if k not in ("Progress", "Seen")}
        if t["Progress"] is not None:
            row["CurrentProgressPercentage"] = float(t["Progress"])
        out.append(row)
    return out


def start_task(t):
    if t["State"] == "Idle":
        t.update(State="Running", Progress=0)


def activity(q):
    newest = sorted(S["activity"], key=lambda e: e["Id"], reverse=True)
    start, limit = (int(v) if v.isdigit() else d for v, d in ((q.get(n, [""])[0], d) for n, d in (("startindex", 0), ("limit", len(newest)))))
    return {"Items": newest[start:start + limit], "TotalRecordCount": len(newest), "StartIndex": start}


def info():
    return {"ServerName": "Test Jellyfin", "Version": "12.4.0", "ProductName": "Mock Jellyfin (test stand-in)", "Id": S["server_id"],
            "HasPendingRestart": S["pending"], "IsShuttingDown": False, "StartupWizardCompleted": True, "OperatingSystem": "Linux",
            "LocalAddress": "http://192.168.1.10:8096", "LogPath": "/var/log/jellyfin", "ProgramDataPath": "/var/lib/jellyfin",
            "CachePath": "/var/cache/jellyfin", "CanSelfRestart": True}


def record(kind, target, q, **extra):
    EVENTS.append({"Kind": kind, "Target": target, "Query": {k: v[0] for k, v in q.items()}, **extra})


def body_of(raw):
    try:
        body = json.loads(raw or b"{}")
    except ValueError:
        return None
    return body if isinstance(body, dict) else None


# ------------------------------------------------------------------ routes
def handle(handler, method, path, q, raw, token):
    """Answer an admin route (True), or leave the request to mock_jellyfin (False). The token is already one it issued."""
    if not S:
        return False
    uid = token_user(token)
    user = S["users"][uid]
    if user["Policy"]["IsDisabled"]:
        return handler.reply(401)
    if token in S["issued"]:
        S["issued"][token]["Seen"] = S["now"]
    segs = path.strip("/").split("/")
    low = [x.lower() for x in segs]
    admin = user["Policy"]["IsAdministrator"]
    elevated = admin and not CONFIG["admin_forbidden"]

    def need_elevation():
        return None if elevated else handler.reply(403)

    if low[:1] == ["mock"] and len(low) == 2:
        if method == "GET" and low[1] == "admin-events":
            return handler.reply(200, list(EVENTS))
        if method == "POST" and low[1] in ("demote", "promote"):
            if uid != S["ben"]:
                return handler.reply(403)
            S["users"][S["ben"]]["Policy"]["IsAdministrator"] = low[1] == "promote"
            return handler.reply(204)
        return False
    if method == "POST" and low[:2] == ["mock", "users"] and len(low) == 4 and low[3] in ("no-download", "allow-download"):
        if uid != S["ben"]:
            return handler.reply(403)
        target = user_named(segs[2])
        if target is None:
            return handler.reply(404)
        S["users"][target]["Policy"]["EnableContentDownloading"] = low[3] == "allow-download"
        return handler.reply(204)
    if low[:1] == ["users"]:
        return users_route(handler, method, segs, low, uid, admin, elevated, raw, q)
    if low[:1] == ["sessions"]:
        return sessions_route(handler, method, segs, low, uid, admin, raw, q)
    if method == "GET" and low == ["library", "virtualfolders"]:
        return need_elevation() or handler.reply(200, folders())
    if method == "POST" and low == ["library", "refresh"]:
        if need_elevation():
            return True
        for lib in S["libraries"]:
            lib["Progress"] = 0
        for t in S["tasks"]:
            if t["Key"] == "RefreshLibrary":
                start_task(t)
        record("RefreshAll", None, q)
        return handler.reply(204)
    if method == "POST" and len(low) == 3 and low[0] == "items" and low[2] == "refresh":
        if need_elevation():
            return True
        target = norm(segs[1])
        lib = next((x for x in S["libraries"] if x["Id"] == target), None)
        if lib is None and target not in S["items"]:
            return handler.reply(404)
        if lib is not None:
            lib["Progress"] = 0
        record("RefreshItem", target, q)
        return handler.reply(204)
    if low[:1] == ["scheduledtasks"]:
        if method == "GET" and len(low) == 1:
            return need_elevation() or handler.reply(200, tasks(q))
        if method in ("POST", "DELETE") and len(low) == 3 and low[1] == "running":
            if need_elevation():
                return True
            t = next((x for x in S["tasks"] if x["Id"] == segs[2]), None)
            if t is None:
                return handler.reply(404)
            if method == "POST":
                start_task(t)
            elif t["State"] == "Running":
                t.update(State="Cancelling", Seen=False)
            record("StartTask" if method == "POST" else "StopTask", t["Key"], q)
            return handler.reply(204)
        return False
    if method == "GET" and low == ["system", "activitylog", "entries"]:
        return need_elevation() or handler.reply(200, activity(q))
    if low == ["devices"]:
        if need_elevation():
            return True
        if method == "GET":
            rows = all_devices()
            return handler.reply(200, {"Items": rows, "TotalRecordCount": len(rows), "StartIndex": 0})
        if method == "DELETE":
            dev_id = q.get("id", [""])[0]
            if not dev_id:
                return handler.reply(400)
            if dev_id not in {d["Id"] for d in all_devices()}:
                return handler.reply(404)
            S["devices"] = [d for d in S["devices"] if d["Id"] != dev_id]
            S["sessions"] = [s for s in S["sessions"] if s["DeviceId"] != dev_id]
            revoke_device(dev_id)
            record("RemoveDevice", dev_id, q)
            return handler.reply(204)
        return False
    if method == "GET" and low == ["system", "info"]:
        return handler.reply(200, info())
    if method == "POST" and low in (["system", "restart"], ["system", "shutdown"]):
        if need_elevation():
            return True
        if low[1] == "restart":
            S["pending"] = False
        record(low[1].capitalize(), None, q)
        return handler.reply(204)
    return False


def users_route(handler, method, segs, low, uid, admin, elevated, raw, q):
    if method == "GET" and low == ["users", "me"]:
        return handler.reply(200, user_dto(uid))
    if method == "GET" and low == ["users"]:
        return handler.reply(200, [user_dto(u) for u in S["users"] if admin or u == uid])
    target = norm(segs[1]) if len(segs) >= 2 else ""
    if method == "GET" and len(low) == 2:
        if target not in S["users"]:
            return handler.reply(404)
        return handler.reply(200, user_dto(target)) if admin or target == uid else handler.reply(403)
    if method == "POST" and len(low) == 3 and low[2] == "policy":
        if not elevated:
            return handler.reply(403)
        if target not in S["users"]:
            return handler.reply(404)
        body = body_of(raw)
        if (body is None or not all(isinstance(body.get(k), str) and body[k] for k in PROVIDERS) or body.get("MockFutureField") != 7
                or not isinstance(body.get("IsDisabled", False), bool)):
            return handler.reply(400)
        S["users"][target]["Policy"]["IsDisabled"] = body.get("IsDisabled", False)
        record("Policy", target, q, IsDisabled=body.get("IsDisabled", False))
        return handler.reply(204)
    return False


def sessions_route(handler, method, segs, low, uid, admin, raw, q):
    if method == "GET" and low == ["sessions"]:
        return handler.reply(200, [s for s in all_sessions() if admin or s["UserId"] == uid])
    is_message = method == "POST" and len(low) == 3 and low[2] == "message"
    is_command = method == "POST" and len(low) == 4 and low[2] == "playing" and low[1] != "playing"
    if not (is_message or is_command):
        return False
    session = next((s for s in all_sessions() if s["Id"] == segs[1]), None)
    if session is None:
        return handler.reply(404)
    if not admin and session["UserId"] != uid:
        return handler.reply(403)
    if is_message:
        body = body_of(raw)
        if body is None or not isinstance(body.get("Text"), str) or not body["Text"]:
            return handler.reply(400)
        record("Message", session["Id"], q, Header=body.get("Header"), Text=body["Text"], TimeoutMs=body.get("TimeoutMs"))
        return handler.reply(204)
    command = low[3]
    if command not in COMMANDS:
        return handler.reply(400)
    if command in ("pause", "unpause"):
        session["PlayState"]["IsPaused"] = command == "pause"
    elif command == "stop":
        session.pop("NowPlayingItem", None)
        session.pop("TranscodingInfo", None)
        session["PlayState"].pop("PositionTicks", None)
    record(segs[3][:1].upper() + segs[3][1:], session["Id"], q)
    return handler.reply(204)
