"""A TEST STAND-IN for Jellyfin, for emulator walkthroughs of Shoalwatch. It is NOT a Jellyfin implementation.

Standard library only. Serves the endpoints the app uses (shapes taken from the official Jellyfin 12.4.0 OpenAPI
reference in docs/reference) from a small in-memory catalogue. Plain http, no TLS, no real security: never expose it.

    python -I mock_jellyfin.py [port] [--latency-ms N] [--fail-rate P] [--host H] [--refuse] [download flags, see the README]

Each request logs one line: method, path (no query), auth yes/no, token yes/no, status. Never the token or password.
Admin routes, extra users and the --ben-not-admin, --pending-restart and --admin-forbidden flags live in mock_admin.py.
Watched state is per user: a request's user is its token's user (ben, anna or kid); playstate flags are in the README.
"""
import argparse
import email.utils
import hashlib
import json
import random
import re
import secrets
import threading
import time
import struct
import sys
import zlib
from pathlib import Path
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python -I does not add the script folder
import mock_admin  # noqa: E402

SERVER_ID = "0123456789abcdef0123456789abcdef"
USER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
USER_NAME, PASSWORD = "ben", "test-password"
TICKS = 10_000_000  # Jellyfin ticks per second
NOW = datetime.now(timezone.utc).replace(microsecond=0)
MEDIA_DIR = Path(__file__).resolve().parent / "media"
REPORTS = []  # playback reports received, newest last (read back at /mock/reports)
TOKEN_RE = re.compile(r'Token="([^"]*)"')


def guid(seed):
    return hashlib.md5(seed.encode()).hexdigest()


ANNA_ID, KID_ID = mock_admin.ANNA_ID, guid("user-kid")
CURRENT = threading.local()  # the user of the request this thread is answering; ben outside a request (the tests' direct calls)


def current_user():
    return getattr(CURRENT, "user", USER_ID)


def norm(raw):
    """An id as Jellyfin accepts it: dashes optional, any case."""
    return raw.replace("-", "").lower()


class BadRequest(Exception):
    pass


def intq(q, name, default=None, maximum=None):
    """A non-negative integer query value; anything else is a 400, as in Jellyfin."""
    raw = q.get(name, [""])[0]
    if raw == "":
        return default
    if not raw.isdigit():
        raise BadRequest(name)
    return min(int(raw), maximum) if maximum else int(raw)


def iso(dt):
    dt = dt.astimezone(timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond:06d}0Z"


DATE_RE = re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,7}))?(Z|[+-]\d{2}:\d{2})?")


def parse_date(text):
    """An ISO 8601 date-time as Jellyfin sends and accepts it (no zone means UTC); BadRequest otherwise."""
    found = DATE_RE.fullmatch(text) if isinstance(text, str) else None
    if not found:
        raise BadRequest("date")
    y, mo, d, h, mi, sec = (int(found[n]) for n in range(1, 7))
    zone = timezone.utc
    if found[8] and found[8] != "Z":
        sign = 1 if found[8][0] == "+" else -1
        zone = timezone(sign * timedelta(hours=int(found[8][1:3]), minutes=int(found[8][4:6])))
    try:
        return datetime(y, mo, d, h, mi, sec, int((found[7] or "0").ljust(6, "0")[:6]), zone).astimezone(timezone.utc)
    except ValueError:
        raise BadRequest("date")


# ---------------------------------------------------------------- catalogue
ITEMS, CHILDREN, ORDER = {}, {}, []
FILMS_ID, TV_ID = guid("library-films"), guid("library-tv")


def add(item):
    item.setdefault("IsFolder", False)
    ITEMS[item["Id"]] = item
    CHILDREN.setdefault(item.get("ParentId"), []).append(item["Id"])
    ORDER.append(item["Id"])
    item["Created"] = NOW - timedelta(days=len(ORDER))
    return item


def build_catalogue():
    if ORDER:  # both test files build it when one run loads them together; a second pass would list every item twice
        return
    add({"Id": FILMS_ID, "Type": "CollectionFolder", "Name": "Films", "IsFolder": True, "Collection": "movies", "Aspect": 1.5})
    add({"Id": TV_ID, "Type": "CollectionFolder", "Name": "TV", "IsFolder": True, "Collection": "tvshows", "Aspect": 1.5})
    adjectives = ["Silent", "Golden", "Hidden", "Last", "Broken", "Northern", "Paper", "Midnight"]
    nouns = ["Harbour", "Orchard", "Signal", "Lantern", "Compass"]
    ratings, genres = ["U", "PG", "12A", "15"], ["Drama", "Comedy", "Adventure", "Mystery", "Family"]
    for n in range(40):
        name = f"The {adjectives[n % 8]} {nouns[n // 8]}"
        add({"Id": guid(f"film-{n}"), "Type": "Movie", "Name": name, "ParentId": FILMS_ID, "Year": 1975 + (n * 7) % 50,
             "Runtime": (80 + (n * 13) % 90) * 60 * TICKS, "Rating": round(5 + (n * 37 % 40) / 10, 1), "Official": ratings[n % 4],
             "Genre": genres[n % 5], "Overview": f"{name} is a made-up film for testing. Its plot is entry {n + 1} of 40 in the stand-in catalogue.",
             "Aspect": 0.667, "NoImage": n in (1, 14, 27), "Backdrop": n == 4})
    for s, title in enumerate(["Harbour Lights", "The Long Orbit", "Night Shift"]):
        series = add({"Id": guid(f"series-{s}"), "Type": "Series", "Name": title, "ParentId": TV_ID, "IsFolder": True, "Year": 2015 + s * 3,
                      "Official": "PG", "Rating": 7.5 + s / 4, "Overview": f"{title} is a made-up series for testing.", "Aspect": 0.667, "NoImage": s == 2})
        for sn in (1, 2):
            season = add({"Id": guid(f"season-{s}-{sn}"), "Type": "Season", "Name": f"Season {sn}", "ParentId": series["Id"], "IsFolder": True,
                          "Index": sn, "SeriesId": series["Id"], "SeriesName": title, "Aspect": 0.667})
            for ep in range(1, 7):
                add({"Id": guid(f"episode-{s}-{sn}-{ep}"), "Type": "Episode", "Name": f"Episode {ep}", "ParentId": season["Id"], "Index": ep,
                     "ParentIndex": sn, "SeriesId": series["Id"], "SeriesName": title, "SeasonId": season["Id"], "SeasonName": season["Name"],
                     "Runtime": 45 * 60 * TICKS, "Overview": f"Episode {ep} of season {sn} of {title}.", "Aspect": 1.778, "Year": 2015 + s * 3 + sn})
    HIDDEN[KID_ID] = {guid("series-2"), *descendants(guid("series-2"))}  # kid never sees Night Shift
    mock_admin.setup(SERVER_ID, USER_ID, USER_NAME, guid, NOW, [(FILMS_ID, "Films", "movies"), (TV_ID, "TV", "tvshows")], tokens=TOKENS, items=ITEMS)


