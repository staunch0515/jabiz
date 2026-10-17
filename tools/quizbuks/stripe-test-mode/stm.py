#!/usr/bin/env python3
"""The API half of tools/quizbuks/stripe-test-mode/run.sh: QuizBuks's money flow against Stripe's test mode.

Runbook: docs/quizbuks/stripe-test-mode.md. Only the Python standard library.

    stm.py guard                 the offline safety checks only (no network); exit 2 when refused
    stm.py run [options]         the whole flow, verified step by step through the API; a summary at the end
    stm.py payout-check PO_ID    whether the application recorded Stripe's payout (payout.paid) and its posting

The flow drives a running QuizBuks (STRIPE_TM_BASE_URL, default http://localhost:8080) that uses the same Stripe
account in test mode as STRIPE_TEST_SECRET_KEY, with Stripe's events forwarded to it (stripe listen, or endpoints):
setup, sponsor, top-up (Checkout, test card), quiz played and rewarded, Connect Express onboarding, the automatic
transfer, one refund, one dispute (test card 4000 0000 0000 0259, closed with Stripe's winning evidence) and one payout
of the platform's balance (payout.paid). After each step it checks the application's records and the ledger.

With --stub it runs against the local Stripe stub instead (tools/quizbuks/stripe-stub; no Stripe key, nothing leaves
this machine): the dispute and the payout are skipped, since the stub has neither. That is the dry run.

Safety (guard, before anything else): the key must be a secret test key (sk_test_); the target must be this machine
unless --allow-remote, never a host listed as production (production-hosts beside this file, or
STRIPE_TM_PRODUCTION_HOSTS), and https when remote; the live-mode switch must not be on here. Once connected, the
application must report test mode: its checkout is a cs_test_ session that this key can read, with livemode false,
and none of its top-ups is a live one. Secrets (keys, passwords, tokens, TOTP seeds, links that sign in) are never
printed: every line goes through redact().
"""

import argparse
import base64
import datetime
import hashlib
import hmac
import ipaddress
import json
import math
import os
import re
import secrets
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
STRIPE_API = "https://api.stripe.com"
CHECKOUT_PREFIX = "https://checkout.stripe.com/"
DEFAULT_BASE = "http://localhost:8080"

STEPS = ["setup", "sponsor", "topup", "quiz", "onboarding", "transfer", "refund", "dispute", "payout"]

# Stripe's test cards and payment methods (https://docs.stripe.com/testing).
CARD_SUCCESS = "4242 4242 4242 4242"
CARD_DISPUTE = "4000 0000 0000 0259"  # succeeds, then a fraudulent dispute is opened
PM_BYPASS_PENDING = "pm_card_bypassPending"  # funds go straight to the available balance
WINNING_EVIDENCE = "winning_evidence"

SPONSOR_DATASET = "urn:jabiz:dataset:sponsor:QbSponsor"
SPONSOR_WALLETS = "urn:jabiz:dataset:sponsor:QbWallet"
SPONSOR_TOPUPS = "urn:jabiz:dataset:sponsor:QbTopUp"
MY_PAYOUT_ACCOUNT = "urn:jabiz:dataset:taker:QbPayoutAccount"
MY_ONBOARDING = "urn:jabiz:dataset:taker:QbPayoutOnboarding"
TOPUPS = "urn:jabiz:dataset:default:QbTopUp"
TRANSFERS = "urn:jabiz:dataset:default:QbTransfer"
PAYOUT_ACCOUNTS = "urn:jabiz:dataset:default:QbPayoutAccount"
STRIPE_PAYOUTS = "urn:jabiz:dataset:default:QbStripePayout"
PARAMS = "urn:jabiz:dataset:platform:SysParam"
CONTROL_CHANGES = "urn:jabiz:dataset:platform:SysControlChange"
ROLES = "urn:jabiz:dataset:platform:SecRole"
ROLE_PERMISSIONS = "urn:jabiz:dataset:platform:SecRolePermission"
USER_ROLES = "urn:jabiz:dataset:platform:SecUserRole"
LEDGER_ACCOUNTS = "urn:jabiz:dataset:platform:LedgerAccount"
LEDGER_TRANSACTIONS = "urn:jabiz:dataset:platform:LedgerTransaction"
LEDGER_ENTRIES = "urn:jabiz:dataset:platform:LedgerEntry"

# The local publisher that run.sh serve lets the administration accept (loopback targets only; never elsewhere).
LOCAL_PUBLISHER_ROLE = "QB_STM_PUBLISHER"
LOCAL_PUBLISHER_USER = "stm-publisher"

_SECRETS = set()


class Refused(Exception):
    """A safety guard refused the run."""


class Failed(Exception):
    """A step's check did not hold."""


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status
        self.body = body


def secret(value):
    """Registers a value that must never be printed; returns it."""
    if value and len(value) >= 6:
        _SECRETS.add(value)
    return value


def redact(text):
    text = str(text)
    for value in sorted(_SECRETS, key=len, reverse=True):
        text = text.replace(value, "***")
    # Whatever looks like a key, signing secret or bearer token, even if it was never registered.
    text = re.sub(r"\b(sk|rk|pk)_(test|live)_[A-Za-z0-9]+", r"\1_\2_***", text)
    text = re.sub(r"\bwhsec_[A-Za-z0-9]+", "whsec_***", text)
    text = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "<token>", text)
    return text


def say(*parts):
    print(redact(" ".join(str(p) for p in parts)), flush=True)


# ---- configuration and the guard ------------------------------------------------------------------------------------

def env(name, default=None):
    value = os.environ.get(name)
    return value if value not in (None, "") else default


def truthy(value):
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def production_hosts():
    """Hosts this script never drives: the production-hosts file beside it and STRIPE_TM_PRODUCTION_HOSTS."""
    hosts = []
    path = env("STRIPE_TM_PRODUCTION_HOSTS_FILE", os.path.join(HERE, "production-hosts"))
    if os.path.exists(path):
        with open(path, encoding="utf-8") as lines:
            for line in lines:
                line = line.split("#", 1)[0].strip()
                if line:
                    hosts.append(line.lower())
    hosts += [h.strip().lower() for h in env("STRIPE_TM_PRODUCTION_HOSTS", "").split(",") if h.strip()]
    return hosts


def is_production(host, hosts):
    host = host.lower().rstrip(".")
    for entry in hosts:
        if entry.startswith("*."):
            if host == entry[2:] or host.endswith(entry[1:]):
                return True
        elif host == entry:
            return True
    return False


