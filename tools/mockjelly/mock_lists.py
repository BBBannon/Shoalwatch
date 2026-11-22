"""Milestone 11 routes for the Jellyfin TEST STAND-IN (mock_jellyfin.py). NOT Jellyfin; never expose it.

Playlists, collections, people, trailers, extras, similar items, versions, filters, genres, studios, online subtitle search and
download, and the photos and home videos libraries, with shapes from the 12.4.0 OpenAPI reference. Imports nothing from
mock_jellyfin: that module hands itself to seed() and setup(). Prints nothing; what it received is kept only for
GET /mock/lists-events. Online addresses (a YouTube trailer) are data the app must hand to Android, never fetch.

Refusals as Jellyfin answers them: a playlist change by someone who is not its owner is 403; a collection change without
the CollectionManagement policy is 403; subtitle search and download without SubtitleManagement are 403; a refused delete
(mock_manage) is 401 while /Users/Me stays 200.
"""
import copy
import re
import secrets
import time

EVENTS = []  # actions received, newest last: {"Kind", "Target", "Query"} (+ extras)
S = {}  # dynamic state, rebuilt by setup()
SEED = {"ready": False}
M = None  # mock_jellyfin, handed in by seed() and setup()
ID_RE = re.compile(r"[0-9a-fA-F-]{32,36}")
ENTRY_RE = re.compile(r"[A-Za-z0-9-]{1,64}")
LANG_RE = re.compile(r"[a-z]{3}")
PEOPLE = ["Maria Hale", "Tom Reeve", "Priya Nair", "Jonas Lind", "Ada Okoro", "Sam Quill"]  # Ada has no picture
CAST = {  # film number (or "series-0") -> [(person number, Type, Role)]
    12: [(0, "Actor", "Captain Ellis"), (1, "Actor", "Harbour master"), (2, "Director", ""), (3, "Writer", ""), (4, "Actor", "Neighbour")],
    13: [(0, "Actor", "Nina"), (5, "Actor", "Doctor")],
    15: [(1, "Actor", "Alex"), (2, "Director", "")],
    "series-0": [(0, "Actor", "Captain Ellis")],
}
TRAILER_URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
STUDIOS = ["Harbour Pictures", "Northlight Studios"]
SEED_PLAYLISTS = [  # (key, name, owner, public, [item ids as seeds])
    ("saturday", "Saturday films", "ben", False, ["film-0", "film-3", "episode-0-1-1", "film-4"]),
    ("family", "Family favourites", "ben", True, ["film-2", "film-9", "film-30"]),
]
SEED_COLLECTIONS = [("harbour", "Harbour Collection", ["film-0", "film-1", "film-2"]), ("family-night", "Family Night", ["film-3", "film-4", "film-8"])]


def record(kind, target, q, **extra):
    EVENTS.append({"Kind": kind, "Target": target, "Query": {k: v[0] for k, v in q.items()}, **extra})


def gid(name):
    return M.guid(f"m11-{name}")