HIDDEN = {}  # user id -> the item ids that user is not allowed to see (404 by id, left out of every list)


def visible(item_id, uid=None):
    return item_id not in HIDDEN.get(uid or current_user(), ())


# ----------------------------------------------------------------- playback
PLAYED_AT = 0.9  # a stop past this share of the runtime marks the item played
CLIP_TICKS = 10 * TICKS  # the committed clip is ten seconds long
SUBTITLES = {2: ("sample.srt", "text/plain; charset=utf-8"), 3: ("styled.ass", "text/x-ssa; charset=utf-8")}
ID_RE = re.compile(r"[0-9a-fA-F-]{32,36}")
RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")
SUBTITLE_FILE_RE = re.compile(r"stream\.\w+")
HLS_SEGMENT_RE = re.compile(r"hls/(seg\d+\.ts)")


def playable(item):
    return item["Type"] in ("Movie", "Episode")


def flag(body, q, name):
    """EnableDirectPlay and friends: the 12.4.0 reference has them in the body (PlaybackInfoDto) and also lists them as query values."""
    if isinstance(body.get(name), bool):
        return body[name]
    return q.get(name.lower(), ["true"])[0].lower() != "false"


def media_source(item_id, direct_play, direct_stream, play_session_id, transcoding=True):
    streams = [
        {"Index": 0, "Type": "Video", "Codec": "h264", "Profile": "Constrained Baseline", "Width": 320, "Height": 180, "BitDepth": 8, "VideoRange": "SDR",
         "VideoRangeType": "SDR", "AverageFrameRate": 24.0, "RealFrameRate": 24.0, "IsDefault": True, "IsExternal": False, "DisplayTitle": "180p H264 SDR"},
        {"Index": 1, "Type": "Audio", "Codec": "aac", "Language": "eng", "Channels": 1, "SampleRate": 48000, "IsDefault": True, "IsExternal": False,
         "DisplayTitle": "English - AAC - Mono - Default"},
        {"Index": 2, "Type": "Subtitle", "Codec": "subrip", "Language": "eng", "IsExternal": True, "IsTextSubtitleStream": True,
         "SupportsExternalStream": True, "DeliveryMethod": "External", "IsDefault": False, "DisplayTitle": "English - SubRip - External"},
        {"Index": 3, "Type": "Subtitle", "Codec": "ass", "Language": "jpn", "IsExternal": True, "IsTextSubtitleStream": True,
         "SupportsExternalStream": True, "DeliveryMethod": "External", "IsDefault": False, "DisplayTitle": "Japanese - ASS - External"},
    ]
    source = {"Protocol": "File", "Id": item_id, "Type": "Default", "Container": "mp4", "Name": ITEMS[item_id]["Name"], "RunTimeTicks": CLIP_TICKS,
              "Size": (MEDIA_DIR / "clip.mp4").stat().st_size, "Bitrate": 1_000_000, "IsRemote": False, "SupportsTranscoding": True,
              "SupportsDirectPlay": direct_play, "SupportsDirectStream": direct_stream, "MediaStreams": streams, "HasSegments": True,
              "DefaultAudioStreamIndex": 1}
    if not direct_play and transcoding:
        source["TranscodingUrl"] = (f"/videos/{item_id}/master.m3u8?MediaSourceId={item_id}&PlaySessionId={play_session_id}"
                                    "&TranscodeReasons=ContainerBitrateExceedsLimit&api_key=MOCKLEAK")  # api_key is deliberate: the app must strip it
        source["TranscodingSubProtocol"] = "hls"
        source["TranscodingContainer"] = "ts"
    return source


def json_body(raw):
    try:
        body = json.loads(raw or b"{}")
    except ValueError:
        raise BadRequest("body")
    if not isinstance(body, dict):
        raise BadRequest("body")
    return body


def record_report(kind, body):
    """Keep the report for /mock/reports; a real stop also moves the item's played flag or resume position."""
    row = {"Kind": kind, **{k: body.get(k) for k in ("ItemId", "PositionTicks", "IsPaused", "PlayMethod", "Failed", "PlaySessionId")}}
    with LOCK:
        REPORTS.append(row)
        item = ITEMS.get(norm(str(body.get("ItemId") or "")))
        ticks = body.get("PositionTicks")
        if kind == "stopped" and item and isinstance(ticks, int) and not body.get("Failed"):
            # Measured against the clip that is actually served (PlaybackInfo says CLIP_TICKS), not the catalogue runtime.
            if ticks > CLIP_TICKS * PLAYED_AT:
                mark(item["Id"], played=True, pos=0, count=lambda c: c + 1, stamp=True)
            else:
                mark(item["Id"], played=False, pos=max(ticks, 0), stamp=True)


def byte_range(header, size):
    """(start, end) inclusive for one 'bytes=' range; None when there is no usable header; 'bad' when it cannot be met."""
    found = RANGE_RE.fullmatch(header.strip()) if header else None
    if not found or not (found[1] or found[2]):
        return None
    if not found[1]:  # suffix: the last N bytes
        return (max(size - int(found[2]), 0), size - 1) if int(found[2]) else "bad"
    start = int(found[1])
    end = min(int(found[2]), size - 1) if found[2] else size - 1
    return "bad" if start >= size or end < start else (start, end)


STATE, LOCK = {}, threading.RLock()  # (user id, item id) -> that user's watched state for the item
BLANK = {"played": False, "pos": 0, "fav": False, "count": 0, "last": None, "rating": None, "likes": None}


def user_state(item_id, uid=None):
    """The user's state for the item, created when missing (for writing). The user is the request's unless named."""
    return STATE.setdefault((uid or current_user(), item_id), dict(BLANK))


def peek(item_id, uid=None):
    """The user's state for reading: never creates an entry, so /mock/userdata lists only items touched. Do not change it."""
    return STATE.get((uid or current_user(), item_id), BLANK)


def seed_state():
    film = lambda n: guid(f"film-{n}")
    for n, pct in ((3, .35), (11, .6), (19, .15)):
        s = user_state(film(n)); s["pos"] = int(ITEMS[film(n)]["Runtime"] * pct); s["last"] = NOW - timedelta(hours=n)
    for n in (5, 6, 7):
        s = user_state(film(n)); s.update(played=True, count=1, last=NOW - timedelta(days=n))
    for n in (2, 9, 30):
        user_state(film(n))["fav"] = True  # all of it ben's: anna and kid start with nothing watched
    for sn, eps in ((1, range(1, 7)), (2, range(1, 2))):  # Harbour Lights: season 1 and S2E1 watched
        for ep in eps:
            user_state(guid(f"episode-0-{sn}-{ep}")).update(played=True, count=1, last=NOW - timedelta(days=20 - ep - 7 * (2 - sn)))
    s = user_state(guid("episode-0-2-2")); s["pos"] = 18 * 60 * TICKS; s["last"] = NOW - timedelta(hours=2)  # S2E2 half watched


