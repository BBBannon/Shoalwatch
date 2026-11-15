"""A TEST STAND-IN for Jellyfin, for emulator walkthroughs of Shoalwatch. It is NOT a Jellyfin implementation.

Standard library only. Serves the endpoints the app uses (shapes taken from the official Jellyfin 12.4.0 OpenAPI
reference in docs/reference) from a small in-memory catalogue. Plain http, no TLS, no real security: never expose it.

    python -I mock_jellyfin.py [port] [--latency-ms N] [--fail-rate P] [--host H]

Each request logs one line: method, path (no query), auth yes/no, token yes/no, status. Never the token or password.
"""
import argparse
import hashlib
import json
import random
import re
import secrets
import threading
import time
import struct
import zlib
from pathlib import Path
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

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
    return dt.strftime("%Y-%m-%dT%H:%M:%S.0000000Z")


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


def media_source(item_id, direct_play, direct_stream, play_session_id):
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
    if not direct_play:
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
                mark(item["Id"], played=True, pos=0, count=user_state(item["Id"])["count"] + 1, stamp=True)
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


STATE, LOCK = {}, threading.RLock()


def user_state(item_id):
    return STATE.setdefault(item_id, {"played": False, "pos": 0, "fav": False, "count": 0, "last": None, "rating": None, "likes": None})


def seed_state():
    film = lambda n: guid(f"film-{n}")
    for n, pct in ((3, .35), (11, .6), (19, .15)):
        s = user_state(film(n)); s["pos"] = int(ITEMS[film(n)]["Runtime"] * pct); s["last"] = NOW - timedelta(hours=n)
    for n in (5, 6, 7):
        s = user_state(film(n)); s.update(played=True, count=1, last=NOW - timedelta(days=n))
    for n in (2, 9, 30):
        user_state(film(n))["fav"] = True
    for sn, eps in ((1, range(1, 7)), (2, range(1, 2))):  # Harbour Lights: season 1 and S2E1 watched
        for ep in eps:
            user_state(guid(f"episode-0-{sn}-{ep}")).update(played=True, count=1, last=NOW - timedelta(days=20 - ep - 7 * (2 - sn)))
    s = user_state(guid("episode-0-2-2")); s["pos"] = 18 * 60 * TICKS; s["last"] = NOW - timedelta(hours=2)  # S2E2 half watched


def when(item_id):
    """When the item was last played; never-played sorts as very old."""
    return user_state(item_id)["last"] or NOW - timedelta(days=9999)


def episodes_of(item_id):
    item = ITEMS[item_id]
    if item["Type"] == "Episode":
        return [item_id]
    return [e for c in CHILDREN.get(item_id, []) for e in episodes_of(c)] if item["IsFolder"] else []


def descendants(item_id):
    return [d for c in CHILDREN.get(item_id, []) for d in [c] + descendants(c)]


def user_data(item):
    iid = item["Id"]
    st = user_state(iid)
    if item["Type"] in ("Series", "Season"):
        eps = episodes_of(iid)
        unplayed = sum(1 for e in eps if not user_state(e)["played"])
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
    if parent:
        pool = descendants(parent) if recursive else CHILDREN.get(parent, [])
    else:
        pool = [i for i in ORDER if recursive or ITEMS[i]["Type"] == "CollectionFolder"]
    ids = {norm(i) for i in csv(q, "ids")}
    types, filters = {t.lower() for t in csv(q, "includeitemtypes")}, {f.lower() for f in csv(q, "filters")}
    term = q.get("searchterm", [""])[0].lower()
    out = []
    for iid in pool:
        item, st = ITEMS[iid], user_state(iid)
        if ids and iid not in ids or types and item["Type"].lower() not in types or term and term not in item["Name"].lower():
            continue
        played = user_data(item)["Played"]
        if "isunplayed" in filters and played or "isplayed" in filters and not played or "isfavorite" in filters and not st["fav"]:
            continue
        out.append(item)
    return page(in_order(out, q), q)


def resume_items(q):
    parent = norm(q.get("parentid", [""])[0])
    scope = set(descendants(parent)) if parent else None
    rows = [ITEMS[i] for i, st in list(STATE.items()) if st["pos"] > 0 and not st["played"] and (scope is None or i in scope) and ITEMS[i]["Type"] in ("Movie", "Episode")]
    return page(sorted(rows, key=lambda i: when(i["Id"]), reverse=True), q)