# ------------------------------------------------------------------ catalogue
def seed(module):
    """Add the Milestone 11 items to the catalogue, once. Called by build_catalogue()."""
    global M
    M = module
    if SEED["ready"]:
        return
    SEED["ready"] = True
    add, guid = M.add, M.guid
    for n, name in enumerate(PEOPLE):
        add({"Id": gid(f"person-{n}"), "Type": "Person", "Name": name, "Aux": True, "Aspect": 0.667, "NoImage": n == 4,
             "Overview": f"{name} is a made-up person for testing the stand-in's people pages."})
    for n in range(40):  # studios and tags on the films, so there are choices to filter by
        film = M.ITEMS[guid(f"film-{n}")]
        if n % 3 < 2:
            film["Studios"] = [STUDIOS[n % 3]]
        if n % 5 == 0:
            film["Tags"] = ["classic"]
        if n % 7 == 0:
            film["Tags"] = film.get("Tags", []) + ["feelgood"]
    rich = guid("film-12")  # the one film with people, a trailer, extras and two versions
    add({"Id": gid("trailer-0"), "Type": "Trailer", "Name": "The Broken Orchard - Trailer", "Aux": True, "Aspect": 1.778, "Runtime": 2 * 60 * M.TICKS,
         "Overview": "A short made-up trailer."})
    for n, (name, kind) in enumerate([("Behind the scenes", "BehindTheScenes"), ("Deleted scenes", "DeletedScene")]):
        add({"Id": gid(f"extra-{n}"), "Type": "Video", "Name": name, "Aux": True, "Aspect": 1.778, "Runtime": (8 + n * 3) * 60 * M.TICKS,
             "ExtraType": kind, "Overview": f"{name} for The Broken Orchard."})
    S["rich"] = rich
    for n, name in enumerate(["Harbour Collection", "Family Night"]):
        add({"Id": gid(f"box-{n}"), "Type": "BoxSet", "Name": name, "IsFolder": True, "Aspect": 0.667, "Overview": f"{name}: a made-up collection."})
    for key, name, _, _, _ in SEED_PLAYLISTS:
        add({"Id": gid(f"playlist-{key}"), "Type": "Playlist", "Name": name, "IsFolder": True, "Aspect": 1.0, "NoImage": True, "MediaType": "Video"})
    photos = add({"Id": gid("library-photos"), "Type": "CollectionFolder", "Name": "Photos", "IsFolder": True, "Collection": "photos", "Aspect": 1.5})
    home = add({"Id": gid("library-home"), "Type": "CollectionFolder", "Name": "Home videos", "IsFolder": True, "Collection": "homevideos", "Aspect": 1.5})
    S["libraries"] = [photos["Id"], home["Id"]]
    album = add({"Id": gid("album-summer"), "Type": "PhotoAlbum", "Name": "Summer trip", "ParentId": photos["Id"], "IsFolder": True, "Aspect": 1.5})
    for n, parent in enumerate([album["Id"]] * 3 + [photos["Id"]] * 2):
        add({"Id": gid(f"photo-{n}"), "Type": "Photo", "Name": ["Harbour at dawn", "Beach huts", "Lighthouse", "2019 beach", "Orchard"][n],
             "ParentId": parent, "Aspect": 1.5, "Width": 4000, "Height": 2667, "Year": 2019 + n % 2})
    clips = add({"Id": gid("folder-clips"), "Type": "Folder", "Name": "Holiday clips", "ParentId": home["Id"], "IsFolder": True, "Aspect": 1.778})
    for n, (name, parent) in enumerate([("Sports day", home["Id"]), ("Birthday party", home["Id"]), ("Sandcastle", clips["Id"])]):
        add({"Id": gid(f"video-{n}"), "Type": "Video", "Name": name, "ParentId": parent, "Aspect": 1.778, "Runtime": (2 + n) * 60 * M.TICKS, "Year": 2020 + n})
    add({"Id": gid("photo-home"), "Type": "Photo", "Name": "Family picnic", "ParentId": home["Id"], "Aspect": 1.5, "Width": 3000, "Height": 2000})


def setup(module):
    """(Re)build the dynamic state: playlists, collections, downloads of subtitles, events. Called by setup_manage()."""
    global M
    M = module
    for iid in S.get("created", []):
        M.ITEMS.pop(iid, None)
        for pool in (M.ORDER, M.CHILDREN.get(None, [])):
            if iid in pool:
                pool.remove(iid)
    names = S.get("names", {})
    for iid, name in names.items():
        if iid in M.ITEMS:
            M.ITEMS[iid]["Name"] = name
    EVENTS.clear()
    ben = M.USER_ID
    S.update(created=[], delay=3.0, added={}, offered={}, lookups={})
    S["playlists"] = {gid(f"playlist-{key}"): {"owner": ben, "public": public,
                                                "entries": [[M.guid(f"entry-{key}-{n}"), M.guid(i)] for n, i in enumerate(items)]}
                      for key, _, _, public, items in SEED_PLAYLISTS}
    S["collections"] = {gid(f"box-{n}"): [M.guid(i) for i in items] for n, (_, _, items) in enumerate(SEED_COLLECTIONS)}
    S["names"] = {iid: M.ITEMS[iid]["Name"] for iid in [*S["playlists"], *S["collections"]]}


def libraries():
    """The photos and home videos libraries, for /UserViews."""
    return [M.ITEMS[i] for i in S.get("libraries", [])] if M else []


def hidden(item_id, uid):
    """A private playlist belongs to its owner alone."""
    pl = S.get("playlists", {}).get(item_id)
    return bool(pl and not pl["public"] and pl["owner"] != uid)


def members(parent):
    """The direct children of a collection or playlist (list_items' pool), or None when `parent` is neither."""
    if parent in S.get("collections", {}):
        return [i for i in S["collections"][parent] if i in M.ITEMS]
    if parent in S.get("playlists", {}):
        return [i for _, i in S["playlists"][parent]["entries"] if i in M.ITEMS]
    return None