def when(item_id):
    """When the item was last played; never-played sorts as very old."""
    return peek(item_id)["last"] or NOW - timedelta(days=9999)


def episodes_of(item_id):
    item = ITEMS[item_id]
    if item["Type"] == "Episode":
        return [item_id]
    return [e for c in CHILDREN.get(item_id, []) for e in episodes_of(c)] if item["IsFolder"] else []


def descendants(item_id):
    return [d for c in CHILDREN.get(item_id, []) for d in [c] + descendants(c)]


def user_data(item):
    iid = item["Id"]
    st = peek(iid)
    if item["Type"] in ("Series", "Season"):
        eps = episodes_of(iid)
        unplayed = sum(1 for e in eps if not peek(e)["played"])
        out = {"Played": unplayed == 0, "UnplayedItemCount": unplayed, "PlayCount": 0, "PlaybackPositionTicks": 0}
    else:
        out = {"Played": st["played"], "PlayCount": st["count"], "PlaybackPositionTicks": st["pos"]}
        if st["pos"] and item.get("Runtime"):
            out["PlayedPercentage"] = round(100 * st["pos"] / item["Runtime"], 2)
    out.update({"IsFavorite": st["fav"], "Key": iid, "ItemId": iid})
    for field, key in (("Rating", "rating"), ("Likes", "likes")):
        if st[key] is not None:  # Jellyfin omits nulls
            out[field] = st[key]
    if st["last"]:
        out["LastPlayedDate"] = iso(st["last"])
    return out


def image_tag(item):
    return guid("image-" + item["Id"])[:16]


def dto(item):
    out = {"Name": item["Name"], "ServerId": SERVER_ID, "Id": item["Id"], "Etag": guid("etag-" + item["Id"])[:16], "DateCreated": iso(item["Created"]),
           "SortName": item["Name"].lower(), "Type": item["Type"], "IsFolder": item["IsFolder"], "UserData": user_data(item),
           "MediaType": "Video" if item["Type"] in ("Movie", "Episode") else "Unknown", "LocationType": "FileSystem",
           "ImageTags": {} if item.get("NoImage") else {"Primary": image_tag(item)},
           "BackdropImageTags": [image_tag(item)] if item.get("Backdrop") else [], "PrimaryImageAspectRatio": item["Aspect"]}
    for key, field in (("Runtime", "RunTimeTicks"), ("Year", "ProductionYear"), ("Rating", "CommunityRating"), ("Official", "OfficialRating"),
                       ("Overview", "Overview"), ("Index", "IndexNumber"), ("ParentIndex", "ParentIndexNumber"), ("ParentId", "ParentId"),
                       ("SeriesId", "SeriesId"), ("SeriesName", "SeriesName"), ("SeasonId", "SeasonId"), ("SeasonName", "SeasonName"), ("Collection", "CollectionType")):
        if key in item:
            out[field] = item[key]
    if "Genre" in item:
        out["Genres"] = [item["Genre"]]
    if item["Type"] == "Movie" and "Year" in item:
        out["PremiereDate"] = f"{item['Year']}-06-01T00:00:00.0000000Z"
    if item["IsFolder"]:
        out["ChildCount"] = len(CHILDREN.get(item["Id"], []))
        out["RecursiveItemCount"] = len(episodes_of(item["Id"])) or len([c for c in descendants(item["Id"]) if ITEMS[c]["Type"] == "Movie"])
    return out


def result(items, start=0, total=None):
    return {"Items": [dto(i) for i in items], "TotalRecordCount": len(items) if total is None else total, "StartIndex": start}


def page(items, q):
    start = intq(q, "startindex", 0)
    limit = intq(q, "limit")
    end = start + limit if limit is not None else None
    return result(items[start:end], start, len(items))


def csv(q, name):
    return [v for raw in q.get(name, []) for v in raw.split(",") if v]


def in_order(items, q):
    keys = csv(q, "sortby") or ["SortName"]
    orders = [o.lower() for o in csv(q, "sortorder")] or ["ascending"]  # one order per sortBy key; the last repeats
    key = {k.lower(): f for k, f in {"Name": lambda i: i["Name"].lower(), "SortName": lambda i: i["Name"].lower(), "DateCreated": lambda i: i["Created"],
           "PremiereDate": lambda i: i.get("Year", 0), "ProductionYear": lambda i: i.get("Year", 0), "CommunityRating": lambda i: i.get("Rating", 0),
           "Runtime": lambda i: i.get("Runtime", 0), "DatePlayed": lambda i: when(i["Id"]),
           "IndexNumber": lambda i: i.get("Index", 0), "ParentIndexNumber": lambda i: i.get("ParentIndex", 0)}.items()}
    ordered = list(items)
    for n in reversed(range(len(keys))):  # stable sorts, last key first
        ordered.sort(key=key.get(keys[n].lower(), key["sortname"]), reverse=orders[min(n, len(orders) - 1)] == "descending")
    return ordered


def list_items(q):
    parent = norm(q.get("parentid", [""])[0])
    recursive = q.get("recursive", ["false"])[0].lower() == "true"
    ids = {norm(i) for i in csv(q, "ids")}
    if parent:
        pool = descendants(parent) if recursive else CHILDREN.get(parent, [])
    else:  # like Jellyfin, ids name items wherever they are
        pool = [i for i in ORDER if ids or recursive or ITEMS[i]["Type"] == "CollectionFolder"]
    types, filters = {t.lower() for t in csv(q, "includeitemtypes")}, {f.lower() for f in csv(q, "filters")}
    term = q.get("searchterm", [""])[0].lower()
    out = []
    for iid in pool:
        item, st = ITEMS[iid], peek(iid)
        if not visible(iid) or ids and iid not in ids or types and item["Type"].lower() not in types or term and term not in item["Name"].lower():
            continue
        played = user_data(item)["Played"]
        if "isunplayed" in filters and played or "isplayed" in filters and not played or "isfavorite" in filters and not st["fav"]:
            continue
        out.append(item)
    listed = page(in_order(out, q), q)
    fields = {f.lower() for f in csv(q, "fields")}
    for row in listed["Items"]:
        download_fields(row, ITEMS[row["Id"]], fields)
    return listed


def resume_items(q):
    parent = norm(q.get("parentid", [""])[0])
    scope = set(descendants(parent)) if parent else None
    uid = current_user()
    rows = [ITEMS[i] for (who, i), st in list(STATE.items()) if who == uid and visible(i) and st["pos"] > 0 and not st["played"]
            and (scope is None or i in scope) and ITEMS[i]["Type"] in ("Movie", "Episode")]
    return page(sorted(rows, key=lambda i: when(i["Id"]), reverse=True), q)


