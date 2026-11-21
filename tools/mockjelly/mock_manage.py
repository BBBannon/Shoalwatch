"""Library management routes for the Jellyfin TEST STAND-IN (mock_jellyfin.py). NOT Jellyfin; never expose it.

Milestone 10: identify (remote search and apply), pictures (list, online choices, download, upload, remove), the metadata editor
and saving an item, deleting an item, with shapes from the 12.4.0 OpenAPI reference. Imports nothing from mock_jellyfin: its
catalogue and helpers come in through setup(). Prints nothing; what it received is kept only for GET /mock/manage-events.
Online picture addresses point at image.tmdb.org as Jellyfin's would; the stand-in never fetches them (it draws a colour instead),
and the app must never load them either.
"""
import base64
import binascii
import hashlib
import json

EVENTS = []  # management actions received, newest last: {"Kind", "Target", "Query"} (+ "Body" keys for a save)
S = {}  # rebuilt by setup()
KINDS = {"primary": "Primary", "backdrop": "Backdrop", "logo": "Logo", "thumb": "Thumb", "banner": "Banner"}
RATINGS = ["U", "PG", "12A", "15", "18"]


def setup(items, guid, norm, may_delete, png):
    """(Re)build the state. `items` is mock_jellyfin's catalogue (edited in place); `may_delete(uid)` reads a user's policy."""
    EVENTS.clear()
    S.clear()
    S.update(items=items, guid=guid, norm=norm, may_delete=may_delete, png=png, images={}, deleted=set())


def record(kind, target, q, **extra):
    EVENTS.append({"Kind": kind, "Target": target, "Query": {k: v[0] for k, v in q.items()}, **extra})


# ------------------------------------------------------------------ pictures
def stored(item_id):
    """The pictures an item has once anything was changed: {kind: [{"bytes", "type", "rev"}]}, or None (mock_jellyfin's own)."""
    return S.get("images", {}).get(item_id) if S else None


def _start(item):
    """The first change to an item's pictures copies mock_jellyfin's own ones, so they can be listed, replaced or removed."""
    item_id = item["Id"]
    have = S["images"].get(item_id)
    if have is None:
        have = {}
        if not item.get("NoImage"):
            have["primary"] = [{"bytes": None, "type": "image/png", "rev": 0}]
        if item.get("Backdrop"):
            have["backdrop"] = [{"bytes": None, "type": "image/png", "rev": 0}]
        S["images"][item_id] = have
    return have


def tag(item, kind, n, rev):
    return S["guid"](f"image-{item['Id']}-{kind}-{n}-{rev}")[:16]


def image_tags(item):
    """(ImageTags, BackdropImageTags) for dto(), or None when the item's pictures were never changed."""
    have = stored(item["Id"])
    if have is None:
        return None
    tags = {KINDS[k]: tag(item, k, 0, v[0]["rev"]) for k, v in have.items() if v and k != "backdrop"}
    backdrops = [tag(item, "backdrop", n, b["rev"]) for n, b in enumerate(have.get("backdrop", []))]
    return tags, backdrops


def image_bytes(item, kind, index):
    """(bytes, type) of a changed picture, "none" when it was removed, or None to let mock_jellyfin draw its own."""
    have = stored(item["Id"])
    if have is None:
        return None
    pics = have.get(kind.lower(), [])
    if index >= len(pics):
        return "none"
    pic = pics[index]
    return None if pic["bytes"] is None else (pic["bytes"], pic["type"])


def colour_for(url):
    return int(hashlib.sha256(url.encode()).hexdigest()[:6], 16)