def scope(parent):
    """The films and series a genre, studio or filter list is about: a library's, or all of them."""
    pool = M.descendants(parent) if parent else M.ORDER
    return [M.ITEMS[i] for i in pool if M.ITEMS[i]["Type"] in ("Movie", "Series") and M.visible(i)]


def genre_names(item):
    return [item["Genre"]] if item.get("Genre") else []


def named_id(kind, name):
    return gid(f"{kind}-{name}")


def keep(item, q):
    """The new list_items filters: one value each (genre, studio, year, rating, tag), a person, and the letter jump."""
    checks = (("genreids", lambda i: {named_id("genre", n) for n in genre_names(i)}, True),
              ("studioids", lambda i: {named_id("studio", n) for n in i.get("Studios", [])}, True),
              ("personids", lambda i: {p for p, _, _ in people_of(i)}, True),
              ("officialratings", lambda i: {i.get("Official")}, False),
              ("tags", lambda i: set(i.get("Tags", [])), False),
              ("years", lambda i: {str(i.get("Year"))}, False))
    for key, have, is_id in checks:
        raw = M.csv(q, key)
        if (is_id and not all(ID_RE.fullmatch(v) for v in raw)) or (key == "years" and not all(v.isdigit() for v in raw)):
            raise M.BadRequest(key)  # like Jellyfin, a value that is not an id or a year is a 400
        asked = {M.norm(v) for v in raw} if is_id else set(raw)
        if asked and not asked & have(item):
            return False
    name = item["Name"].lower()
    starts, below = q.get("namestartswith", [""])[0].lower(), q.get("namelessthan", [""])[0].lower()
    return (not starts or name.startswith(starts)) and (not below or name < below)


def people_of(item):
    """[(person id, Type, Role)] for a film or series."""
    if item["Type"] == "Movie":
        key = next((n for n in range(40) if M.guid(f"film-{n}") == item["Id"]), None)
    else:
        key = "series-0" if item["Id"] == M.guid("series-0") else None
    return [(gid(f"person-{p}"), kind, role) for p, kind, role in CAST.get(key, [])]


def decorate(item, out):
    """Extra fields on every item row (called at the end of mock_jellyfin.dto)."""
    kind = item["Type"]
    if kind == "Photo":
        out.update(MediaType="Photo", Width=item["Width"], Height=item["Height"])
    elif kind in ("Video", "Trailer"):
        out["MediaType"] = "Video"
    if item.get("ExtraType"):
        out["ExtraType"] = item["ExtraType"]
    if item.get("Studios"):
        out["Studios"] = [{"Name": n, "Id": named_id("studio", n)} for n in item["Studios"]]
    if item.get("Tags"):
        out["Tags"] = list(item["Tags"])
    if item.get("Genre"):
        out["GenreItems"] = [{"Name": n, "Id": named_id("genre", n)} for n in genre_names(item)]
    if item["Id"] in S.get("playlists", {}):
        out["ChildCount"] = len(S["playlists"][item["Id"]]["entries"])
    elif item["Id"] in S.get("collections", {}):
        out["ChildCount"] = len(members(item["Id"]))


def versions(item, source):
    """The sources of a film: [source], or two for the film with two versions."""
    if item["Id"] != S.get("rich"):
        return [source]
    second = copy.deepcopy(source)
    second["Id"] = gid("source-b")
    second["Name"] = "Director's cut"
    second["Bitrate"] = 2_000_000
    if "TranscodingUrl" in second:
        second["TranscodingUrl"] = second["TranscodingUrl"].replace(f"MediaSourceId={item['Id']}", f"MediaSourceId={second['Id']}")
    return [source, second]


def detail(item, row):
    """What GET /Items/{id} adds for one item: people, trailers, extras, versions and streams."""
    kind = item["Type"]
    if kind in ("Movie", "Series"):
        row["People"] = [{"Name": M.ITEMS[p]["Name"], "Id": p, "Role": role, "Type": k,
                          **({} if M.ITEMS[p].get("NoImage") else {"PrimaryImageTag": M.image_tag(M.ITEMS[p])})} for p, k, role in people_of(item)]
        row["LocalTrailerCount"] = len(trailers_of(item))
        row["SpecialFeatureCount"] = len(extras_of(item))
        row["RemoteTrailers"] = [{"Url": TRAILER_URL, "Name": "Official trailer"}] if item["Id"] == S.get("rich") else []
    if row.get("MediaSources"):
        row["MediaSources"] = versions(item, row["MediaSources"][0])
        row["MediaStreams"] = row["MediaSources"][0]["MediaStreams"]