def is_loopback(host):
    """Only literal loopback names and addresses: no DNS, which could answer anything."""
    host = host.lower().strip("[]")
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def guard(args):
    """The offline checks; raises Refused with every problem at once."""
    problems = []
    base = args.base_url
    parsed = urllib.parse.urlsplit(base)
    host = parsed.hostname or ""
    if parsed.scheme not in ("http", "https") or not host:
        problems.append(f"the base URL {base!r} is not an http(s) address")
    elif parsed.username or parsed.password or parsed.query or parsed.fragment:
        problems.append("the base URL must not carry credentials, a query or a fragment")
    else:
        if is_production(host, production_hosts()):
            problems.append(f"{host} is listed as a production host: this script never drives production")
        if not is_loopback(host):
            if not args.allow_remote:
                problems.append(f"{host} is not this machine: pass --allow-remote (or STRIPE_TM_ALLOW_REMOTE=yes) "
                                "to drive a remote test installation")
            elif parsed.scheme != "https":
                problems.append(f"a remote target must be https (tokens and passwords cross the network): {base}")
            if args.stub:
                problems.append("--stub only drives this machine (the stub listens on loopback only)")
    key = env("STRIPE_TEST_SECRET_KEY", "")
    secret(key)
    if re.match(r"(sk|rk)_live_", key):
        problems.append("STRIPE_TEST_SECRET_KEY is a LIVE key: refused (test mode only, sk_test_)")
    elif args.stub:
        pass  # nothing reaches Stripe; any test key is ignored
    elif not key:
        problems.append("STRIPE_TEST_SECRET_KEY is not set (a secret test key, sk_test_...)")
    elif key.startswith("rk_test_"):
        problems.append("STRIPE_TEST_SECRET_KEY is a restricted key: the script refunds, disputes and pays out, so "
                        "it needs the secret test key (sk_test_...)")
    elif not re.fullmatch(r"sk_test_[A-Za-z0-9]{8,}", key):
        problems.append("STRIPE_TEST_SECRET_KEY is not a secret test key (sk_test_...)")
    for name in ("QB_STRIPE_LIVEMODE", "QUIZBUKS_STRIPE_LIVEMODE"):
        if env(name) is not None and not re.fullmatch(r"(?i)false", env(name)):
            problems.append(f"{name}={env(name)} in this environment: live mode is never driven by this script")
    if args.stub:
        stub = urllib.parse.urlsplit(args.stub_url)
        if not is_loopback(stub.hostname or ""):
            problems.append(f"the stub URL {args.stub_url} is not this machine")
    if not env("STRIPE_TM_ADMIN_PASSWORD"):
        problems.append("STRIPE_TM_ADMIN_PASSWORD is not set (the administrator the script signs in as)")
    if problems:
        raise Refused("; ".join(problems))


# ---- HTTP ------------------------------------------------------------------------------------------------------------

def http(method, url, body=None, token=None, headers=None, raw=None, content_type="application/json", timeout=60):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", content_type)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode()
            if "json" not in (response.headers.get("Content-Type") or ""):
                return text
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read().decode(errors="replace")) from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def post_no_redirect(url):
    """A form button of the stub: it answers 303 to the browser's next page, which is not followed."""
    opener = urllib.request.build_opener(NoRedirect)
    try:
        opener.open(urllib.request.Request(url, data=b"", method="POST"), timeout=60)
    except urllib.error.HTTPError as error:
        if error.code >= 400:
            raise ApiError(error.code, error.read().decode(errors="replace")) from None


def totp(seed, at=None):
    """RFC 6238 (HMAC-SHA1, 6 digits, 30 s), as the platform's authenticator setup."""
    key = base64.b32decode(seed.replace(" ", "").upper() + "=" * (-len(seed.replace(" ", "")) % 8))
    step = int((at or time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}", step


class Session:
    """A signed-in user; signs in again before the access token gets old (waits can last many minutes)."""

    MAX_AGE = 600

    def __init__(self, app, user, password, entry="admin", seed=None):
        self.app, self.user, self.password, self.entry, self.seed = app, user, secret(password), entry, secret(seed)
        self._token, self._at, self._step = None, 0, None

    def token(self):
        if self._token and time.time() - self._at < self.MAX_AGE:
            return self._token
        answer = self.app.call("POST", "/api/auth/login", {"userName": self.user, "password": self.password,
                                                           "entry": self.entry})
        if answer.get("status") == "MFA_REQUIRED":
            if not self.seed:
                raise Failed(f"{self.user} has a second factor: set its TOTP seed (see the runbook) or use an "
                             "installation started with JABIZ_SECURITY_MFA_ADMINISTRATION=false (test only)")
            code, step = totp(self.seed)
            while step == self._step:  # a code is accepted once: wait for the next step
                time.sleep(2)
                code, step = totp(self.seed)
            self._step = step
            secret(code)
            answer = self.app.call("POST", "/api/auth/challenge/verify", {"challenge": secret(answer["challenge"]),
                                                                          "code": code})
        elif answer.get("status") == "MFA_ENROLLMENT_REQUIRED":
            raise Failed(f"{self.user} must enrol a second factor first (administration sign-in)")
        token = answer.get("accessToken")
        if not token:
            raise Failed(f"{self.user} could not sign in at {self.entry}: {answer.get('status')}")
        self._token, self._at = secret(token), time.time()
        return token

    def subject(self):
        claims = self.token().split(".")[1]
        return json.loads(base64.urlsafe_b64decode(claims + "=" * (-len(claims) % 4)))["sub"]


class App:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None, token=None, headers=None, **kwargs):
        url = path if path.startswith("http") else self.base + path
        return http(method, url, body, token, headers, **kwargs)

    def run(self, session, process, data):
        return self.call("POST", f"/api/processes/{process}/latest", data, session.token(),
                         {"Idempotency-Key": str(uuid.uuid4())})["output"]

    def rows(self, session, dataset, filters=(), limit=100, sorts=None):
        body = {"filters": list(filters), "limit": limit}
        if sorts:
            body["sorts"] = sorts
        return self.call("POST", f"/api/datasets/{urllib.parse.quote(dataset, safe='')}/query", body,
                         session.token()).get("items") or []

    def entity(self, session, dataset, entity_id):
        return self.call("GET", f"/api/datasets/{urllib.parse.quote(dataset, safe='')}/entities/{entity_id}",
                         token=session.token())["attributes"]

    def reveal(self, session, dataset, entity_id, field):
        """A masked field in plain text (recorded by the platform as a display, sys_reveal_record)."""
        return self.call("POST", f"/api/datasets/{urllib.parse.quote(dataset, safe='')}/reveal",
                         {"id": entity_id, "field": field}, session.token())["value"]

    def insert(self, session, dataset, attributes):
        return self.call("POST", f"/api/datasets/{urllib.parse.quote(dataset, safe='')}/commit",
                         {"changes": [{"action": "INSERT", "attributes": attributes}]}, session.token())[0]

    def query(self, session, template, params=None, filters=(), limit=100):
        return self.call("POST", f"/api/queries/{urllib.parse.quote(template, safe='')}",
                         {"params": params or {}, "filters": list(filters), "limit": limit},
                         session.token()).get("items") or []