def next_up(q):
    series_id, parent = norm(q.get("seriesid", [""])[0]), norm(q.get("parentid", [""])[0])
    resumable = q.get("enableresumable", ["true"])[0].lower() != "false"  # false: leave out episodes already part way through
    rows = []
    for sid in CHILDREN.get(TV_ID, []):
        if (series_id and sid != series_id) or (parent and parent not in (sid, TV_ID)):
            continue
        eps = sorted(episodes_of(sid), key=lambda e: (ITEMS[e]["ParentIndex"], ITEMS[e]["Index"]))
        played = [n for n, e in enumerate(eps) if user_state(e)["played"]]
        if played and played[-1] + 1 < len(eps) and (resumable or user_state(eps[played[-1] + 1])["pos"] == 0):
            rows.append((max(when(eps[n]) for n in played), ITEMS[eps[played[-1] + 1]]))
    return page([item for _, item in sorted(rows, key=lambda r: r[0], reverse=True)], q)


def latest(q):
    parent = norm(q.get("parentid", [""])[0])
    kinds = ("Movie", "Series")
    pool = [ITEMS[c] for c in (CHILDREN.get(parent, []) if parent else ORDER) if ITEMS[c]["Type"] in kinds]
    limit = intq(q, "limit", 20)
    return [dto(i) for i in sorted(pool, key=lambda i: i["Created"], reverse=True)[:limit]]


def mark(item_id, **changes):
    with LOCK:
        for target in episodes_of(item_id) or [item_id]:
            st = user_state(target)
            st.update({k: v for k, v in changes.items() if k != "stamp"})
            if changes.get("stamp"):
                st["last"] = datetime.now(timezone.utc)
        return user_data(ITEMS[item_id])


def update_user_data(item_id, body):
    names = {"Played": "played", "IsFavorite": "fav", "PlaybackPositionTicks": "pos", "PlayCount": "count", "Rating": "rating", "Likes": "likes"}
    changes = {names[k]: v for k, v in body.items() if k in names and v is not None}
    if "LastPlayedDate" in body and body["LastPlayedDate"]:
        changes["stamp"] = True
    return mark(item_id, **changes)


def png(item, width, height):
    r, g, b = (60 + int(c, 16) * 8 for c in guid("colour-" + item["Id"])[:3])
    row = b"\x00" + bytes((r, g, b)) * width
    raw = zlib.compress(row * height, 6)
    chunk = lambda tag, data: struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