def trailers_of(item):
    return [M.ITEMS[gid("trailer-0")]] if item["Id"] == S.get("rich") else []


def extras_of(item):
    return [M.ITEMS[gid(f"extra-{n}")] for n in range(2)] if item["Id"] == S.get("rich") else []


# ------------------------------------------------------------------ subtitles added by a download
def added_streams(item_id):
    """The subtitle streams a download has added by now (Index 4 onwards): the server adds them after a short delay."""
    now = time.monotonic()
    out = []
    for n, entry in enumerate(S.get("added", {}).get(item_id, [])):
        if entry["at"] <= now:
            out.append({"Index": 4 + n, "Type": "Subtitle", "Codec": "subrip", "Language": entry["lang"], "IsExternal": True, "IsTextSubtitleStream": True,
                        "SupportsExternalStream": True, "DeliveryMethod": "External", "IsDefault": False,
                        "DisplayTitle": f"{entry['lang'].upper()} - SubRip - External"})
    return out


def is_added(item_id, index):
    return any(s["Index"] == index for s in added_streams(item_id))


def search_results(item, lang, perfect):
    rows = []
    for n, (provider, fmt, hash_match, hearing) in enumerate([("OpenSubtitles", "srt", True, False), ("OpenSubtitles", "srt", False, True),
                                                                ("Podnapisi", "ass", False, False)]):
        sid = M.guid(f"subtitle-{item['Id']}-{lang}-{n}")
        rows.append({"ThreeLetterISOLanguageName": lang, "Id": sid, "ProviderName": provider, "Name": f"{item['Name'].replace(' ', '.')}.{lang}.{n}",
                     "Format": fmt, "Author": f"uploader{n}", "Comment": f"Stand-in subtitle number {n + 1}", "DateCreated": "2024-05-01T10:00:00.0000000Z",
                     "CommunityRating": round(8.5 - n, 1), "FrameRate": 24.0, "DownloadCount": 5000 - n * 1500, "IsHashMatch": hash_match,
                     "AiTranslated": False, "MachineTranslated": False, "Forced": n == 2, "HearingImpaired": hearing})
        S["offered"][(item["Id"], sid)] = lang
    return [r for r in rows if r["IsHashMatch"]] if perfect else rows


# ------------------------------------------------------------------ routes
def may(uid, key):
    return bool(M.mock_admin.S.get("users", {}).get(uid, {}).get("Policy", {}).get(key))


def body_of(raw):
    import json
    try:
        body = json.loads(raw or b"{}")
    except ValueError:
        return None
    return body if isinstance(body, dict) else None


def clean_name(value):
    name = value.strip() if isinstance(value, str) else ""
    return name if 1 <= len(name) <= 200 and name.isprintable() else None


def ids_of(q, name):
    """A comma list of ids (dashes allowed), or None when any is not an id."""
    raw = M.csv(q, name)
    return [M.norm(v) for v in raw] if raw and all(ID_RE.fullmatch(v) for v in raw) else None


def handle(handler, method, path, q, raw, uid):
    """Answer a Milestone 11 route (True), or leave the request to the others (False). The token is already one mock_jellyfin issued."""
    if not S:
        return False
    segs = path.strip("/").split("/")
    low = [x.lower() for x in segs]
    reply = handler.reply
    if method == "GET" and low == ["mock", "lists-events"]:
        return reply(200, list(EVENTS))
    head = low[0]
    if head == "playlists":
        return playlists(handler, method, segs, low, q, raw, uid)
    if head == "collections":
        return collections(handler, method, segs, low, q, uid)
    if method != "GET" and not (head == "items" and len(low) == 5 and low[2] == "remotesearch"):
        return False
    if head == "items" and len(low) == 2 and low[1] == "filters":
        return reply(200, filters(q))
    if head in ("genres", "studios") and len(low) == 1:
        return reply(200, genres_or_studios("genre" if head == "genres" else "studio", q))
    if head == "persons" and len(low) == 1:
        term = q.get("searchterm", [""])[0].lower()
        rows = [i for i in M.ITEMS.values() if i["Type"] == "Person" and term in i["Name"].lower() and M.visible(i["Id"])]
        return reply(200, M.page(M.in_order(rows, q), q))
    if head == "items" and len(low) >= 3 and ID_RE.fullmatch(segs[1]):
        item = M.ITEMS.get(M.norm(segs[1]))
        found = item is not None and M.visible(item["Id"])
        if low[2] in ("localtrailers", "specialfeatures", "similar") and len(low) == 3:
            if not found:
                return reply(404)
            if low[2] == "localtrailers":
                return reply(200, [M.dto(i) for i in trailers_of(item)])
            if low[2] == "specialfeatures":
                return reply(200, [M.dto(i) for i in extras_of(item)])
            same = [i for i in scope("") if i["Type"] == item["Type"] and i["Id"] != item["Id"] and set(genre_names(i)) & set(genre_names(item))]
            return reply(200, M.page(same, {"limit": [str(min(M.intq(q, "limit", 12), 50))], "startindex": ["0"]}))
        if low[2] == "remotesearch" and len(low) == 5 and low[3] == "subtitles":
            return subtitles(handler, method, item if found else None, segs[4], q, uid)
    return False