# ------------------------------------------------------------------ routes
def handle(handler, method, path, q, raw, uid, admin):
    """Answer a management route (True), or leave the request to the others (False). The token is already one mock_jellyfin issued."""
    if not S:
        return False
    segs = path.strip("/").split("/")
    low = [x.lower() for x in segs]
    items = S["items"]
    if method == "GET" and low == ["mock", "manage-events"]:
        return handler.reply(200, list(EVENTS))
    if len(low) < 2 or low[0] != "items":
        return False

    def need_admin():
        return None if admin else handler.reply(403)

    if low[1] == "remotesearch":
        if method == "POST" and len(low) == 3 and low[2] in ("movie", "series"):
            body = body_of(raw)
            if body is None or not isinstance(body.get("SearchInfo"), dict):
                return handler.reply(400)
            return handler.reply(200, search(low[2], body))
        if method == "POST" and len(low) == 4 and low[2] == "apply":
            if need_admin():
                return True
            item = items.get(S["norm"](segs[3]))
            body = body_of(raw)
            if item is None or item["Id"] in S["deleted"]:
                return handler.reply(404)
            if body is None or not body.get("Name") or not isinstance(body.get("ProviderIds"), dict):
                return handler.reply(400)
            item["Name"] = body["Name"]
            if isinstance(body.get("ProductionYear"), int):
                item["Year"] = body["ProductionYear"]
            record("Apply", item["Id"], q, Name=body["Name"], ProviderIds=body["ProviderIds"])
            return handler.reply(204)
        return False
    item = items.get(S["norm"](segs[1]))
    if item is None or item["Id"] in S["deleted"]:
        return handler.reply(404) if len(low) <= 3 and low[1] != "latest" and len(segs[1]) >= 32 else False
    rest = low[2:]
    if method == "DELETE" and rest == []:
        if not S["may_delete"](uid):
            # Jellyfin's LibraryController.DeleteItem: `if (!item.CanDelete(user)) return Unauthorized(...)`, a 401, not a 403,
            # and CanDelete reads the deletion policy only (being an administrator does not imply it).
            return handler.reply(401)
        S["deleted"].add(item["Id"])
        record("DeleteItem", item["Id"], q)
        return handler.reply(204)
    if method == "POST" and rest == []:
        if need_admin():
            return True
        return save(handler, item, raw, q)
    if method == "GET" and rest == ["metadataeditor"]:
        if need_admin():
            return True
        return handler.reply(200, {"ParentalRatingOptions": [{"Name": r, "Value": n} for n, r in enumerate(RATINGS)],
                                   "Countries": [], "Cultures": [], "ExternalIdInfos": [], "ContentTypeOptions": []})
    if method == "GET" and rest == ["images"]:
        return handler.reply(200, list_images(item))
    if method == "GET" and rest == ["remoteimages"]:
        return handler.reply(200, remote_images(item, q))
    if method == "POST" and rest == ["remoteimages", "download"]:
        if need_admin():
            return True
        kind = q.get("type", [""])[0].lower()
        url = q.get("imageurl", [""])[0]
        if kind not in KINDS or not url.startswith("https://"):
            return handler.reply(400)
        put(item, kind, S["png"](colour_for(url), 400, 600 if kind == "primary" else 225), "image/png")
        record("DownloadImage", item["Id"], q)
        return handler.reply(204)
    if method == "POST" and len(rest) == 2 and rest[0] == "images" and rest[1] in KINDS:
        if need_admin():
            return True
        if handler.headers.get("Content-Type", "").split(";")[0].strip().lower() not in ("image/jpeg", "image/png", "image/webp"):
            return handler.reply(415)
        try:
            data = base64.b64decode(raw, validate=True)  # Jellyfin reads the body as base64 text
        except (binascii.Error, ValueError):
            return handler.reply(400)
        if not data.startswith(b"\xff\xd8\xff"):
            return handler.reply(400)
        put(item, rest[1], data, "image/jpeg")
        record("UploadImage", item["Id"], q, Kind=KINDS[rest[1]], Bytes=len(data))
        return handler.reply(204)
    if method == "DELETE" and len(rest) == 3 and rest[0] == "images" and rest[1] in KINDS and rest[2].isdigit():
        if need_admin():
            return True
        have = _start(item)
        pics = have.get(rest[1], [])
        n = int(rest[2])
        if n >= len(pics):
            return handler.reply(404)
        del pics[n]
        record("DeleteImage", item["Id"], q, Kind=KINDS[rest[1]], Index=n)
        return handler.reply(204)
    return False


def body_of(raw):
    try:
        body = json.loads(raw or b"{}")
    except ValueError:
        return None
    return body if isinstance(body, dict) else None