def next_up(q):
    series_id, parent = norm(q.get("seriesid", [""])[0]), norm(q.get("parentid", [""])[0])
    resumable = q.get("enableresumable", ["true"])[0].lower() != "false"  # false: leave out episodes already part way through
    rows = []
    for sid in CHILDREN.get(TV_ID, []):
        if (series_id and sid != series_id) or (parent and parent not in (sid, TV_ID)) or not visible(sid):
            continue
        eps = sorted(episodes_of(sid), key=lambda e: (ITEMS[e]["ParentIndex"], ITEMS[e]["Index"]))
        played = [n for n, e in enumerate(eps) if peek(e)["played"]]
        if played and played[-1] + 1 < len(eps) and (resumable or peek(eps[played[-1] + 1])["pos"] == 0):
            rows.append((max(when(eps[n]) for n in played), ITEMS[eps[played[-1] + 1]]))
    return page([item for _, item in sorted(rows, key=lambda r: r[0], reverse=True)], q)


def latest(q):
    parent = norm(q.get("parentid", [""])[0])
    kinds = ("Movie", "Series")
    pool = [ITEMS[c] for c in (CHILDREN.get(parent, []) if parent else ORDER) if ITEMS[c]["Type"] in kinds and visible(c)]
    limit = intq(q, "limit", 20)
    return [dto(i) for i in sorted(pool, key=lambda i: i["Created"], reverse=True)[:limit]]


def server_now():
    """The time the server stamps itself, moved by --clock-skew."""
    return datetime.now(timezone.utc) + timedelta(seconds=CONFIG["clock_skew"])


def mark(item_id, **changes):
    """Change the request user's state for the item (each episode, for a series or season). A callable `count` maps the old
    count to the new; `stamp` sets the last played date to the server's own now."""
    with LOCK:
        for target in episodes_of(item_id) or [item_id]:
            st = user_state(target)
            for k, v in changes.items():
                if k != "stamp":
                    st[k] = v(st[k]) if callable(v) else v
            if changes.get("stamp"):
                st["last"] = server_now()
        return user_data(ITEMS[item_id])


USER_DATA_FIELDS = {"Played": ("played", bool), "IsFavorite": ("fav", bool), "PlaybackPositionTicks": ("pos", int), "PlayCount": ("count", int),
                    "Rating": ("rating", (int, float)), "Likes": ("likes", bool)}


def typed(value, kind):
    """bool is an int in Python; Jellyfin's JSON binding does not take one for the other, so neither does the mock."""
    return isinstance(value, kind) and (kind is bool or not isinstance(value, bool))


def update_user_data(item_id, body):
    """UpdateItemUserData: each non-null field applied as sent; LastPlayedDate as given (not now). 400 for a badly typed field."""
    changes = {}
    for field, (key, kind) in USER_DATA_FIELDS.items():
        if body.get(field) is not None:
            if not typed(body[field], kind):
                raise BadRequest(field)
            changes[key] = body[field]
    if body.get("LastPlayedDate") is not None:
        changes["last"] = parse_date(body["LastPlayedDate"])
    if CONFIG["userdata_ignores_dates"]:
        changes.pop("count", None)
        changes.pop("last", None)
    return mark(item_id, **changes)


# ---------------------------------------------------------------- playstate (Milestone 5)
# One flag per way a real server might take a sync (see the README); each user's state is their own.
PLAYSTATE_FLAGS = {"userdata_ignores_dates": False, "markplayed_counts": False, "lose_reply_once": False, "no_userdata_get": False,
                   "refuse_userdata": None, "clock_skew": 0}
PLAYSTATE = []  # every playstate request, oldest first (read back at /mock/playstate); never a token or a field value
PLAYSTATE_STATE = {"reply_lost": False}
STATE_FIELDS = {"played": bool, "count": int, "pos": int, "fav": bool}


def reset_playstate():
    """Every playstate flag off and the playstate log empty (the tests call it between cases)."""
    with LOCK:
        CONFIG.update(PLAYSTATE_FLAGS)
        PLAYSTATE.clear()
        PLAYSTATE_STATE["reply_lost"] = False


def playstate_route(method, segs):
    """'userdata', 'played' or 'favourite' for a playstate path (segments lower-cased), else None."""
    if len(segs) == 3 and segs[0] == "useritems" and segs[2] == "userdata" and method in ("GET", "POST"):
        return "userdata"
    if len(segs) == 2 and segs[0] in ("userplayeditems", "userfavoriteitems") and method in ("POST", "DELETE"):
        return "played" if segs[0] == "userplayeditems" else "favourite"
    return None


def record_playstate(method, path, q, raw):
    try:
        body = json.loads(raw) if raw else {}
    except ValueError:
        body = {}
    PLAYSTATE.append({"Method": method, "Path": path, "User": current_user(), "UserId": q.get("userid", [None])[0],
                      "DatePlayed": "dateplayed" in q, "Fields": sorted(body) if isinstance(body, dict) else []})


def loopback(address):
    return address in ("127.0.0.1", "::1", "::ffff:127.0.0.1")


def state_row(st):
    return {"played": st["played"], "count": st["count"], "pos": st["pos"], "fav": st["fav"], "last": iso(st["last"]) if st["last"] else None}


def mock_userdata(method, q, raw):
    """GET: one user's state for every item touched. POST: "another device changed it". (status, body)."""
    name = q.get("user", [""])[0]
    if not name:
        return 400, None
    uid = mock_admin.user_named(name)
    if uid is None:
        return 404, None
    if method == "GET":
        return 200, {iid: state_row(st) for (who, iid), st in STATE.items() if who == uid}
    iid = norm(q.get("item", [""])[0])
    if iid not in ITEMS:
        return 404, None
    body = json_body(raw)
    changes = {}
    for key, kind in STATE_FIELDS.items():
        if key in body:
            if not typed(body[key], kind):
                return 400, None
            changes[key] = body[key]
    if "last" in body:
        changes["last"] = None if body["last"] is None else parse_date(body["last"])
    user_state(iid, uid).update(changes)
    return 204, None


def png(item, width, height):
    r, g, b = (60 + int(c, 16) * 8 for c in guid("colour-" + item["Id"])[:3])
    row = b"\x00" + bytes((r, g, b)) * width
    raw = zlib.compress(row * height, 6)
    chunk = lambda tag, data: struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


# ---------------------------------------------------------------- downloads
# One flag per way a real server might behave (Milestone 4 Task 1; see the README and research/2026-11-downloads-spike.md).
DOWNLOAD_FLAGS = {"drop_after": None, "no_range": False, "etag_changes": False, "octet_stream": False, "no_download": False,
                  "candownload_missing": False, "download_forbidden": False, "legacy_auth_only": False, "hls_recut": False,
                  "hls_idle_kill": None, "no_etag": False}