def playlist_of(handler, segs, uid, write):
    """(playlist, item) or an already-sent refusal (None, True)."""
    if not ID_RE.fullmatch(segs[1]):
        return None, handler.reply(404)
    pid = M.norm(segs[1])
    pl = S["playlists"].get(pid)
    if pl is None or not M.visible(pid) or hidden(pid, uid):
        return None, handler.reply(404)
    if write and pl["owner"] != uid:
        return None, handler.reply(403)
    return pl, pid


def playlists(handler, method, segs, low, q, raw, uid):
    reply = handler.reply
    if len(low) == 1 and method == "POST":
        body = body_of(raw)
        name = clean_name(body.get("Name")) if body else None
        ids = body.get("Ids", []) if body else None
        if name is None or not isinstance(ids, list) or not all(isinstance(i, str) and ID_RE.fullmatch(i) for i in ids):
            return reply(400)
        if body.get("UserId") and M.norm(str(body["UserId"])) != uid:
            return reply(403)  # nobody acts for someone else
        new = M.add({"Id": secrets.token_hex(16), "Type": "Playlist", "Name": name, "IsFolder": True, "Aspect": 1.0, "NoImage": True, "MediaType": "Video"})
        S["created"].append(new["Id"])
        S["playlists"][new["Id"]] = {"owner": uid, "public": bool(body.get("IsPublic", False)),
                                     "entries": [[secrets.token_hex(16), M.norm(i)] for i in ids if M.norm(i) in M.ITEMS and M.visible(M.norm(i))]}
        record("CreatePlaylist", new["Id"], q, Name=name, Count=len(S["playlists"][new["Id"]]["entries"]))
        return reply(200, {"Id": new["Id"]})
    if len(low) < 2:
        return False
    if len(low) == 3 and low[2] == "items" and method == "GET":
        pl, pid = playlist_of(handler, segs, uid, False)
        if pl is None:
            return True
        entries = [(e, i) for e, i in pl["entries"] if i in M.ITEMS and M.visible(i)]
        start, limit = M.intq(q, "startindex", 0), M.intq(q, "limit")
        rows = entries[start:start + limit if limit is not None else None]
        out = M.result([M.ITEMS[i] for _, i in rows], start, len(entries))
        fields = {f.lower() for f in M.csv(q, "fields")}
        for row, (entry, _) in zip(out["Items"], rows):
            row["PlaylistItemId"] = entry
            M.download_fields(row, M.ITEMS[row["Id"]], fields)
        return reply(200, out)
    if len(low) == 2 and method == "POST":  # rename
        pl, pid = playlist_of(handler, segs, uid, True)
        if pl is None:
            return True
        body = body_of(raw)
        name = clean_name(body.get("Name")) if body else None
        if name is None:
            return reply(400)
        M.ITEMS[pid]["Name"] = name
        record("RenamePlaylist", pid, q, Name=name)
        return reply(204)
    if len(low) == 3 and low[2] == "items" and method in ("POST", "DELETE"):
        pl, pid = playlist_of(handler, segs, uid, True)
        if pl is None:
            return True
        if method == "POST":
            ids = ids_of(q, "ids")
            if ids is None:
                return reply(400)
            added = [i for i in ids if i in M.ITEMS and M.visible(i) and M.ITEMS[i]["Type"] in ("Movie", "Episode", "Video", "Photo")]
            pl["entries"].extend([secrets.token_hex(16), i] for i in added)
            record("AddToPlaylist", pid, q, Added=len(added))
            return reply(204)
        wanted = M.csv(q, "entryids")
        if not wanted or not all(ENTRY_RE.fullmatch(e) for e in wanted):
            return reply(400)
        pl["entries"] = [e for e in pl["entries"] if e[0] not in wanted]
        record("RemoveFromPlaylist", pid, q, Removed=len(wanted))
        return reply(204)
    if len(low) == 6 and low[2] == "items" and low[4] == "move" and method == "POST":
        pl, pid = playlist_of(handler, segs, uid, True)
        if pl is None:
            return True
        if not ENTRY_RE.fullmatch(segs[3]) or not segs[5].isdigit():
            return reply(400)
        found = next((e for e in pl["entries"] if e[0] == segs[3]), None)
        if found is None:
            return reply(404)
        if int(segs[5]) >= len(pl["entries"]):
            return reply(400)
        pl["entries"].remove(found)
        pl["entries"].insert(int(segs[5]), found)
        record("MovePlaylistItem", pid, q, Entry=segs[3], NewIndex=int(segs[5]))
        return reply(204)
    return False


