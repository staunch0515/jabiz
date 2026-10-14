#!/usr/bin/env python3
"""The API half of tools/quizbuks/local-demo.sh: the first-time setup and the demo data of a local QuizBuks.

Only the Python standard library. Every step is safe to repeat: what exists already is left as it is.

    demo.py setup   QB_SETUP, then the demo publisher publishes what it proposed (four eyes, platform decision D40)
    demo.py seed    an approved and funded sponsor with a live quiz, and a taker in Japan

Settings come from the environment (local-demo.sh exports them): QB_DEMO_BASE_URL, QB_DEMO_MAILPIT_URL,
JABIZ_BOOTSTRAP_ADMIN_USER / _PASSWORD, QB_DEMO_PASSWORD.
"""

import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

BASE = os.environ.get("QB_DEMO_BASE_URL", "http://localhost:8080").rstrip("/")
MAILPIT = os.environ.get("QB_DEMO_MAILPIT_URL", "http://localhost:8025").rstrip("/")
ADMIN_USER = os.environ.get("JABIZ_BOOTSTRAP_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.environ["JABIZ_BOOTSTRAP_ADMIN_PASSWORD"]
DEMO_PASSWORD = os.environ.get("QB_DEMO_PASSWORD", "quizbuks-demo-1")

# The demo's second administrator: holds `control.publish` only and asks for no second factor. local-demo.sh adds the
# role to QB_ADMIN_ROLES; never configure it on a reachable installation (the finance administrator publishes there).
PUBLISHER_ROLE = "QB_DEMO_PUBLISHER"
PUBLISHER_USER = "demo-publisher"
SPONSOR_EMAIL = "sponsor@quizbuks.local"
TAKER_EMAIL = "taker@quizbuks.local"

ROLES = "urn:jabiz:dataset:platform:SecRole"
ROLE_PERMISSIONS = "urn:jabiz:dataset:platform:SecRolePermission"
USER_ROLES = "urn:jabiz:dataset:platform:SecUserRole"
CONTROL_CHANGES = "urn:jabiz:dataset:platform:SysControlChange"
SPONSORS = "urn:jabiz:dataset:sponsor:QbSponsor"
TOPUPS = "urn:jabiz:dataset:sponsor:QbTopUp"
WALLETS = "urn:jabiz:dataset:sponsor:QbWallet"
PUBLICATIONS = "urn:jabiz:dataset:sponsor:QbPublication"


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def call(method, path, body=None, token=None, headers=None, raw=None, content_type="application/json"):
    url = path if path.startswith("http") else BASE + path
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", content_type)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            text = response.read().decode()
            if "json" not in (response.headers.get("Content-Type") or ""):
                return text
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read().decode()) from None


def login(user, password, entry="admin"):
    """An access token, or None when the account does not exist or signs in otherwise (a second factor)."""
    try:
        answer = call("POST", "/api/auth/login", {"userName": user, "password": password, "entry": entry})
    except ApiError as error:
        if error.status in (401, 403):
            return None
        raise
    return answer.get("accessToken")


def run(token, process, data):
    return call("POST", f"/api/processes/{process}/latest", data, token,
                {"Idempotency-Key": str(uuid.uuid4())})["output"]


def rows(token, dataset, filters=(), limit=100):
    answer = call("POST", f"/api/datasets/{urllib.parse.quote(dataset, safe='')}/query",
                  {"filters": list(filters), "limit": limit}, token)
    return answer.get("items") or []


def insert(token, dataset, attributes):
    return call("POST", f"/api/datasets/{urllib.parse.quote(dataset, safe='')}/commit",
                {"changes": [{"action": "INSERT", "attributes": attributes}]}, token)[0]


def query(token, template, params):
    return call("POST", f"/api/queries/{urllib.parse.quote(template, safe='')}", {"params": params, "limit": 100},
                token).get("items") or []


def wait_for(what, check, timeout=60):
    deadline = time.time() + timeout
    while True:
        value = check()
        if value:
            return value
        if time.time() > deadline:
            sys.exit(f"local-demo: gave up waiting for {what}")
        time.sleep(1)


def admin_token():
    token = login(ADMIN_USER, ADMIN_PASSWORD)
    if not token:
        sys.exit(f"local-demo: the administrator {ADMIN_USER} cannot sign in (password in the demo's env file?)")
    return token