ETAG = "a1b2c3"
API_KEY_NAMES = {"api_key", "apikey", "x-emby-token", "x-mediabrowser-token", "x-emby-authorization"}  # compared lower-cased
DOWNLOADS = []  # each media request, newest last (read back at /mock/downloads); never a token or a query value
SESSIONS = {}  # play session id -> {"item", "shape": [segment seconds], "last": monotonic time, "dead"}
SESSION_RE = re.compile(r"[0-9a-f]{32}")
SESSION_SEGMENT_RE = re.compile(r"seg(\d{1,4})\.ts")
MEDIA_STATE = {"drop_used": False, "validators": 0, "sessions": 0}


def reset_downloads():
    """Every download flag off and every download record gone (the tests call it between cases)."""
    with LOCK:
        CONFIG.update(DOWNLOAD_FLAGS)
        DOWNLOADS.clear()
        SESSIONS.clear()
        MEDIA_STATE.update(drop_used=False, validators=0, sessions=0)


def can_download(item):
    """What /Items says in CanDownload: None when --candownload-missing leaves the field out."""
    if CONFIG["candownload_missing"]:
        return None
    return playable(item) and not CONFIG["no_download"] and mock_admin.may_download(current_user())


def download_fields(row, item, fields):
    """Adds CanDownload and MediaSources to an /Items row, only when asked for in fields (as Jellyfin's DtoService does)."""
    if "candownload" in fields and can_download(item) is not None:
        row["CanDownload"] = can_download(item)
    if "mediasources" in fields and playable(item):
        source = media_source(item["Id"], True, True, None)
        source["ETag"] = ETAG
        row["MediaSources"] = [source]


def validators():
    """(ETag without quotes, Last-Modified) for this /Download response; --etag-changes makes a new pair every time."""
    if CONFIG["etag_changes"]:
        MEDIA_STATE["validators"] += 1
        n = MEDIA_STATE["validators"]
        return f"{ETAG}x{n}", email.utils.formatdate(NOW.timestamp() + n, usegmt=True)
    return ETAG, email.utils.formatdate(NOW.timestamp(), usegmt=True)


def if_range_matches(if_range, etag, modified):
    """As ASP.NET Core serves a file: with --no-etag an entity tag cannot be compared and is ignored (the range is served),
    and a date matches when the file was not modified after it; otherwise the ETag or the exact date must match."""
    if not CONFIG["no_etag"]:
        return if_range in (f'"{etag}"', modified)
    if if_range.startswith('"') or if_range.startswith("W/"):
        return True
    try:
        return email.utils.parsedate_to_datetime(modified) <= email.utils.parsedate_to_datetime(if_range)
    except (TypeError, ValueError):
        return False


def segment_shape():
    """Segment lengths for a new play session: five 2 s segments, or with --hls-recut a different count and length each time."""
    MEDIA_STATE["sessions"] += 1
    if not CONFIG["hls_recut"]:
        return [2.0] * 5
    count = 5 - (MEDIA_STATE["sessions"] - 1) % 3  # 5, 4, 3, 5, ...
    return [round(10.0 / count, 6)] * count


def new_session(session, item_id):
    SESSIONS[session] = {"item": item_id, "shape": segment_shape(), "last": time.monotonic(), "dead": False}


def session_playlist(session):
    shape = SESSIONS[session]["shape"]
    lines = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{int(max(shape) + 0.999)}", "#EXT-X-MEDIA-SEQUENCE:0",
             "#EXT-X-PLAYLIST-TYPE:VOD"]
    for n, seconds in enumerate(shape):
        lines += [f"#EXTINF:{seconds:.6f},", f"seg{n}.ts?api_key=MOCKLEAK"]  # relative, with a key the app must strip
    return ("\n".join(lines + ["#EXT-X-ENDLIST"]) + "\n").encode()


def media_kind(segs):
    """'download', 'stream', 'playlist' or 'segment' for a media path; None for anything else."""
    low = [x.lower() for x in segs]
    if len(segs) == 3 and low[0] == "items" and low[2] == "download":
        return "download"
    if len(segs) == 3 and low[0] == "videos" and low[2] == "stream":
        return "stream"
    if len(segs) > 2 and segs[0] == "videos":  # the mock's own lower-case HLS paths
        return "segment" if segs[-1].endswith(".ts") else "playlist"
    return None


def record_download(path, segs, q, token, headers):
    in_path = segs[3] if len(segs) > 4 and segs[2] == "hls" and SESSION_RE.fullmatch(segs[3]) else None  # /videos/{id}/hls/{session}/...
    session = in_path or (q.get("playsessionid") or [None])[0]
    DOWNLOADS.append({"Path": path, "Range": headers.get("Range"), "IfRange": headers.get("If-Range"), "PlaySessionId": session,
                      "Token": "yes" if token else "no", "ApiKey": "yes" if API_KEY_NAMES & set(q) else "no"})