class Stripe:
    """Stripe's API with the test key: form-encoded, version pinned as the application pins it."""

    def __init__(self, key, version):
        self.key, self.version = secret(key), version

    def call(self, method, path, form=None, idempotency=None):
        headers = {"Authorization": f"Bearer {self.key}", "Stripe-Version": self.version}
        if idempotency:
            headers["Idempotency-Key"] = idempotency
        raw = urllib.parse.urlencode(form or {}).encode() if method == "POST" else None
        return http(method, STRIPE_API + path, headers=headers, raw=raw,
                    content_type="application/x-www-form-urlencoded")

    def available(self, currency="jpy"):
        balance = self.call("GET", "/v1/balance")
        if balance.get("livemode"):
            raise Refused("Stripe answered the balance in live mode: refused")
        return sum(b["amount"] for b in balance.get("available", []) if b.get("currency") == currency)


def wait_for(what, check, timeout, every=3):
    deadline = time.time() + timeout
    while True:
        value = check()
        if value:
            return value
        if time.time() > deadline:
            raise Failed(f"gave up waiting for {what} after {timeout} s")
        time.sleep(every)


def iso(text):
    """An instant as the API writes it (ISO 8601, Z or an offset)."""
    return datetime.datetime.fromisoformat(str(text).replace("Z", "+00:00"))


def money(value):
    return int(round(float(value or 0)))


# ---- the run ---------------------------------------------------------------------------------------------------------