def publisher_token(admin):
    token = login(PUBLISHER_USER, DEMO_PASSWORD)
    if token:
        return token
    found = rows(admin, ROLES, [{"field": "roleCode", "op": "eq", "value": PUBLISHER_ROLE}], 1)
    role_id = found[0]["id"] if found else None
    if not role_id:
        role_id = insert(admin, ROLES, {"roleCode": PUBLISHER_ROLE, "labels": {"en": "Demo publisher"},
                                        "enabled": True})["id"]
        insert(admin, ROLE_PERMISSIONS, {"roleId": role_id, "permission": "control.publish"})
    user_id = run(admin, "SEC_USER_CREATE", {"userName": PUBLISHER_USER, "displayName": "Demo publisher",
                                             "password": DEMO_PASSWORD})["userId"]
    insert(admin, USER_ROLES, {"userId": user_id, "roleId": role_id})
    return login(PUBLISHER_USER, DEMO_PASSWORD)


def setup():
    admin = admin_token()
    made = run(admin, "QB_SETUP", {})
    print(f"  QB_SETUP: {len(made.get('rolesCreated') or [])} roles, {made.get('countriesAdded', 0)} countries, "
          f"{len(made.get('paramsProposed') or []) + len(made.get('rulesProposed') or [])} proposals")
    proposed = rows(admin, CONTROL_CHANGES, [{"field": "status", "op": "eq", "value": "PROPOSED"}])
    if proposed:
        publisher = publisher_token(admin)
        for change in proposed:
            run(publisher, "CONTROL_CHANGE_PUBLISH", {"changeId": change["id"]})
    print(f"  {len(proposed)} proposals published by {PUBLISHER_USER}")


def verified_user(entry, email, name):
    """Registers at the entry and opens the mailed link, as the person would; nothing when the address exists."""
    token = login(email, DEMO_PASSWORD, entry)
    if token:
        return token
    call("POST", "/api/auth/register", {"email": email, "password": DEMO_PASSWORD, "displayName": name,
                                        "locale": "en", "entry": entry})

    def mail():
        found = call("GET", f"{MAILPIT}/api/v1/search?query={urllib.parse.quote(f'to:{email}')}")
        for message in found.get("messages") or []:
            if re.search(r"verify|confirm|address", message["Subject"], re.I):
                return message["ID"]
        return None

    text = call("GET", f"{MAILPIT}/api/v1/message/{wait_for(f'the mail to {email}', mail)}")["Text"]
    link = next(m for m in re.findall(r"https?://[^\s<>\"')\]]+", text) if "/auth/verify-email" in m)
    verification = urllib.parse.parse_qs(urllib.parse.urlparse(link.replace("&amp;", "&")).query)["token"][0]
    check = call("POST", "/api/auth/verify-email/check", {"token": verification})
    body = {"token": verification, "password": DEMO_PASSWORD} if check.get("passwordRequired") else {
        "token": verification}
    call("POST", "/api/auth/verify-email", body)
    return login(email, DEMO_PASSWORD, entry)