def put(item, kind, data, ctype):
    have = _start(item)
    pics = have.setdefault(kind, [])
    rev = max([p["rev"] for p in pics] + [0]) + 1
    entry = {"bytes": data, "type": ctype, "rev": rev}
    if pics:
        pics[0] = entry
    else:
        pics.append(entry)


def list_images(item):
    have = stored(item["Id"])
    rows = []
    if have is None:
        if not item.get("NoImage"):
            rows.append({"ImageType": "Primary", "ImageTag": "x", "Path": f"/srv/media/{item['Id']}/poster.jpg", "Width": 300, "Height": 450, "Size": 20000})
        if item.get("Backdrop"):
            rows.append({"ImageType": "Backdrop", "ImageIndex": 0, "ImageTag": "x", "Path": f"/srv/media/{item['Id']}/backdrop.jpg", "Width": 1000, "Height": 562, "Size": 40000})
        return rows
    for kind, pics in have.items():
        for n, p in enumerate(pics):
            row = {"ImageType": KINDS[kind], "ImageTag": tag(item, kind, n, p["rev"]), "Path": f"/srv/media/{item['Id']}/{kind}{n}.jpg",
                   "Width": 400, "Height": 600 if kind == "primary" else 225, "Size": len(p["bytes"] or b"") or 20000}
            if kind == "backdrop":
                row["ImageIndex"] = n
            rows.append(row)
    return rows


def remote_images(item, q):
    kind = KINDS.get(q.get("type", ["primary"])[0].lower(), "Primary")
    langs = ["en", "fr", None] if q.get("includealllanguages", ["false"])[0].lower() == "true" else ["en"]
    images = []
    for n in range(3):
        for lang in langs:
            images.append({"ProviderName": "TheMovieDb" if n < 2 else "Fanart", "Type": kind, "Language": lang,
                           "Url": f"https://image.tmdb.org/t/p/original/{item['Id'][:8]}-{kind.lower()}-{n}-{lang or 'none'}.jpg",
                           "ThumbnailUrl": f"https://image.tmdb.org/t/p/w300/{item['Id'][:8]}-{n}.jpg",
                           "Width": 1000 if kind == "Primary" else 1920, "Height": 1500 if kind == "Primary" else 1080,
                           "CommunityRating": round(7.5 - n * 0.6, 1), "VoteCount": 20 - n * 5, "RatingType": "Score"})
    return {"Images": images, "TotalRecordCount": len(images), "Providers": ["TheMovieDb", "Fanart"]}


def search(kind, body):
    info = body["SearchInfo"]
    name = str(info.get("Name") or "").strip() or "Untitled"
    year = info.get("Year") if isinstance(info.get("Year"), int) else 2001
    provider = "TheMovieDb"
    out = []
    for n, suffix in enumerate(["", " (Director's Cut)", " Returns"]):
        out.append({"Name": name + suffix, "ProductionYear": year + n, "PremiereDate": f"{year + n}-03-0{n + 1}T00:00:00.0000000Z",
                    "SearchProviderName": provider, "ProviderIds": {"Tmdb": str(1000 + n), "Imdb": f"tt00{1000 + n}"},
                    "ImageUrl": f"https://image.tmdb.org/t/p/original/match-{n}.jpg",
                    "Overview": f"A stand-in {'film' if kind == 'movie' else 'series'} match number {n + 1} for {name}."})
    return out


def save(handler, item, raw, q):
    body = body_of(raw)
    # Like Jellyfin, the whole item is needed: a body without its Id and Type is refused.
    if body is None or S["norm"](str(body.get("Id") or "")) != item["Id"] or not body.get("Type") or not body.get("Name"):
        return handler.reply(400)
    item["Name"] = body["Name"]
    for key, field in (("Overview", "Overview"), ("Year", "ProductionYear"), ("Rating", "CommunityRating"), ("Official", "OfficialRating")):
        if field in body:
            if body[field] is None:
                item.pop(key, None)
            else:
                item[key] = body[field]
    edited = item.setdefault("Edited", {})
    for field in ("ForcedSortName", "OriginalTitle", "PremiereDate", "Genres", "Tags", "Studios", "LockedFields", "LockData"):
        if field in body:
            edited[field] = body[field]
    record("SaveItem", item["Id"], q, Keys=sorted(body.keys()))
    return handler.reply(204)