# ------------------------------------------------------------------- server
QC = {}  # Quick Connect secret -> {"code", "polls", "used"}; only with --quick-connect
CONFIG = {"latency": 0.0, "fail_rate": 0.0, "refuse": False, "quick_connect": False, **DOWNLOAD_FLAGS, **PLAYSTATE_FLAGS}
TOKENS = set()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    status = 0

    def reply(self, code, body=None, headers=None, raw=None, content_type="application/json", media=False):
        data = raw if raw is not None else (b"" if body is None else json.dumps(body).encode())
        self.status = code
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if media and data and CONFIG["drop_after"] is not None and not MEDIA_STATE["drop_used"]:
            MEDIA_STATE["drop_used"] = True  # --drop-after: the first media body only, cut short, then the connection closes
            self.wfile.write(data[:CONFIG["drop_after"]])
            self.wfile.flush()
            self.close_connection = True
            return True
        self.wfile.write(data)
        return True

    def handle_one_request(self):
        self.status = 0
        super().handle_one_request()

    def route(self):
        parts = urlsplit(self.path)
        path = unquote(parts.path)
        q = {k.lower(): v for k, v in parse_qs(parts.query).items()}
        body = b""
        if self.headers.get("Content-Length"):
            body = self.rfile.read(int(self.headers["Content-Length"]))
        auth = self.headers.get("Authorization", "")
        match = TOKEN_RE.search(auth)
        token = match.group(1) if match else ""
        time.sleep(CONFIG["latency"])
        try:
            with LOCK:  # one lock for every read and write of the shared state
                self.dispatch(path, q, body, token)
        except BadRequest:
            self.reply(400)
        finally:
            print(f"{self.command} {path} auth={'yes' if auth else 'no'} token={'yes' if token else 'no'} status={self.status}", flush=True)

    def dispatch(self, path, q, raw, token):
        method = self.command
        low = path.lower()
        segs = path.strip("/").split("/")
        CURRENT.user = USER_ID
        if low in ("/mock/userdata", "/mock/playstate"):  # test-only, no token, this machine only
            if not loopback(self.client_address[0]):
                return self.reply(403)
            if low == "/mock/playstate":
                return self.reply(200, list(PLAYSTATE)) if method == "GET" else self.reply(404)
            return self.reply(*mock_userdata(method, q, raw)) if method in ("GET", "POST") else self.reply(404)
        if method == "GET" and len(segs) >= 4 and segs[0].lower() == "items" and segs[2].lower() == "images":
            # like Jellyfin, artwork needs no token
            return self.image(ITEMS[norm(segs[1])], segs[3], q) if norm(segs[1]) in ITEMS else self.reply(404)
        if method == "GET" and low == "/system/info/public":
            return self.reply(200, {"Id": SERVER_ID, "ServerName": "Test Jellyfin", "Version": "12.4.0", "ProductName": "Mock Jellyfin (test stand-in)"})
        if method == "GET" and low == "/quickconnect/enabled":
            return self.reply(200, CONFIG["quick_connect"])
        if CONFIG["quick_connect"] and method == "POST" and low == "/quickconnect/initiate":
            secret, code = secrets.token_hex(32), f"{secrets.randbelow(10**6):06d}"
            QC[secret] = {"code": code, "polls": 0, "used": False}
            return self.reply(200, {"Authenticated": False, "Secret": secret, "Code": code, "DeviceId": "x", "DeviceName": "x", "AppName": "x", "AppVersion": "1", "DateAdded": "2026-11-18T10:00:00.0000000Z"})
        if CONFIG["quick_connect"] and method == "GET" and low == "/quickconnect/connect":
            entry = QC.get(q.get("secret", [""])[0])
            if entry is None:
                return self.reply(404)
            entry["polls"] += 1  # approved on the second poll, as if someone tapped Authorize in Jellyfin web
            return self.reply(200, {"Authenticated": entry["polls"] >= 2, "Secret": q["secret"][0], "Code": entry["code"], "DateAdded": "2026-11-18T10:00:00.0000000Z"})
        if CONFIG["quick_connect"] and method == "POST" and low == "/users/authenticatewithquickconnect":
            try:
                body = json.loads(raw or b"{}")
            except ValueError:
                body = {}
            entry = QC.get(body.get("Secret", "") if isinstance(body, dict) else "")
            if entry is None or entry["used"] or entry["polls"] < 2:
                return self.reply(400)
            entry["used"] = True  # a secret signs in once, like Jellyfin
            issued = "mock-" + secrets.token_hex(16)
            TOKENS.add(issued)
            mock_admin.issued(issued, USER_ID, self.headers.get("Authorization", ""))
            user = {"Name": USER_NAME, "ServerId": SERVER_ID, "Id": USER_ID, "HasPassword": True}
            return self.reply(200, {"User": user, "SessionInfo": {}, "AccessToken": issued, "ServerId": SERVER_ID})
        if method == "POST" and low == "/users/authenticatebyname":
            try:
                creds = json.loads(raw or b"{}")
            except ValueError:
                creds = {}
            other = mock_admin.sign_in(creds)
            if not other and not (isinstance(creds, dict) and creds.get("Username") == USER_NAME and creds.get("Pw") == PASSWORD):
                return self.reply(401)
            issued = "mock-" + secrets.token_hex(16)
            TOKENS.add(issued)
            mock_admin.issued(issued, other["Id"] if other else USER_ID, self.headers.get("Authorization", ""))
            user = {"Name": other["Name"] if other else USER_NAME, "ServerId": SERVER_ID, "Id": other["Id"] if other else USER_ID, "HasPassword": True}
            return self.reply(200, {"User": user, "SessionInfo": {}, "AccessToken": issued, "ServerId": SERVER_ID})
        media = media_kind(segs) if method == "GET" else None
        if media:
            record_download(path, segs, q, token, self.headers)
            keyed = sorted(API_KEY_NAMES & set(q))
            if keyed and not CONFIG["legacy_auth_only"]:
                return self.reply(400)  # the app must never put a key in a URL
            if CONFIG["legacy_auth_only"] and (keyed or media in ("download", "segment")):
                token = q[keyed[0]][0] if keyed else ""  # the legacy setting: the key in the query counts, the header does not
        if token not in TOKENS:
            return self.reply(401)
        CURRENT.user = mock_admin.token_user(token)
        if playstate_route(method, [x.lower() for x in segs]):
            record_playstate(method, path, q, raw)
        asked_for = q.get("userid", [""])[0]
        if asked_for and norm(asked_for) != current_user():
            return self.reply(403)  # nobody acts for someone else here, not even an administrator
        if random.random() < CONFIG["fail_rate"]:
            return self.reply(500)
        if method == "POST" and low == "/sessions/logout":
            TOKENS.discard(token)
            return self.reply(204)
        if mock_admin.handle(self, method, path, q, raw, token):
            return
        if not self.authenticated(method, path, q, raw):
            return self.reply(404)

    def playback(self, method, path, q, raw):
        """Playback routes. Returns None when the path is not one of them."""
        segs = path.strip("/").split("/")
        low = [x.lower() for x in segs]
        item = lambda i: ITEMS.get(norm(i)) if ID_RE.fullmatch(i) and visible(norm(i)) else None
        if method == "GET" and low == ["mock", "reports"]:
            return self.reply(200, list(REPORTS))
        if method == "GET" and low == ["mock", "downloads"]:
            return self.reply(200, list(DOWNLOADS))
        if method == "POST" and low in (["mock", "no-download"], ["mock", "allow-download"]):
            CONFIG["no_download"] = low[1] == "no-download"  # --no-download at run time: a restart would forget every token
            return self.reply(204)
        if method == "GET" and low[0] == "items" and len(segs) == 3 and low[2] == "download":
            target = item(segs[1])
            if not target:
                return self.reply(404)
            return self.download() if playable(target) else self.reply(400)  # Jellyfin: ArgumentException for a folder
        if method == "POST" and low[:2] == ["sessions", "playing"] and len(segs) <= 3:
            kind = {"": "start", "progress": "progress", "stopped": "stopped", "ping": "ping"}.get(low[2] if len(segs) == 3 else "")
            if kind is None:
                return None
            body = {"PlaySessionId": q.get("playsessionid", [None])[0]} if kind == "ping" else json_body(raw)
            record_report(kind, body)
            return self.reply(204)
        if method == "GET" and low[0] == "mediasegments" and len(segs) == 2:
            target = item(segs[1])
            if not target or not playable(target):
                return self.reply(404)
            spans = (("Intro", 1, 3), ("Outro", 8, 10))
            return self.reply(200, {"Items": [{"Id": guid(f"segment-{kind}-{target['Id']}"), "ItemId": target["Id"], "Type": kind,
                                               "StartTicks": a * TICKS, "EndTicks": b * TICKS} for kind, a, b in spans],
                                    "TotalRecordCount": len(spans), "StartIndex": 0})
        if method == "POST" and low[0] == "items" and len(segs) == 3 and low[2] == "playbackinfo":
            target = item(segs[1])
            if not target or not playable(target):
                return self.reply(404)
            body = json_body(raw)
            if CONFIG["refuse"]:
                return self.reply(200, {"MediaSources": [], "ErrorCode": "NotAllowed"})
            session = secrets.token_hex(16)
            transcoding = flag(body, q, "EnableTranscoding")
            source = media_source(target["Id"], flag(body, q, "EnableDirectPlay"), flag(body, q, "EnableDirectStream"), session, transcoding)
            if transcoding:
                new_session(session, target["Id"])
            return self.reply(200, {"MediaSources": [source], "PlaySessionId": session})
        target = item(segs[1]) if method == "GET" and low[0] == "videos" and len(segs) > 1 else None
        if target and playable(target):
            if len(segs) == 3 and low[2] == "stream":
                return self.clip(self.headers.get("Range", ""))  # a key in the query was already refused (dispatch)
            if len(segs) == 6 and low[3] == "subtitles" and SUBTITLE_FILE_RE.fullmatch(low[5]) and segs[4].isdigit() and int(segs[4]) in SUBTITLES:
                name, content_type = SUBTITLES[int(segs[4])]
                return self.reply(200, raw=(MEDIA_DIR / name).read_bytes(), content_type=content_type)
            if segs[0] == "videos":  # the lower-case path the mock puts in its own TranscodingUrl
                return self.hls(target["Id"], "/".join(segs[2:]), q)
        return None

    def hls(self, item_id, rest, q):
        if rest == "master.m3u8":
            session = q.get("playsessionid", [""])[0]
            media = f"hls/{session}/main.m3u8" if session in SESSIONS else "hls/main.m3u8"  # a known play session is in the path
            playlist = f"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x180\n{media}\n"
            return self.reply(200, raw=playlist.encode(), content_type="application/vnd.apple.mpegurl")
        parts = rest.split("/")
        if len(parts) == 3 and parts[0] == "hls" and parts[1] in SESSIONS and SESSIONS[parts[1]]["item"] == item_id:
            return self.session_file(SESSIONS[parts[1]], parts[1], parts[2])
        if rest == "hls/main.m3u8":
            return self.reply(200, raw=(MEDIA_DIR / "hls" / "main.m3u8").read_bytes(), content_type="application/vnd.apple.mpegurl")
        found = HLS_SEGMENT_RE.fullmatch(rest)
        if found and (MEDIA_DIR / "hls" / found[1]).is_file():
            return self.reply(200, raw=(MEDIA_DIR / "hls" / found[1]).read_bytes(), content_type="video/mp2t", media=True)
        return self.reply(404)

    def clip(self, range_header):
        data = (MEDIA_DIR / "clip.mp4").read_bytes()
        span = byte_range(range_header, len(data))
        headers = {"Accept-Ranges": "bytes"}
        if span == "bad":
            return self.reply(416, headers={**headers, "Content-Range": f"bytes */{len(data)}"})
        if span is None:
            return self.reply(200, raw=data, headers=headers, content_type="video/mp4", media=True)
        start, end = span
        headers["Content-Range"] = f"bytes {start}-{end}/{len(data)}"
        return self.reply(206, raw=data[start:end + 1], headers=headers, content_type="video/mp4", media=True)

    def session_file(self, session, session_id, name):
        """One play session's media playlist or segment; --hls-idle-kill ends a session whose segments went unasked for too long."""
        if name == "main.m3u8":
            return self.reply(200, raw=session_playlist(session_id), content_type="application/vnd.apple.mpegurl")
        found = SESSION_SEGMENT_RE.fullmatch(name)
        if not found or int(found[1]) >= len(session["shape"]):
            return self.reply(404)
        now, idle = time.monotonic(), CONFIG["hls_idle_kill"]
        if session["dead"] or (idle is not None and now - session["last"] > idle):
            session["dead"] = True
            return self.reply(404)
        session["last"] = now
        data = (MEDIA_DIR / "hls" / f"seg{int(found[1]) % 5}.ts").read_bytes()
        return self.reply(200, raw=data, content_type="video/mp2t", media=True)

    def download(self):
        """GET /Items/{id}/Download: the clip, with validators and (unless --no-range) Range and If-Range as ASP.NET Core serves files."""
        if CONFIG["no_download"] or CONFIG["download_forbidden"] or not mock_admin.may_download(current_user()):
            return self.reply(403)
        data = (MEDIA_DIR / "clip.mp4").read_bytes()
        etag, modified = validators()
        headers = {"Last-Modified": modified} if CONFIG["no_etag"] else {"ETag": f'"{etag}"', "Last-Modified": modified}
        content_type = "application/octet-stream" if CONFIG["octet_stream"] else "video/mp4"
        if CONFIG["no_range"]:
            return self.reply(200, raw=data, headers=headers, content_type=content_type, media=True)
        headers["Accept-Ranges"] = "bytes"
        span = byte_range(self.headers.get("Range", ""), len(data))
        if_range = self.headers.get("If-Range")
        if span is not None and if_range is not None and not if_range_matches(if_range, etag, modified):
            span = None  # a validator that no longer matches: the whole file, as 200
        if span == "bad":
            return self.reply(416, headers={**headers, "Content-Range": f"bytes */{len(data)}"})
        if span is None:
            return self.reply(200, raw=data, headers=headers, content_type=content_type, media=True)
        start, end = span
        headers["Content-Range"] = f"bytes {start}-{end}/{len(data)}"
        return self.reply(206, raw=data[start:end + 1], headers=headers, content_type=content_type, media=True)

    def authenticated(self, method, path, q, raw):
        segs = [x.lower() if n in (0, 2) else x for n, x in enumerate(path.strip("/").split("/"))]
        low = path.lower()
        item = lambda i: ITEMS.get(norm(i)) if visible(norm(i)) else None
        handled = self.playback(method, path, q, raw)
        if handled:
            return handled
        kind = playstate_route(method, segs)
        if kind:
            return self.playstate(kind, method, item(segs[1]), q, raw)
        if method == "GET":
            if low == "/users/me":
                return self.reply(200, {"Name": USER_NAME, "ServerId": SERVER_ID, "Id": USER_ID, "HasPassword": True})
            if low == "/userviews":
                return self.reply(200, result([ITEMS[FILMS_ID], ITEMS[TV_ID]]))
            if low == "/items":
                return self.reply(200, list_items(q))
            if low == "/items/latest":
                return self.reply(200, latest(q))
            if low == "/useritems/resume":
                return self.reply(200, resume_items(q))
            if low == "/shows/nextup":
                return self.reply(200, next_up(q))
            if len(segs) == 2 and segs[0] == "items" and item(segs[1]):
                return self.reply(200, dto(item(segs[1])))
            if len(segs) == 3 and segs[0] == "shows" and item(segs[1]) and item(segs[1])["Type"] == "Series":
                series = item(segs[1])
                if segs[2] == "seasons":
                    return self.reply(200, page([ITEMS[c] for c in CHILDREN[series["Id"]]], q))
                if segs[2] == "episodes":
                    season = norm(q.get("seasonid", [""])[0])
                    # Only a season of this series the user may see (final review M5), and only episodes the user may see.
                    own = season in ITEMS and visible(season) and ITEMS[season].get("SeriesId") == series["Id"]
                    ids = [e for e in episodes_of(season if own else series["Id"]) if visible(e)]
                    return self.reply(200, page(sorted((ITEMS[e] for e in ids), key=lambda e: (e["ParentIndex"], e["Index"])), q))
        return False

    def playstate(self, kind, method, target, q, raw):
        """GET/POST /UserItems/{id}/UserData, POST/DELETE /UserPlayedItems/{id} and /UserFavoriteItems/{id}, for the token's user."""
        if target is None:
            return self.reply(404)
        if CONFIG["refuse_userdata"] and norm(CONFIG["refuse_userdata"]) == target["Id"]:
            return self.reply(403)
        if kind == "userdata" and method == "GET":
            return self.reply(404) if CONFIG["no_userdata_get"] else self.reply(200, user_data(target))
        if kind == "favourite":
            return self.reply(200, mark(target["Id"], fav=method == "POST"))
        if kind == "played" and method == "DELETE":
            return self.reply(200, mark(target["Id"], played=False, pos=0, count=0, last=None))
        if kind == "played":
            if "dateplayed" in q:
                return self.reply(400)  # the app must never send one
            counted = (lambda c: c + 1) if CONFIG["markplayed_counts"] else (lambda c: max(c, 1))
            answer = mark(target["Id"], played=True, pos=0, count=counted, stamp=True)
        else:
            answer = update_user_data(target["Id"], json_body(raw))
        if CONFIG["lose_reply_once"] and not PLAYSTATE_STATE["reply_lost"]:
            PLAYSTATE_STATE["reply_lost"] = True  # applied, but the connection closes with no response at all
            self.close_connection = True
            return True
        return self.reply(200, answer)

    def image(self, item, kind, q):
        kind = kind.lower()
        if not (kind == "primary" and not item.get("NoImage") or kind == "backdrop" and item.get("Backdrop")):
            return self.reply(404)
        aspect = 1.778 if kind == "backdrop" else item["Aspect"]
        width = min(intq(q, "maxwidth") or intq(q, "width") or intq(q, "fillwidth") or 300, 1000)
        height = min(intq(q, "maxheight") or intq(q, "height") or intq(q, "fillheight") or round(width / aspect), 1500)
        etag = f'"{image_tag(item)}-{kind}-{width}x{height}"'
        headers = {"ETag": etag, "Cache-Control": "public, max-age=3600"}
        if self.headers.get("If-None-Match") == etag:
            return self.reply(304, headers=headers)
        return self.reply(200, raw=png(item, max(width, 1), max(height, 1)), headers=headers, content_type="image/png")

    do_GET = do_POST = do_DELETE = route

    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description="Test stand-in for Jellyfin (not Jellyfin).")
    parser.add_argument("port", nargs="?", type=int, default=8096)
    parser.add_argument("--host", default="127.0.0.1")  # loopback only unless asked; the emulator reaches it as 10.0.2.2
    parser.add_argument("--latency-ms", type=int, default=0, help="delay every response by this many milliseconds")
    parser.add_argument("--fail-rate", type=float, default=0.0, help="fraction (0 to 1) of signed-in requests answered with a 500")
    parser.add_argument("--refuse", action="store_true", help="answer every PlaybackInfo request with ErrorCode NotAllowed")
    downloads = parser.add_argument_group("downloads", "one real-server behaviour each; any combination is allowed (see the README)")
    downloads.add_argument("--drop-after", type=int, metavar="BYTES", help="close the connection after BYTES body bytes, first media request only")
    downloads.add_argument("--no-range", action="store_true", help="/Download ignores Range and If-Range and sends no Accept-Ranges")
    downloads.add_argument("--etag-changes", action="store_true", help="every /Download has a new ETag and Last-Modified")
    downloads.add_argument("--octet-stream", action="store_true", help="/Download answers application/octet-stream")
    downloads.add_argument("--no-download", action="store_true", help="CanDownload false, and /Download is 403")
    downloads.add_argument("--candownload-missing", action="store_true", help="CanDownload left out of /Items; /Download still allowed")
    downloads.add_argument("--download-forbidden", action="store_true", help="CanDownload true, but /Download is 403")
    downloads.add_argument("--legacy-auth-only", action="store_true", help="/Download and HLS segments refuse header auth and want api_key")
    downloads.add_argument("--hls-recut", action="store_true", help="each PlaybackInfo gets a media playlist of a different shape")
    downloads.add_argument("--no-etag", action="store_true", help="/Download sends Last-Modified only, as real Jellyfin does")
    downloads.add_argument("--hls-idle-kill", type=float, metavar="SECONDS", help="a play session's segments are 404 after SECONDS idle")
    sync = parser.add_argument_group("playstate", "one way a real server might take a sync each; any combination is allowed (see the README)")
    sync.add_argument("--userdata-ignores-dates", action="store_true", help="POST /UserItems/{id}/UserData ignores PlayCount and LastPlayedDate")
    sync.add_argument("--markplayed-counts", action="store_true", help="POST /UserPlayedItems/{id} adds one to the play count every time")
    sync.add_argument("--lose-reply-once", action="store_true", help="the first user-data or mark-played write is applied, then no reply")
    sync.add_argument("--no-userdata-get", action="store_true", help="GET /UserItems/{id}/UserData is 404 for every item")
    sync.add_argument("--refuse-userdata", metavar="ITEMID", help="every user-data route for that item is 403")
    sync.add_argument("--clock-skew", type=int, default=0, metavar="SECONDS", help="shift every LastPlayedDate the server writes itself")
    parser.add_argument("--ben-not-admin", action="store_true", help="ben is not an administrator (the Server admin entry must not show)")
    parser.add_argument("--pending-restart", action="store_true", help="System/Info says a restart is needed")
    parser.add_argument("--quick-connect", action="store_true", help="Quick Connect on: a code is approved on the second poll and signs in as ben, once")
    parser.add_argument("--admin-forbidden", action="store_true", help="admin-only routes answer 403 while /Users/Me still says administrator")
    args = parser.parse_args()
    CONFIG.update(latency=args.latency_ms / 1000, fail_rate=args.fail_rate, refuse=args.refuse, quick_connect=args.quick_connect)
    CONFIG.update({name: getattr(args, name) for name in DOWNLOAD_FLAGS})
    CONFIG.update({name: getattr(args, name) for name in PLAYSTATE_FLAGS})
    mock_admin.CONFIG.update(ben_admin=not args.ben_not_admin, pending_restart=args.pending_restart, admin_forbidden=args.admin_forbidden)
    build_catalogue()
    seed_state()
    print(f"Mock Jellyfin (test stand-in) on {args.host}:{args.port}; users {USER_NAME}, anna and kid", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