def png(width=640, height=360):
    """A small PNG cover: a diagonal blend of two colours."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            t = (x + y) / (width + height)
            raw += bytes((int(37 + 180 * t), int(99 + 60 * t), int(235 - 120 * t)))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def upload(token, policy, name, content, mime):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            f"Content-Type: {mime}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return call("POST", f"/api/files?policy={urllib.parse.quote(policy)}", token=token, raw=body,
                content_type=f"multipart/form-data; boundary={boundary}")["fileId"]


QUESTIONS = [
    ("What is the capital of Japan?", ["Tokyo", "Kyoto", "Osaka"]),
    ("What is the capital of Australia?", ["Canberra", "Sydney", "Melbourne"]),
    ("What is the capital of Canada?", ["Ottawa", "Toronto", "Vancouver"]),
]


def seed():
    admin = admin_token()
    sponsor = verified_user("sponsor", SPONSOR_EMAIL, "Sam Sponsor")
    mine = rows(sponsor, SPONSORS)
    if not mine or mine[0]["attributes"].get("reviewStatus") != "APPROVED":
        applied = run(sponsor, "QB_SPONSOR_APPLY", {"displayName": "Sam Sponsor", "company": "QuizBuks Demo Co",
                                                     "acceptTerms": True})
        if applied.get("approval") == "PENDING":
            run(admin, "APPROVAL_DECIDE", {"requestId": applied["approvalRequestId"], "decision": "APPROVE"})
        wait_for("the sponsor's approval",
                 lambda: rows(sponsor, SPONSORS)[0]["attributes"].get("reviewStatus") == "APPROVED")
    print(f"  sponsor {SPONSOR_EMAIL} approved")

    balance = lambda: int(float((rows(sponsor, WALLETS) or [{"attributes": {}}])[0]["attributes"].get("balance") or 0))
    if balance() < 5000:
        top_up = run(sponsor, "QB_TOPUP_START", {"amount": 10000})["topUpId"]

        def checkout():
            for row in rows(sponsor, TOPUPS):
                if row["id"] == top_up:
                    return row["attributes"].get("checkoutUrl")
            return None

        # The Stripe stub's checkout page, paid as its Pay button does: the stub posts the signed event back.
        call("POST", wait_for("the checkout", checkout) + "/paid", raw=b"", content_type="text/plain")
        wait_for("the top-up to be credited", lambda: balance() >= 10000)
    print(f"  sponsor wallet: {balance():,} Kudos")

    if not [p for p in rows(sponsor, PUBLICATIONS) if p["attributes"].get("status") == "LIVE"]:
        cover = upload(sponsor, "qb.content.image", "cover.png", png(), "image/png")
        quiz = run(sponsor, "QB_QUIZ_SAVE", {"title": "World capitals", "cover": cover, "timeLimitSec": 300,
                                             "intro": "Three questions about **capital cities**. Score 2 or more "
                                                      "to win Kudos."})["quizId"]
        for stem, (right, *wrong) in QUESTIONS:
            run(sponsor, "QB_QUESTION_SAVE", {"quizId": quiz, "stem": stem, "points": 1, "options":
                [{"text": right, "correct": True}] + [{"text": w, "correct": False} for w in wrong]})
        run(sponsor, "QB_MATERIAL_SAVE", {"quizId": quiz, "kind": "ARTICLE", "title": "Before you start",
                                          "description": "A hint.", "body": "# Hint\n\nAll three are *not* the "
                                                                            "largest city of their country."})
        run(sponsor, "QB_QUIZ_PUBLISH_VERSION", {"quizId": quiz})
        version = query(sponsor, "qb.sponsor.quiz-versions", {"quizId": quiz})[0]["versionId"]
        now = time.time()
        stamp = lambda t: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))
        publication = run(sponsor, "QB_PUBLICATION_SAVE", {
            "versionId": version, "name": "World capitals", "regions": ["GLOBAL"],
            "startAt": stamp(now - 60), "endAt": stamp(now + 30 * 86400), "passScore": 2, "budgetCap": 5000,
            "maxWinners": 50, "tiers": [{"fromScore": 0, "toScore": 1, "reward": 0},
                                        {"fromScore": 2, "toScore": 2, "reward": 50},
                                        {"fromScore": 3, "toScore": 3, "reward": 100}]})["publicationId"]
        run(sponsor, "QB_PUBLICATION_SUBMIT", {"publicationId": publication})
        path = f"/api/datasets/{urllib.parse.quote(PUBLICATIONS, safe='')}/entities/{publication}"
        request_id = call("GET", path, token=sponsor)["attributes"]["approvalRequestId"]
        run(admin, "APPROVAL_DECIDE", {"requestId": request_id, "decision": "APPROVE"})
        wait_for("the publication to go live",
                 lambda: call("GET", path, token=sponsor)["attributes"].get("status") == "LIVE")
    print("  publication \"World capitals\" is live")

    taker = verified_user("app", TAKER_EMAIL, "Taro Taker")
    run(taker, "QB_PROFILE_SAVE", {"displayName": "Taro Taker", "countryCode": "JP", "preferredCurrency": "JPY"})
    print(f"  taker {TAKER_EMAIL} ready")


if __name__ == "__main__":
    {"setup": setup, "seed": seed}[sys.argv[1]]()