class Run:
    def __init__(self, args):
        self.args = args
        self.app = App(args.base_url)
        self.stripe = None if args.stub else Stripe(env("STRIPE_TEST_SECRET_KEY"),
                                                     env("QB_STRIPE_API_VERSION", "2025-03-31.basil"))
        self.id = time.strftime("%Y%m%d-%H%M%S")
        self.started = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)
        self.results = []
        self.facts = {}
        self.admin = Session(self.app, env("STRIPE_TM_ADMIN_USER", "admin"), env("STRIPE_TM_ADMIN_PASSWORD"),
                             seed=env("STRIPE_TM_ADMIN_TOTP"))
        self.publisher = None
        self.sponsor = None
        self.taker = None
        self.accounts = None

    # -- bookkeeping --

    def record(self, step, status, detail):
        self.results.append({"step": step, "status": status, "detail": redact(detail)})
        say(f"  -> {status}: {detail}")

    def fact(self, name, value):
        self.facts[name] = value
        return value

    def interactive(self, prompt):
        """Shows what a person must do in a browser (or hands it to the --browser helper)."""
        say(prompt)

    def browser(self, action, url, extra=None):
        """Runs the browser helper (unattended runs); False when there is none."""
        if not self.args.browser:
            return False
        command = self.args.browser.split() + [action, url] + ([extra] if extra else [])
        say(f"  (browser helper: {action})")
        done = subprocess.run(command, timeout=600)
        if done.returncode != 0:
            raise Failed(f"the browser helper failed at {action} (exit {done.returncode})")
        return True

    # -- the ledger --

    def account_codes(self):
        if self.accounts is None:
            self.accounts = {r["id"]: r["attributes"]["accountCode"] for r in
                             self.app.rows(self.admin, LEDGER_ACCOUNTS, limit=500)}
        return self.accounts

    def postings(self, source_entity, source_id):
        """The transactions of a document, each as {description, reverses, nets: {account: debit - credit}}."""
        found = []
        for tx in self.app.rows(self.admin, LEDGER_TRANSACTIONS, [
                {"field": "sourceEntity", "op": "eq", "value": source_entity},
                {"field": "sourceId", "op": "eq", "value": str(source_id)}]):
            entries = self.app.rows(self.admin, LEDGER_ENTRIES, [{"field": "transactionId", "op": "eq",
                                                                   "value": tx["id"]}])
            nets, debit, credit = {}, 0, 0
            for e in entries:
                a = e["attributes"]
                code = self.account_codes().get(a["accountId"], a["accountId"])
                amount = money(a["amount"])
                signed = amount if a["direction"] == "DEBIT" else -amount
                nets[code] = nets.get(code, 0) + signed
                debit += amount if a["direction"] == "DEBIT" else 0
                credit += amount if a["direction"] == "CREDIT" else 0
            if debit != credit:
                raise Failed(f"ledger transaction {tx['id']} does not balance ({debit} / {credit})")
            found.append({"id": tx["id"], "description": tx["attributes"].get("description"),
                          "reverses": tx["attributes"].get("reversesTransactionId"), "nets": nets})
        return found

    def expect_posting(self, source_entity, source_id, description, nets):
        """Exactly one transaction of the document with this description, and with these account nets."""
        matching = [p for p in self.postings(source_entity, source_id) if p["description"] == description]
        if len(matching) != 1:
            raise Failed(f"expected one '{description}' posting of {source_entity} {source_id}, "
                         f"found {len(matching)}")
        got = {k: v for k, v in matching[0]["nets"].items() if v != 0}
        want = {k: v for k, v in nets.items() if v != 0}
        if got != want:
            raise Failed(f"'{description}' of {source_entity} {source_id}: ledger {got}, expected {want}")
        return matching[0]

    # -- helpers --

    def param(self, key):
        found = self.app.rows(self.admin, PARAMS, [{"field": "paramKey", "op": "eq", "value": key}], 1)
        return found[0]["attributes"]["value"] if found else None

    def sponsor_wallet(self):
        rows = self.app.rows(self.sponsor, SPONSOR_WALLETS)
        a = rows[0]["attributes"] if rows else {}
        return money(a.get("balance")), money(a.get("held"))

    def taker_available(self):
        rows = self.app.query(self.taker, "qb.app.wallet")
        return money(rows[0]["available"]) if rows else 0

    def topup(self, topup_id):
        return self.app.entity(self.admin, TOPUPS, topup_id)

    def publish_proposals(self, ids, started):
        """Publishes only the given proposals, proposed by this run's administrator since the run began."""
        if not ids:
            return 0
        if not self.publisher:
            raise Failed(f"{len(ids)} controlled change(s) wait to be published and no second administrator is "
                         "set (STRIPE_TM_PUBLISHER_USER / _PASSWORD): publish them in the administration and run "
                         "again")
        mine = self.admin.subject()
        pending = {r["id"]: r for r in self.app.rows(self.admin, CONTROL_CHANGES, [
            {"field": "status", "op": "eq", "value": "PROPOSED"},
            {"field": "proposedBy", "op": "eq", "value": mine}], 1000)}
        missing = [i for i in ids if i not in pending]
        if missing:
            raise Failed(f"proposals {missing} are not pending from {mine}: refusing to publish anything")
        for change_id in ids:
            self.app.run(self.publisher, "CONTROL_CHANGE_PUBLISH", {"changeId": change_id})
        return len(ids)

    def set_param(self, key, value, started):
        change = self.app.run(self.admin, "CONTROL_CHANGE_PROPOSE", {
            "targetEntity": "SysParam", "values": {"paramKey": key, "value": value},
            "reason": f"Stripe test-mode run {self.id}"})
        self.publish_proposals([change["changeId"]], started)

    def local_publisher(self):
        """run.sh serve's second administrator (loopback only): control.publish alone, made once."""
        password = env("STRIPE_TM_PUBLISHER_PASSWORD")
        if not password:
            return None
        session = Session(self.app, LOCAL_PUBLISHER_USER, password)
        try:
            session.token()
            return session
        except (ApiError, Failed):
            pass
        found = self.app.rows(self.admin, ROLES, [{"field": "roleCode", "op": "eq", "value": LOCAL_PUBLISHER_ROLE}],
                              1)
        role_id = found[0]["id"] if found else None
        if not role_id:
            role_id = self.app.insert(self.admin, ROLES, {"roleCode": LOCAL_PUBLISHER_ROLE,
                                                         "labels": {"en": "Stripe test-mode publisher"},
                                                         "enabled": True})["id"]
            self.app.insert(self.admin, ROLE_PERMISSIONS, {"roleId": role_id, "permission": "control.publish"})
        user_id = self.app.run(self.admin, "SEC_USER_CREATE", {"userName": LOCAL_PUBLISHER_USER,
                                                               "displayName": "Stripe test-mode publisher",
                                                               "password": password})["userId"]
        self.app.insert(self.admin, USER_ROLES, {"userId": user_id, "roleId": role_id})
        return session

    def user(self, entry, kind, name):
        """A verified user: the given account, or a new one verified through Mailpit."""
        email, password = env(f"STRIPE_TM_{kind}_EMAIL"), env(f"STRIPE_TM_{kind}_PASSWORD")
        if email and password:
            return Session(self.app, email, password, entry)
        mailpit = env("STRIPE_TM_MAILPIT_URL")
        if not mailpit:
            raise Failed(f"no {kind.lower()} account: set STRIPE_TM_{kind}_EMAIL / _PASSWORD (a verified account "
                         f"at the {entry} entry) or STRIPE_TM_MAILPIT_URL to register a new one")
        email = f"stm-{kind.lower()}-{self.id}-{secrets.token_hex(3)}@quizbuks.test"
        password = secret(secrets.token_urlsafe(18))
        self.app.call("POST", "/api/auth/register", {"email": email, "password": password, "displayName": name,
                                                     "locale": "en", "entry": entry})

        def mail():
            found = http("GET", f"{mailpit.rstrip('/')}/api/v1/search?query="
                         + urllib.parse.quote(f'to:"{email}"'))
            for message in found.get("messages") or []:
                if re.search(r"verify|confirm|address", message["Subject"], re.I):
                    return message["ID"]
            return None

        text = http("GET", f"{mailpit.rstrip('/')}/api/v1/message/{wait_for('the verification mail', mail, 60)}")
        link = next(m for m in re.findall(r"https?://[^\s<>\"')\]]+", text["Text"]) if "/auth/verify-email" in m)
        token = secret(urllib.parse.parse_qs(urllib.parse.urlsplit(link.replace("&amp;", "&")).query)["token"][0])
        check = self.app.call("POST", "/api/auth/verify-email/check", {"token": token})
        body = {"token": token, "password": password} if check.get("passwordRequired") else {"token": token}
        self.app.call("POST", "/api/auth/verify-email", body)
        self.fact(f"{kind.lower()}Email", email)
        return Session(self.app, email, password, entry)

    # -- steps --

    def step_setup(self):
        started = time.time()
        if self.args.stub or is_loopback(urllib.parse.urlsplit(self.args.base_url).hostname or ""):
            self.publisher = self.local_publisher()
        if not self.publisher and env("STRIPE_TM_PUBLISHER_USER"):
            self.publisher = Session(self.app, env("STRIPE_TM_PUBLISHER_USER"), env("STRIPE_TM_PUBLISHER_PASSWORD"),
                                     seed=env("STRIPE_TM_PUBLISHER_TOTP"))
        made = self.app.run(self.admin, "QB_SETUP", {})
        published = self.publish_proposals(made.get("proposals") or [], started)
        live = [r for r in self.app.query(self.admin, "qb.admin.topups", limit=500) if r.get("livemode")]
        if live:
            raise Refused(f"the application has {len(live)} live top-up(s): this is not a test installation")
        changed = []
        if str(self.param("qb.transfer.enabled")).lower() != "true":
            self.set_param("qb.transfer.enabled", "true", started)
            changed.append("qb.transfer.enabled=true")
        threshold = money(self.param("qb.transfer.threshold"))
        review_above = money(self.param("qb.transfer.review-above"))
        rate = float(self.param("qb.creator-fee-rate") or 0)
        fee = money(self.param("qb.transfer.fee"))
        self.fact("threshold", threshold)
        self.fact("reviewAbove", review_above)
        self.fact("feeRate", rate)
        self.fact("transferFee", fee)
        self.record("setup", "PASS", f"QB_SETUP ({published} proposal(s) published), transfers on"
                    + (f" (published {', '.join(changed)})" if changed else "")
                    + f"; threshold {threshold}, review above {review_above}, commission {rate}, fee {fee}")

    def step_sponsor(self):
        self.sponsor = self.user("sponsor", "SPONSOR", "Stripe Test Sponsor")
        mine = self.app.rows(self.sponsor, SPONSOR_DATASET)
        if not mine or mine[0]["attributes"].get("reviewStatus") != "APPROVED":
            applied = self.app.run(self.sponsor, "QB_SPONSOR_APPLY", {
                "displayName": "Stripe Test Sponsor", "company": f"Stripe test {self.id}", "acceptTerms": True})
            if applied.get("approval") == "PENDING":
                self.app.run(self.admin, "APPROVAL_DECIDE", {"requestId": applied["approvalRequestId"],
                                                             "decision": "APPROVE"})
            wait_for("the sponsor's approval", lambda: self.app.rows(self.sponsor, SPONSOR_DATASET)[0]["attributes"]
                     .get("reviewStatus") == "APPROVED", 60)
        self.fact("sponsorId", self.sponsor.subject())
        self.record("sponsor", "PASS", "sponsor approved")

    def checkout(self, amount, card, label):
        """Starts a top-up, checks that it is a test-mode checkout of this Stripe account, has it paid, waits for
        the credit; returns the top-up's id."""
        topup_id = self.app.run(self.sponsor, "QB_TOPUP_START", {"amount": amount})["topUpId"]

        def opened():
            for row in self.app.rows(self.sponsor, SPONSOR_TOPUPS):
                if row["id"] == topup_id and row["attributes"].get("checkoutUrl"):
                    return row["attributes"]["checkoutUrl"]
            return None

        url = wait_for("the checkout", opened, 120)
        session_id = self.topup(topup_id).get("checkoutSessionId") or ""
        if not session_id.startswith("cs_test_"):
            raise Refused(f"the checkout {session_id or '(none)'} is not a test-mode session: refused before paying")
        if self.args.stub:
            if not is_loopback(urllib.parse.urlsplit(url).hostname or ""):
                raise Refused("the checkout is not the local stub's: refused")
            post_no_redirect(url.rstrip("/") + "/paid")
        else:
            if not url.startswith(CHECKOUT_PREFIX):
                raise Refused(f"the checkout address is not Stripe's ({CHECKOUT_PREFIX}): refused")
            try:
                session = self.stripe.call("GET", f"/v1/checkout/sessions/{session_id}")
            except ApiError as error:
                raise Refused(f"this key cannot read the application's checkout ({error.status}): the application "
                              "uses another Stripe account") from None
            if session.get("livemode") is not False:
                raise Refused("Stripe reports the checkout in live mode: refused")
            if not self.browser("checkout", url, card.replace(" ", "")):
                self.interactive(f"\n  >>> Open this checkout in a browser and pay {amount:,} JPY ({label}) with "
                                 f"the test card {card}, any future expiry, any CVC, any name:\n      {url}\n"
                                 "      (waiting for Stripe's checkout.session.completed to reach QuizBuks)\n")
        wait_for(f"the top-up of {amount} to be credited (checkout.session.completed)",
                 lambda: self.topup(topup_id).get("status") == "PAID", self.args.wait_person)
        return topup_id

    def step_topup(self):
        amount = self.args.topup
        before, _ = self.sponsor_wallet()
        topup_id = self.fact("topUpId", self.checkout(amount, CARD_SUCCESS, "the top-up"))
        row = self.topup(topup_id)
        problems = []
        if row.get("livemode") is not False:
            problems.append(f"livemode {row.get('livemode')}")
        if not str(row.get("paymentIntentId") or "").startswith("pi_"):
            problems.append("no payment intent")
        if money(row.get("amount")) != amount:
            problems.append(f"amount {row.get('amount')}")
        balance, _ = self.sponsor_wallet()
        if balance - before != amount:
            problems.append(f"wallet {before} -> {balance}, expected +{amount}")
        if problems:
            raise Failed("top-up: " + ", ".join(problems))
        self.expect_posting("QbTopUp", topup_id, "Top-up", {"1110": amount, "2110": -amount})
        row = wait_for("Stripe's fee to be recorded", lambda: (lambda r: r if r.get("stripeFee") is not None
                                                               else None)(self.topup(topup_id)), 180)
        fee = money(row.get("stripeFee"))
        if fee:
            self.expect_posting("QbTopUp", topup_id, "Stripe fee", {"5110": fee, "1110": -fee})
        self.fact("paymentIntentId", row.get("paymentIntentId"))
        self.fact("checkoutSessionId", row.get("checkoutSessionId"))
        if self.stripe:
            intent = self.stripe.call("GET", f"/v1/payment_intents/{row['paymentIntentId']}")
            if intent.get("status") != "succeeded" or intent.get("amount") != amount or intent.get("livemode"):
                raise Failed(f"Stripe's payment intent: {intent.get('status')}, {intent.get('amount')}")
        self.record("topup", "PASS", f"{amount:,} paid (PI {row.get('paymentIntentId')}), wallet +{amount:,}, "
                    f"posted 1110/2110, Stripe fee {fee:,} posted 5110/1110")

    def step_quiz(self):
        reward = self.args.reward
        self.taker = self.user("app", "TAKER", "Stripe Test Taker")
        self.app.run(self.taker, "QB_PROFILE_SAVE", {"displayName": "Stripe Test Taker",
                                                    "countryCode": self.args.country, "preferredCurrency": "JPY"})
        self.fact("takerId", self.taker.subject())
        before = self.taker_available()
        # The transfer run takes the taker as soon as the balance and an enabled account are there (a given taker may
        # have one already): Stripe's available balance must hold the transfer before the reward is paid.
        rate = self.facts["feeRate"]
        self.fund(before + reward - math.floor(reward * rate) - self.facts["transferFee"], "the transfer")
        quiz = self.app.run(self.sponsor, "QB_QUIZ_SAVE", {"title": f"Stripe test {self.id}",
                                                           "intro": "One question.", "timeLimitSec": 300})["quizId"]
        self.app.run(self.sponsor, "QB_MATERIAL_SAVE", {"quizId": quiz, "kind": "ARTICLE", "title": "Read first",
                                                        "body": "Paris is the capital of France."})
        self.app.run(self.sponsor, "QB_QUESTION_SAVE", {"quizId": quiz, "stem": "Capital of France?", "points": 1,
                                                        "options": [{"text": "Paris", "correct": True},
                                                                    {"text": "Lyon", "correct": False}]})
        version = self.app.run(self.sponsor, "QB_QUIZ_PUBLISH_VERSION", {"quizId": quiz})["versionId"]
        now = time.time()
        stamp = lambda t: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))
        publication = self.app.run(self.sponsor, "QB_PUBLICATION_SAVE", {
            "versionId": version, "name": f"Stripe test {self.id}", "regions": ["GLOBAL"],
            "startAt": stamp(now - 60), "endAt": stamp(now + 7 * 86400), "timeLimitSec": 300, "passScore": 1,
            "tiers": [{"fromScore": 0, "toScore": 0, "reward": 0}, {"fromScore": 1, "toScore": 1, "reward": reward}]
        })["publicationId"]
        submitted = self.app.run(self.sponsor, "QB_PUBLICATION_SUBMIT", {"publicationId": publication})
        if submitted.get("approval") == "PENDING":
            self.app.run(self.admin, "APPROVAL_DECIDE", {"requestId": submitted["approvalRequestId"],
                                                         "decision": "APPROVE"})

        def live():
            stamp_now = stamp(time.time())
            items = self.app.query(self.taker, "qb.app.publication", {"publicationId": publication, "at": stamp_now,
                                                                      "country": self.args.country})
            return items and items[0].get("canStart")

        wait_for("the publication to go live", live, 60)
        started = self.app.run(self.taker, "QB_ATTEMPT_START", {"publicationId": publication})
        answers = [{"questionNo": q["no"], "selected": [o["no"] for o in q["options"] if o["text"] == "Paris"]}
                   for q in started["questions"]]
        result = self.app.run(self.taker, "QB_ATTEMPT_SUBMIT", {"attemptId": started["attemptId"],
                                                                "publicationId": publication, "answers": answers})
        if result.get("rewardStatus") != "PAID":
            raise Failed(f"the reward is {result.get('rewardStatus')}, not PAID")
        attempt = started["attemptId"]
        posting = [p for p in self.postings("QbAttempt", attempt) if not p["reverses"]]
        if len(posting) != 1 or posting[0]["nets"].get("2110") != reward:
            raise Failed(f"the reward's posting: {posting}")
        net = -posting[0]["nets"].get("2120", 0)
        after = self.taker_available()
        if after - before != net:
            raise Failed(f"taker's balance {before} -> {after}, expected +{net}")
        if after < self.facts["threshold"]:
            raise Failed(f"the taker has {after}, below the transfer threshold {self.facts['threshold']}: "
                         "raise --reward")
        self.fact("attemptId", attempt)
        self.fact("takerBalance", after)
        self.record("quiz", "PASS", f"reward {reward:,} paid; taker +{net:,} (2120), commission "
                    f"{-posting[0]['nets'].get('4110', 0):,}; taker balance {after:,}")

    def fund(self, needed, why):
        """Makes sure the platform's available balance holds `needed` JPY (test-mode funding, outside QuizBuks)."""
        if not self.stripe:
            return
        available = self.stripe.available()
        if available >= needed:
            return
        if not self.args.fund_balance:
            raise Failed(f"Stripe's available JPY balance is {available:,}, {why} needs {needed:,}: card payments "
                         "stay pending for days; rerun with --fund-balance (a test charge with "
                         f"{PM_BYPASS_PENDING}) or add funds in the dashboard")
        amount = needed - available + 1000
        self.stripe.call("POST", "/v1/payment_intents", {
            "amount": amount, "currency": "jpy", "payment_method": PM_BYPASS_PENDING, "confirm": "true",
            "payment_method_types[]": "card", "description": f"QuizBuks Stripe test-mode run {self.id}: "
                                                              "balance for transfers and payouts"},
            idempotency=f"qb-stm-fund-{self.id}-{needed}")
        wait_for("the funding to become available", lambda: self.stripe.available() >= needed, 120)
        say(f"  (funded Stripe's available balance with {amount:,} JPY: not a QuizBuks top-up, not in the ledger)")

    def step_onboarding(self):
        mine = self.app.rows(self.taker, MY_PAYOUT_ACCOUNT)
        if mine and mine[0]["attributes"].get("status") == "ENABLED":
            self.fact("accountId", self.stripe_account_id())
            self.record("onboarding", "SKIP", "the taker's payout account was enabled already (a given account)")
            return
        url, expires = self.onboarding_link()
        enabled = lambda: (lambda rows: rows[0] if rows and rows[0]["attributes"].get("status") == "ENABLED"
                           else None)(self.app.rows(self.taker, MY_PAYOUT_ACCOUNT))
        if self.args.stub:
            post_no_redirect(url.rstrip("/") + "/enable")
        elif not self.browser("onboard", url):
            # An account link signs the person in to the account's onboarding: it goes to a private file, not to the
            # terminal, and a new one replaces it when it expires (Stripe's links last minutes and work once).
            os.makedirs(self.args.state, exist_ok=True)
            path = os.path.join(self.args.state, f"onboarding-{self.id}.url")
            say("\n  >>> Open the payout onboarding link in the private file below in a browser and complete "
                "Stripe's test onboarding (runbook, step 5: test phone, code 000000, test identity and bank data):"
                f"\n      {path}\n      (waiting for account.updated to enable the account; the file is renewed "
                "when the link expires)\n")
            deadline = time.time() + self.args.wait_person
            while not enabled():
                if time.time() > deadline:
                    raise Failed(f"gave up waiting for the payout account after {self.args.wait_person} s")
                if expires and time.time() > expires:
                    url, expires = self.onboarding_link()
                    say("      (the link expired: a new one is in the file)")
                with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as out:
                    out.write(url + "\n")
                time.sleep(5)
        account = wait_for("the payout account to be ENABLED (account.updated)", enabled,
                           self.args.wait_person)["attributes"]
        # The taker's own dataset shows the Stripe account masked: the administrator reveals it (a recorded display).
        account_id = self.stripe_account_id()
        if account.get("livemode") is not False or not account_id.startswith("acct_"):
            raise Failed(f"payout account: livemode {account.get('livemode')}, {account_id}")
        if self.stripe:
            stripe_account = self.stripe.call("GET", f"/v1/accounts/{account_id}")
            if not stripe_account.get("payouts_enabled"):
                raise Failed("Stripe reports the account without payouts enabled")
        self.fact("accountId", account_id)
        self.record("onboarding", "PASS", f"Express account {account_id} ENABLED ({account.get('country')})")

    def stripe_account_id(self):
        rows = self.app.rows(self.admin, PAYOUT_ACCOUNTS, [{"field": "userId", "op": "eq",
                                                            "value": self.facts["takerId"]}], 1)
        if not rows:
            raise Failed("no payout account of the taker")
        return str(self.app.reveal(self.admin, PAYOUT_ACCOUNTS, rows[0]["id"], "externalAccountId"))

    def onboarding_link(self):
        """A new account link of the taker's (QB_PAYOUT_ONBOARD) and when it expires (epoch seconds, or None)."""
        previous = (self.app.rows(self.taker, MY_ONBOARDING) or [{}])[0].get("attributes", {}).get("url")
        self.app.run(self.taker, "QB_PAYOUT_ONBOARD", {})

        def fresh():
            row = (self.app.rows(self.taker, MY_ONBOARDING) or [{}])[0].get("attributes", {})
            return row if row.get("url") and row.get("url") != previous else None

        row = wait_for("the onboarding link", fresh, 120)
        expires = row.get("expiresAt")
        return secret(row["url"]), (iso(expires).timestamp() if expires else None)

    def step_transfer(self):
        user = self.facts["takerId"]
        jobs = {j["name"]: j for j in self.app.call("GET", "/api/jobs", token=self.admin.token())}
        job = jobs.get("qb.transfer-run")
        if job and job.get("nextTime"):
            if iso(job["nextTime"]).timestamp() - time.time() > self.args.wait_transfer:
                raise Failed(f"the next transfer run is at {job['nextTime']} ({job.get('cron')}): start the test "
                             "installation with QB_TRANSFER_RUN_CRON='0 * * * * *' (test mode only; run.sh serve "
                             "does) or wait for it and run again")

        def latest():
            rows = [r for r in self.app.rows(self.admin, TRANSFERS, [{"field": "userId", "op": "eq", "value": user}],
                                             100) if iso(r["attributes"]["createdAt"]) >= self.started]
            rows.sort(key=lambda r: iso(r["attributes"]["createdAt"]), reverse=True)
            return rows[0] if rows else None

        row = wait_for("the transfer run to take the taker", latest, self.args.wait_transfer + 60, every=5)
        if row["attributes"]["status"] == "REVIEW":
            say("  (the transfer waits for review: approving it as the administrator)")
            self.app.run(self.admin, "APPROVAL_DECIDE", {"requestId": row["attributes"]["approvalRequestId"],
                                                         "decision": "APPROVE"})
        row = wait_for("the transfer to be SENT", lambda: (lambda r: r if r and r["attributes"]["status"] not in
                                                           ("REVIEW", "SENDING") else None)(latest()), 300, every=5)
        a = row["attributes"]
        if a["status"] != "SENT":
            raise Failed(f"the transfer ended {a['status']} ({a.get('failureCode')}: {a.get('failureMessage')})")
        amount, fee, net = money(a["amount"]), money(a["fee"]), money(a["net"])
        transfer_id = row["id"]
        nets = {"2120": amount, "2130": -net}
        if fee:
            nets["4110"] = -fee
        self.expect_posting("QbTransfer", transfer_id, "Transfer", nets)
        self.expect_posting("QbTransfer", transfer_id, "Transfer settled", {"2130": net, "1110": -net})
        if self.stripe:
            tr = self.stripe.call("GET", f"/v1/transfers/{a['stripeTransferId']}")
            if tr.get("amount") != net or tr.get("destination") != self.facts["accountId"] or tr.get("livemode"):
                raise Failed(f"Stripe's transfer: {tr.get('amount')} to {tr.get('destination')}")
        if self.taker_available() != 0:
            raise Failed(f"the taker still has {self.taker_available()} after the transfer")
        self.fact("transferId", transfer_id)
        self.fact("stripeTransferId", a["stripeTransferId"])
        self.record("transfer", "PASS", f"{amount:,} sent as {a['stripeTransferId']} ({net:,} net, fee {fee:,}) to "
                    f"{self.facts['accountId']}; posted 2120/2130 and 2130/1110")

    def step_refund(self):
        topup_id = self.facts["topUpId"]
        before = self.topup(topup_id)
        wallet_before, _ = self.sponsor_wallet()
        if self.args.stub:
            post_no_redirect(f"{self.args.stub_url.rstrip('/')}/pay/{self.facts['checkoutSessionId']}/refund")
            refunded = money(before["amount"])
        else:
            refunded = self.args.refund
            self.fund(refunded, "the refund")
            self.stripe.call("POST", "/v1/refunds", {"payment_intent": self.facts["paymentIntentId"],
                                                     "amount": refunded}, idempotency=f"qb-stm-refund-{topup_id}")
        row = wait_for("charge.refunded to be recorded", lambda: (lambda r: r if money(r.get("refundedAmount"))
                       + money(r.get("refundShortfall")) >= refunded else None)(self.topup(topup_id)),
                       self.args.wait_event)
        clawed = money(row.get("refundedAmount")) - money(before.get("refundedAmount"))
        shortfall = money(row.get("refundShortfall")) - money(before.get("refundShortfall"))
        if clawed + shortfall != refunded:
            raise Failed(f"refund of {refunded}: taken back {clawed} + shortfall {shortfall}")
        wallet_after, _ = self.sponsor_wallet()
        if wallet_before - wallet_after != clawed:
            raise Failed(f"sponsor wallet {wallet_before} -> {wallet_after}, expected -{clawed}")
        if clawed:
            clawbacks = [p for p in self.postings("QbTopUp", topup_id)
                         if p["nets"].get("2110", 0) > 0 and p["description"] not in ("Top-up", "Stripe fee")]
            if sum(p["nets"]["2110"] for p in clawbacks) != clawed or any(
                    p["nets"].get("1110") != -p["nets"]["2110"] for p in clawbacks):
                raise Failed(f"the refund's posting: {clawbacks}")
        where = "the stub (whole amount)" if self.args.stub else "Stripe"
        self.record("refund", "PASS", f"{refunded:,} refunded at {where}: {clawed:,} taken back from the wallet "
                    f"(2110/1110), shortfall {shortfall:,}" + (" (task for finance)" if shortfall else ""))

    def step_dispute(self):
        if self.args.stub:
            self.record("dispute", "SKIP", "the stub has no disputes")
            return
        amount = self.args.dispute
        topup_id = self.checkout(amount, CARD_DISPUTE, "the dispute's top-up")
        self.fact("disputeTopUpId", topup_id)
        row = wait_for("charge.dispute.created to be recorded", lambda: (lambda r: r if r.get("disputeStatus")
                       else None)(self.topup(topup_id)), self.args.wait_event)
        held = money(row.get("disputeHeld"))
        _, wallet_held = self.sponsor_wallet()
        if row.get("disputeStatus") != "OPEN" or held <= 0 or wallet_held < held:
            raise Failed(f"dispute opened: status {row.get('disputeStatus')}, held {held}, wallet held {wallet_held}")
        dispute = row.get("disputeId")
        self.stripe.call("POST", f"/v1/disputes/{dispute}", {"evidence[uncategorized_text]": WINNING_EVIDENCE,
                                                             "submit": "true"})
        row = wait_for("charge.dispute.closed (won) to be recorded", lambda: (lambda r: r if r.get("disputeStatus")
                       not in (None, "OPEN") else None)(self.topup(topup_id)), self.args.wait_event)
        _, wallet_held_after = self.sponsor_wallet()
        if row["disputeStatus"] != "WON" or money(row.get("disputeHeld")) != 0 or wallet_held_after != wallet_held - held:
            raise Failed(f"dispute closed: {row['disputeStatus']}, held {row.get('disputeHeld')}, wallet held "
                         f"{wallet_held} -> {wallet_held_after}")
        self.record("dispute", "PASS", f"dispute {dispute} on a {amount:,} top-up: {held:,} held while OPEN, "
                    "closed WON with Stripe's winning evidence, hold released")

    def step_payout(self):
        if self.args.stub:
            self.record("payout", "SKIP", "the stub has no payouts")
            return
        amount = self.args.payout
        self.fund(amount, "the payout")
        try:
            payout = self.stripe.call("POST", "/v1/payouts", {"amount": amount, "currency": "jpy",
                                                              "description": f"QuizBuks test-mode run {self.id}"},
                                      idempotency=f"qb-stm-payout-{self.id}")
        except ApiError as error:
            self.record("payout", "SKIP", f"Stripe refused a manual payout ({error.status}): "
                        f"{error.body[:200]} - set the payout schedule to manual and add a test bank account "
                        "(runbook, step 9)")
            return
        self.fact("payoutId", payout["id"])
        try:
            self.verify_payout(payout["id"], amount, self.args.wait_payout)
        except Failed as failure:
            self.record("payout", "PENDING", f"{payout['id']} ({amount:,}) not recorded yet ({failure}); test-mode "
                        f"payouts are paid on their arrival date: later, run.sh payout-check {payout['id']}")
            return
        self.record("payout", "PASS", f"{payout['id']} of {amount:,} paid (payout.paid), posted 1120/1110")

    def verify_payout(self, payout_id, amount, timeout):
        row = wait_for(f"payout.paid of {payout_id} to be recorded", lambda: (lambda rows: rows[0] if rows and rows[0]
                       ["attributes"].get("status") == "PAID" else None)(self.app.rows(
                           self.admin, STRIPE_PAYOUTS, [{"field": "payoutId", "op": "eq", "value": payout_id}], 1)),
                       timeout, every=10)
        if amount is not None and money(row["attributes"]["amount"]) != amount:
            raise Failed(f"payout recorded with {row['attributes']['amount']}, expected {amount}")
        amount = money(row["attributes"]["amount"])
        self.expect_posting("QbStripePayout", row["id"], "Stripe payout", {"1120": amount, "1110": -amount})
        return row

    def reconciliation(self):
        try:
            runs = self.app.query(self.admin, "qb.admin.recon-runs", limit=1)
        except ApiError as error:
            return f"not readable ({error.status})"
        if not runs:
            return "no run yet"
        r = runs[0]
        return f"latest run {r.get('scheduledTime')}: {r.get('status')} ({r.get('itemCount')} item(s) noted)"

    def execute(self):
        guard(self.args)
        health = self.app.call("GET", "/actuator/health")
        if not (isinstance(health, dict) and health.get("status") == "UP"):
            raise Failed(f"{self.args.base_url} is not healthy: {health}")
        if self.stripe:
            available = self.stripe.available()  # also: Stripe confirms the key is a test-mode key
            account = self.stripe.call("GET", "/v1/account")
            say(f"Stripe account {account.get('id')} ({account.get('country')}), test mode, available "
                f"{available:,} JPY")
        say(f"QuizBuks at {self.args.base_url}, run {self.id}" + (" (local Stripe stub)" if self.args.stub else ""))
        failed = False
        for step in STEPS:  # each step needs the ones before it
            say(f"\n[{step}]")
            try:
                getattr(self, f"step_{step}")()
            except (Failed, ApiError) as failure:
                self.record(step, "FAIL", str(failure))
                failed = True
                break
        self.facts["reconciliation"] = self.reconciliation()
        self.summary()
        return 1 if failed else 0

    def summary(self):
        say("\nSummary of run", self.id)
        for r in self.results:
            say(f"  {r['step']:<11} {r['status']:<8} {r['detail']}")
        done = {r["step"] for r in self.results}
        for step in STEPS:
            if step not in done:
                say(f"  {step:<11} {'NOT RUN':<8}")
        say("  reconciliation:", self.facts.get("reconciliation"))
        os.makedirs(self.args.state, exist_ok=True)
        path = os.path.join(self.args.state, f"results-{self.id}.json")
        with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as out:
            json.dump({"run": self.id, "base": self.args.base_url, "stub": self.args.stub,
                       "results": self.results, "facts": self.facts}, out, indent=2)
        say("  results:", path)