# ------------------------------------------------------------------- server
CONFIG = {"latency": 0.0, "fail_rate": 0.0, "refuse": False}
TOKENS = set()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    status = 0

    def reply(self, code, body=None, headers=None, raw=None, content_type="application/json"):
        data = raw if raw is not None else (b"" if body is None else json.dumps(body).encode())
        self.status = code
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
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
        if method == "GET" and len(segs) >= 4 and segs[0].lower() == "items" and segs[2].lower() == "images":
            # like Jellyfin, artwork needs no token
            return self.image(ITEMS[norm(segs[1])], segs[3], q) if norm(segs[1]) in ITEMS else self.reply(404)
        if method == "GET" and low == "/system/info/public":
            return self.reply(200, {"Id": SERVER_ID, "ServerName": "Test Jellyfin", "Version": "12.4.0", "ProductName": "Mock Jellyfin (test stand-in)"})
        if method == "GET" and low == "/quickconnect/enabled":
            return self.reply(200, False)
        if method == "POST" and low == "/users/authenticatebyname":
            try:
                creds = json.loads(raw or b"{}")
            except ValueError:
                creds = {}
            if not (isinstance(creds, dict) and creds.get("Username") == USER_NAME and creds.get("Pw") == PASSWORD):
                return self.reply(401)
            issued = "mock-" + secrets.token_hex(16)
            TOKENS.add(issued)
            user = {"Name": USER_NAME, "ServerId": SERVER_ID, "Id": USER_ID, "HasPassword": True}
            return self.reply(200, {"User": user, "SessionInfo": {}, "AccessToken": issued, "ServerId": SERVER_ID})
        if token not in TOKENS:
            return self.reply(401)
        if random.random() < CONFIG["fail_rate"]:
            return self.reply(500)
        if method == "POST" and low == "/sessions/logout":
            TOKENS.discard(token)
            return self.reply(204)
        if not self.authenticated(method, path, q, raw):
            return self.reply(404)

    def playback(self, method, path, q, raw):
        """Playback routes. Returns None when the path is not one of them."""
        segs = path.strip("/").split("/")
        low = [x.lower() for x in segs]
        item = lambda i: ITEMS.get(norm(i)) if ID_RE.fullmatch(i) else None
        if method == "GET" and low == ["mock", "reports"]:
            return self.reply(200, list(REPORTS))
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
            source = media_source(target["Id"], flag(body, q, "EnableDirectPlay"), flag(body, q, "EnableDirectStream"), session)
            return self.reply(200, {"MediaSources": [source], "PlaySessionId": session})
        target = item(segs[1]) if method == "GET" and low[0] == "videos" and len(segs) > 1 else None
        if target and playable(target):
            if len(segs) == 3 and low[2] == "stream":
                if any(name in ("api_key", "apikey") for name in q):  # q names are already lower-cased
                    return self.reply(400)  # the app must never put a key in a URL
                return self.clip(self.headers.get("Range", ""))
            if len(segs) == 6 and low[3] == "subtitles" and SUBTITLE_FILE_RE.fullmatch(low[5]) and segs[4].isdigit() and int(segs[4]) in SUBTITLES:
                name, content_type = SUBTITLES[int(segs[4])]
                return self.reply(200, raw=(MEDIA_DIR / name).read_bytes(), content_type=content_type)
            if segs[0] == "videos":  # the lower-case path the mock puts in its own TranscodingUrl
                return self.hls("/".join(segs[2:]))
        return None

    def hls(self, rest):
        if rest == "master.m3u8":
            playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x180\nhls/main.m3u8\n"
            return self.reply(200, raw=playlist.encode(), content_type="application/vnd.apple.mpegurl")
        if rest == "hls/main.m3u8":
            return self.reply(200, raw=(MEDIA_DIR / "hls" / "main.m3u8").read_bytes(), content_type="application/vnd.apple.mpegurl")
        found = HLS_SEGMENT_RE.fullmatch(rest)
        if found and (MEDIA_DIR / "hls" / found[1]).is_file():
            return self.reply(200, raw=(MEDIA_DIR / "hls" / found[1]).read_bytes(), content_type="video/mp2t")
        return self.reply(404)

    def clip(self, range_header):
        data = (MEDIA_DIR / "clip.mp4").read_bytes()
        span = byte_range(range_header, len(data))
        headers = {"Accept-Ranges": "bytes"}
        if span == "bad":
            return self.reply(416, headers={**headers, "Content-Range": f"bytes */{len(data)}"})
        if span is None:
            return self.reply(200, raw=data, headers=headers, content_type="video/mp4")
        start, end = span
        headers["Content-Range"] = f"bytes {start}-{end}/{len(data)}"
        return self.reply(206, raw=data[start:end + 1], headers=headers, content_type="video/mp4")

    def authenticated(self, method, path, q, raw):
        segs = [x.lower() if n in (0, 2) else x for n, x in enumerate(path.strip("/").split("/"))]
        low = path.lower()
        item = lambda i: ITEMS.get(norm(i))
        handled = self.playback(method, path, q, raw)
        if handled:
            return handled
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
                    ids = episodes_of(season if season in ITEMS else series["Id"])
                    return self.reply(200, page(sorted((ITEMS[e] for e in ids), key=lambda e: (e["ParentIndex"], e["Index"])), q))
            if len(segs) == 3 and segs[0] == "useritems" and segs[2] == "userdata" and item(segs[1]):
                return self.reply(200, user_data(item(segs[1])))
        elif len(segs) == 2 and segs[0] in ("userplayeditems", "userfavoriteitems") and item(segs[1]):
            on = method == "POST"
            if segs[0] == "userplayeditems":
                changes = {"played": on, "pos": 0, "stamp": on}
                if on:
                    changes["count"] = 1
                return self.reply(200, mark(item(segs[1])["Id"], **changes))
            return self.reply(200, mark(item(segs[1])["Id"], fav=on))
        elif method == "POST" and len(segs) == 3 and segs[0] == "useritems" and segs[2] == "userdata" and item(segs[1]):
            try:
                body = json.loads(raw or b"{}")
            except ValueError:
                return self.reply(400)
            return self.reply(200, update_user_data(item(segs[1])["Id"], body if isinstance(body, dict) else {}))
        return False

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
    args = parser.parse_args()
    CONFIG.update(latency=args.latency_ms / 1000, fail_rate=args.fail_rate, refuse=args.refuse)
    build_catalogue()
    seed_state()
    print(f"Mock Jellyfin (test stand-in) on {args.host}:{args.port}; user {USER_NAME}", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