def collections(handler, method, segs, low, q, uid):
    reply = handler.reply
    if len(low) == 1 and method == "POST":
        if not may(uid, "EnableCollectionManagement"):
            return reply(403)
        name = clean_name(q.get("name", [""])[0])
        ids = ids_of(q, "ids") if q.get("ids") else []
        if name is None or ids is None:
            return reply(400)
        new = M.add({"Id": secrets.token_hex(16), "Type": "BoxSet", "Name": name, "IsFolder": True, "Aspect": 0.667})
        S["created"].append(new["Id"])
        S["collections"][new["Id"]] = [i for i in ids if i in M.ITEMS and M.visible(i)]
        record("CreateCollection", new["Id"], q, Name=name)
        return reply(200, {"Id": new["Id"]})
    if len(low) == 3 and low[2] == "items" and method in ("POST", "DELETE"):
        if not may(uid, "EnableCollectionManagement"):
            return reply(403)
        cid = M.norm(segs[1]) if ID_RE.fullmatch(segs[1]) else ""
        if cid not in S["collections"] or not M.visible(cid):
            return reply(404)
        ids = ids_of(q, "ids")
        if ids is None:
            return reply(400)
        members_now = S["collections"][cid]
        if method == "POST":
            members_now.extend(i for i in ids if i in M.ITEMS and M.visible(i) and i not in members_now)
        else:
            S["collections"][cid] = [i for i in members_now if i not in ids]
        record("AddToCollection" if method == "POST" else "RemoveFromCollection", cid, q)
        return reply(204)
    return False


def filters(q):
    rows = scope(M.norm(q.get("parentid", [""])[0]))
    return {"Genres": sorted({g for i in rows for g in genre_names(i)}), "Tags": sorted({t for i in rows for t in i.get("Tags", [])}),
            "OfficialRatings": sorted({i["Official"] for i in rows if i.get("Official")}), "Years": sorted({i["Year"] for i in rows if i.get("Year")})}


def genres_or_studios(kind, q):
    rows = scope(M.norm(q.get("parentid", [""])[0]))
    term = q.get("searchterm", [""])[0].lower()
    names = sorted({n for i in rows for n in (genre_names(i) if kind == "genre" else i.get("Studios", []))
                    if term in n.lower()}, key=str.lower)
    start, limit = M.intq(q, "startindex", 0), M.intq(q, "limit")
    shown = names[start:start + limit if limit is not None else None]
    items = [{"Name": n, "ServerId": M.SERVER_ID, "Id": named_id(kind, n), "Type": "Genre" if kind == "genre" else "Studio", "IsFolder": True,
              "ImageTags": {}, "BackdropImageTags": []} for n in shown]
    return {"Items": items, "TotalRecordCount": len(names), "StartIndex": start}


def subtitles(handler, method, item, language, q, uid):
    reply = handler.reply
    if not may(uid, "EnableSubtitleManagement"):
        return reply(403)
    if item is None or item["Type"] not in ("Movie", "Episode", "Video"):
        return reply(404)
    if method == "GET":
        if not LANG_RE.fullmatch(language):
            return reply(400)
        record("SearchSubtitles", item["Id"], q, Language=language)
        return reply(200, search_results(item, language, q.get("isperfectmatch", ["false"])[0].lower() == "true"))
    lang = S["offered"].get((item["Id"], language))
    if lang is None:
        return reply(404)
    S["added"].setdefault(item["Id"], []).append({"at": time.monotonic() + S["delay"], "lang": lang})
    record("DownloadSubtitle", item["Id"], q, SubtitleId=language)
    return reply(204)