def payout_check(args, payout_id):
    guard(args)
    run = Run(args)
    row = run.verify_payout(payout_id, None, 5)
    say(f"{payout_id}: PAID, {money(row['attributes']['amount']):,} posted 1120/1110")
    return 0


def parse(argv):
    p = argparse.ArgumentParser(prog="run.sh", description=__doc__.split("\n\n")[0])
    p.add_argument("command", choices=["guard", "run", "payout-check"])
    p.add_argument("payout_id", nargs="?")
    p.add_argument("--base-url", default=env("STRIPE_TM_BASE_URL", DEFAULT_BASE))
    p.add_argument("--allow-remote", action="store_true", default=truthy(env("STRIPE_TM_ALLOW_REMOTE", "no")))
    p.add_argument("--stub", action="store_true", help="drive the local Stripe stub (dry run; nothing reaches Stripe)")
    p.add_argument("--stub-url", default=env("STRIPE_TM_STUB_URL", "http://localhost:8093"))
    p.add_argument("--fund-balance", action="store_true", default=truthy(env("STRIPE_TM_FUND_BALANCE", "no")),
                   help=f"top up Stripe's available test balance with {PM_BYPASS_PENDING} when short")
    p.add_argument("--browser", default=env("STRIPE_TM_BROWSER"), help="a command that pays a checkout and "
                   "completes an onboarding (browser.mjs): unattended runs")
    p.add_argument("--country", default=env("STRIPE_TM_COUNTRY", "JP"))
    p.add_argument("--topup", type=int, default=10000)
    p.add_argument("--reward", type=int, default=2000)
    p.add_argument("--refund", type=int, default=1000)
    p.add_argument("--dispute", type=int, default=2000)
    p.add_argument("--payout", type=int, default=1000)
    p.add_argument("--wait-person", type=int, default=1200, help="seconds to wait for a person at Stripe's pages")
    p.add_argument("--wait-event", type=int, default=600, help="seconds to wait for an event to be recorded")
    p.add_argument("--wait-transfer", type=int, default=150, help="seconds to wait for the transfer run")
    p.add_argument("--wait-payout", type=int, default=900, help="seconds to wait for payout.paid")
    p.add_argument("--state", default=env("STRIPE_TM_STATE", os.path.join(HERE, "..", "..", "..", "deploy",
                                                                       "quizbuks", ".stripe-test-mode")))
    args = p.parse_args(argv)
    if args.command == "payout-check" and not args.payout_id:
        p.error("payout-check needs a payout id (po_...)")
    for name in ("topup", "reward", "refund", "dispute", "payout"):
        if getattr(args, name) < 1:
            p.error(f"--{name} must be positive")
    if args.reward + args.refund > args.topup:
        p.error("--topup must cover --reward and --refund")
    args.state = os.path.abspath(args.state)
    return args


def main(argv):
    try:
        args = parse(argv)
        if args.command == "guard":
            guard(args)
            say("guard: ok", "(stub)" if args.stub else "(Stripe test mode)", args.base_url)
            return 0
        if args.command == "payout-check":
            return payout_check(args, args.payout_id)
        return Run(args).execute()
    except Refused as refusal:
        say(f"stripe-test-mode: REFUSED: {refusal}")
        return 2
    except (Failed, ApiError) as failure:
        say(f"stripe-test-mode: FAILED: {failure}")
        return 1
    except KeyboardInterrupt:
        say("stripe-test-mode: interrupted")
        return 130


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
