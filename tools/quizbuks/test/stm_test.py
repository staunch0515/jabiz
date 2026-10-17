"""Tests of tools/quizbuks/stripe-test-mode/stm.py with fakes (no network but loopback servers of the tests' own): the
preflight's test-mode signal before any write, the local publisher only for serve's instance, requests that follow no
redirect and turn connection errors into a FAIL with a summary, the private state directory, the browser helper's
address off the command line; and the Stripe-mode branches: the test-mode checks at the checkout, the funding of the
available balance, the dispute and the payout. The stub dry run covers the rest against a real application; these
cover what only real Stripe would reach. Run by stripe-test-mode.test.sh:

    python3 -I -B tools/quizbuks/test/stm_test.py
"""

import http.server
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "stripe-test-mode"))
import stm  # noqa: E402


class FakeSession:
    def token(self):
        return "token"

    def subject(self):
        return "user-1"


class FakeApp:
    """Answers the calls the steps make from plain data; `topups` and `payouts` may be lists of successive states."""

    def __init__(self):
        self.topups = {}
        self.wallet = [{"balance": 10000, "held": 0}]
        self.payouts = []
        self.runs = []
        self.inserts = []
        self.ledger = {}
        self.jobs = [{"name": stm.TRANSFER_JOB, "cron": "0 * * * * *"}]
        self.admin_topups = []

    def _now(self, value):
        return value.pop(0) if isinstance(value, list) and len(value) > 1 else (
            value[0] if isinstance(value, list) else value)

    def call(self, method, path, body=None, token=None, headers=None, **kwargs):
        if (method, path) == ("GET", "/actuator/health"):
            return {"status": "UP"}
        if (method, path) == ("GET", "/api/jobs"):
            return self.jobs
        raise AssertionError((method, path))

    def query(self, session, template, params=None, filters=(), limit=100):
        if template == "qb.admin.topups":
            return self.admin_topups
        raise AssertionError(template)

    def run(self, session, process, data):
        self.runs.append((process, data))
        if process == "QB_TOPUP_START":
            return {"topUpId": "t1"}
        return {}

    def insert(self, session, dataset, attributes):
        self.inserts.append((dataset, attributes))
        return {"id": "x"}

    def writes(self):
        return self.runs + self.inserts

    def rows(self, session, dataset, filters=(), limit=100, sorts=None):
        if dataset == stm.PARAMS:
            key = filters[0]["value"]
            return [{"id": key, "attributes": {"value": "true" if key == "qb.transfer.enabled" else "0"}}]
        if dataset == stm.SPONSOR_TOPUPS:
            return [{"id": "t1", "attributes": {"checkoutUrl": self.checkout_url}}]
        if dataset == stm.SPONSOR_WALLETS:
            return [{"id": "w", "attributes": self._now(self.wallet)}]
        if dataset == stm.STRIPE_PAYOUTS:
            return self._now(self.payouts) if self.payouts else []
        raise AssertionError(dataset)

    def entity(self, session, dataset, entity_id):
        return dict(self._now(self.topups[entity_id]))


class FakeStripe:
    def __init__(self, available=0, session=None, refuse=None):
        self.balance = available
        self.session = session or {"livemode": False}
        self.refuse = refuse or {}
        self.calls = []

    def available(self):
        return self.balance

    def call(self, method, path, form=None, idempotency=None):
        self.calls.append((method, path, form, idempotency))
        for prefix, error in self.refuse.items():
            if path.startswith(prefix):
                raise error
        if path.startswith("/v1/checkout/sessions/"):
            return self.session
        if path == "/v1/payment_intents":
            self.balance += form["amount"]
            return {"id": "pi_fund"}
        if path == "/v1/payouts":
            return {"id": "po_1"}
        if path.startswith("/v1/disputes/"):
            return {"id": path.rsplit("/", 1)[1]}
        raise AssertionError(path)


def make_run(**options):
    args = stm.parse(["run"] + [a for k, v in options.items() for a in ([f"--{k.replace('_', '-')}"] if v is True
                                                                         else [f"--{k.replace('_', '-')}", str(v)])])
    os.environ.setdefault("STRIPE_TM_ADMIN_PASSWORD", "x")
    state = tempfile.mkdtemp(prefix="stm-test-")
    unittest.addModuleCleanup(shutil.rmtree, state, ignore_errors=True)
    args.state = os.path.join(state, "state")
    run = stm.Run(args)
    run.app = FakeApp()
    run.stripe = FakeStripe()
    run.sponsor = run.admin = FakeSession()
    run.interactive = lambda prompt: None
    run.args.wait_person = run.args.wait_event = run.args.wait_payout = 1
    stm.time.sleep = lambda seconds: None
    return run


class CheckoutTest(unittest.TestCase):
    def prepared(self, session_id="cs_test_1", url="https://checkout.stripe.com/c/pay/cs_test_1", **stripe):
        run = make_run()
        run.app.checkout_url = url
        run.app.topups["t1"] = [{"checkoutSessionId": session_id, "status": "OPEN"}, {"checkoutSessionId": session_id,
                                                                                       "status": "PAID"}]
        run.stripe = FakeStripe(**stripe)
        return run

    def test_a_test_session_of_this_account_is_paid_by_a_person(self):
        run = self.prepared()
        self.assertEqual(run.checkout(1000, stm.CARD_SUCCESS, "x"), "t1")
        self.assertEqual(run.stripe.calls[0][1], "/v1/checkout/sessions/cs_test_1")

    def test_a_live_session_is_refused_before_paying(self):
        run = self.prepared(session_id="cs_live_1")
        with self.assertRaisesRegex(stm.Refused, "not a test-mode session"):
            run.checkout(1000, stm.CARD_SUCCESS, "x")
        self.assertEqual(run.stripe.calls, [])

    def test_another_accounts_session_is_refused(self):
        run = self.prepared(refuse={"/v1/checkout/sessions/": stm.ApiError(404, "{}")})
        with self.assertRaisesRegex(stm.Refused, "another Stripe account"):
            run.checkout(1000, stm.CARD_SUCCESS, "x")

    def test_stripe_reporting_live_mode_is_refused(self):
        run = self.prepared(session={"livemode": True})
        with self.assertRaisesRegex(stm.Refused, "live mode"):
            run.checkout(1000, stm.CARD_SUCCESS, "x")

    def test_a_page_that_is_not_stripes_is_refused(self):
        run = self.prepared(url="https://checkout.stripe.com.evil.test/c/pay/cs_test_1")
        with self.assertRaisesRegex(stm.Refused, "not Stripe's"):
            run.checkout(1000, stm.CARD_SUCCESS, "x")


class FundTest(unittest.TestCase):
    def test_enough_balance_needs_nothing(self):
        run = make_run()
        run.stripe.balance = 5000
        run.fund(5000, "the transfer")
        self.assertEqual(run.stripe.calls, [])

    def test_short_without_the_option_fails_before_the_step(self):
        run = make_run()
        run.stripe.balance = 100
        with self.assertRaisesRegex(stm.Failed, "--fund-balance"):
            run.fund(2000, "the transfer")
        self.assertEqual(run.stripe.calls, [])

    def test_short_with_the_option_charges_the_bypass_method(self):
        run = make_run(fund_balance=True)
        run.stripe.balance = 500
        run.fund(2000, "the transfer")
        method, path, form, key = run.stripe.calls[0]
        self.assertEqual((method, path, form["payment_method"], form["amount"], form["currency"]),
                         ("POST", "/v1/payment_intents", stm.PM_BYPASS_PENDING, 2500, "jpy"))
        self.assertTrue(key.startswith("qb-stm-fund-"))


class PayoutTest(unittest.TestCase):
    def test_a_refused_manual_payout_is_skipped(self):
        run = make_run()
        run.stripe = FakeStripe(available=5000, refuse={"/v1/payouts": stm.ApiError(400, "automatic payouts")})
        run.step_payout()
        self.assertEqual(run.results[-1]["status"], "SKIP")

    def test_a_recorded_payout_passes_with_its_posting(self):
        run = make_run()
        run.stripe.balance = 5000
        run.app.payouts = [[{"id": "r1", "attributes": {"status": "PAID", "amount": 1000}}]]
        posted = []
        run.expect_posting = lambda *a: posted.append(a)
        run.step_payout()
        self.assertEqual(run.results[-1]["status"], "PASS")
        self.assertEqual(posted, [("QbStripePayout", "r1", "Stripe payout", {"1120": 1000, "1110": -1000})])

    def test_a_payout_not_yet_paid_is_pending(self):
        run = make_run()
        run.stripe.balance = 5000
        run.step_payout()
        self.assertEqual(run.results[-1]["status"], "PENDING")
        self.assertIn("payout-check po_1", run.results[-1]["detail"])


class DisputeTest(unittest.TestCase):
    def test_opened_held_then_won_and_released(self):
        run = make_run()
        run.app.checkout_url = "https://checkout.stripe.com/c/pay/cs_test_1"
        opened = {"checkoutSessionId": "cs_test_1", "status": "PAID", "disputeStatus": "OPEN", "disputeHeld": 2000,
                  "disputeId": "du_1"}
        run.app.topups["t1"] = [{"checkoutSessionId": "cs_test_1", "status": "PAID"}, opened, opened,
                                dict(opened, disputeStatus="WON", disputeHeld=0)]
        run.app.wallet = [{"balance": 12000, "held": 2000}, {"balance": 12000, "held": 0}]
        run.step_dispute()
        self.assertEqual(run.results[-1]["status"], "PASS", run.results)
        evidence = [c for c in run.stripe.calls if c[1] == "/v1/disputes/du_1"][0][2]
        self.assertEqual(evidence, {"evidence[uncategorized_text]": stm.WINNING_EVIDENCE, "submit": "true"})


class BalanceTest(unittest.TestCase):
    def test_a_live_balance_is_refused(self):
        stripe = stm.Stripe("sk_test_x", "v")
        stripe.call = lambda method, path, form=None, idempotency=None: {"livemode": True, "available": []}
        with self.assertRaises(stm.Refused):
            stripe.available()


def stub_run(**options):
    """A run of the whole flow (execute) against FakeApp, with the stub's options (the guard needs no key)."""
    run = make_run(stub=True, **options)
    run.stripe = None
    return run


class PreflightTest(unittest.TestCase):
    """The test-mode signal is read before anything is written; without it nothing is written."""

    def assert_refused_without_writes(self, run, reason):
        self.assertEqual(run.execute(), 2)
        self.assertEqual(run.app.writes(), [])
        self.assertEqual(run.results[0]["step"], "preflight")
        self.assertEqual(run.results[0]["status"], "REFUSED")
        self.assertIn(reason, run.results[0]["detail"])
        self.assertEqual(len(run.results), 1)
        self.assertTrue(any(name.startswith("results-") for name in os.listdir(run.args.state)))

    def test_the_daily_transfer_schedule_is_no_test_mode_signal(self):
        run = stub_run()
        run.app.jobs = [{"name": stm.TRANSFER_JOB, "cron": stm.DAILY_TRANSFER_CRON}]
        self.assert_refused_without_writes(run, "daily schedule")

    def test_a_missing_transfer_job_is_no_test_mode_signal(self):
        run = stub_run()
        run.app.jobs = [{"name": "qb.reconcile", "cron": "30 * * * * *"}]
        self.assert_refused_without_writes(run, "missing")

    def test_a_live_top_up_refuses_before_any_write(self):
        run = stub_run()
        run.app.admin_topups = [{"livemode": True}]
        self.assert_refused_without_writes(run, "live top-up")

    def test_with_the_signal_the_steps_run(self):
        run = stub_run()
        run.step_setup = lambda: run.app.run(run.admin, "QB_SETUP", {})
        run.step_sponsor = lambda: (_ for _ in ()).throw(stm.Failed("stop here"))
        run.reconciliation = lambda: "n/a"
        self.assertEqual(run.execute(), 1)
        self.assertEqual([(r["step"], r["status"]) for r in run.results],
                         [("preflight", "PASS"), ("sponsor", "FAIL")])
        self.assertEqual(run.app.runs, [("QB_SETUP", {})])

    def test_an_unexpected_error_in_a_step_is_a_fail_with_a_summary(self):
        run = stub_run()
        run.step_setup = lambda: {}["no such key"]
        run.reconciliation = lambda: "n/a"
        self.assertEqual(run.execute(), 1)
        self.assertEqual(run.results[-1]["step"], "setup")
        self.assertEqual(run.results[-1]["status"], "FAIL")
        self.assertIn("KeyError", run.results[-1]["detail"])
        files = [n for n in os.listdir(run.args.state) if n.startswith("results-")]
        with open(os.path.join(run.args.state, files[0]), encoding="utf-8") as saved:
            self.assertEqual(json.load(saved)["results"][-1]["status"], "FAIL")


class PublisherTest(unittest.TestCase):
    """The script makes its own publisher only for the instance run.sh serve started and still runs."""

    def serve_state(self, run, base="http://localhost:8080", pid=None):
        os.makedirs(run.args.state, exist_ok=True)
        with open(os.path.join(run.args.state, "run.env"), "w", encoding="utf-8") as out:
            out.write(f"STRIPE_TM_BASE_URL={base}\nSTRIPE_TM_ADMIN_PASSWORD=x\n")
        if pid is not None:
            with open(os.path.join(run.args.state, "app.pid"), "w", encoding="utf-8") as out:
                out.write(f"{pid}\n")

    def setup_without_publisher(self, run):
        made = []
        run.local_publisher = lambda: made.append("publisher")
        run.step_setup()
        return made

    def test_a_loopback_target_that_is_not_serves_gets_no_publisher(self):
        os.environ["STRIPE_TM_PUBLISHER_PASSWORD"] = "publisher-password"
        try:
            run = stub_run(base_url="http://localhost:8080")
            self.assertFalse(stm.serve_target(run.args))
            self.assertEqual(self.setup_without_publisher(run), [])
            self.assertEqual(run.app.inserts, [])
            self.assertNotIn("SEC_USER_CREATE", [process for process, _ in run.app.runs])
        finally:
            del os.environ["STRIPE_TM_PUBLISHER_PASSWORD"]

    def test_serves_live_instance_is_recognised(self):
        run = stub_run(base_url="http://localhost:8080/")
        self.serve_state(run, pid=os.getpid())
        self.assertTrue(stm.serve_target(run.args))
        self.assertEqual(self.setup_without_publisher(run), ["publisher"])

    def test_another_address_than_serves_is_not_serves(self):
        run = stub_run(base_url="http://127.0.0.1:9090")
        self.serve_state(run, pid=os.getpid())
        self.assertFalse(stm.serve_target(run.args))

    def test_a_stopped_serve_is_not_serves(self):
        run = stub_run(base_url="http://localhost:8080")
        done = subprocess.run([sys.executable, "-c", "import os; print(os.getpid())"], capture_output=True, text=True)
        self.serve_state(run, pid=int(done.stdout))
        self.assertFalse(stm.serve_target(run.args))
        self.serve_state(run, pid=0)
        self.assertFalse(stm.serve_target(run.args))

    def test_without_a_pid_file_it_is_not_serves(self):
        run = stub_run(base_url="http://localhost:8080")
        self.serve_state(run)
        self.assertFalse(stm.serve_target(run.args))


class Recorder(http.server.BaseHTTPRequestHandler):
    """A loopback server: /redirect answers 302 to the other server's /target; every request is recorded."""

    seen = []
    target = None

    def do_GET(self):  # noqa: N802 - http.server's naming
        Recorder.seen.append((self.server.server_port, self.path, self.headers.get("Authorization")))
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", Recorder.target)
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


class HttpTest(unittest.TestCase):
    def serve(self):
        server = http.server.HTTPServer(("127.0.0.1", 0), Recorder)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server

    def test_a_redirect_is_not_followed_and_the_token_goes_nowhere_else(self):
        first, second = self.serve(), self.serve()
        Recorder.seen = []
        Recorder.target = f"http://127.0.0.1:{second.server_port}/target"
        with self.assertRaises(stm.ApiError) as raised:
            stm.http("GET", f"http://127.0.0.1:{first.server_port}/redirect", token="secret-bearer-token")
        self.assertEqual(raised.exception.status, 302)
        self.assertEqual(Recorder.seen, [(first.server_port, "/redirect", "Bearer secret-bearer-token")])

    def test_the_stubs_buttons_accept_their_redirect(self):
        first, second = self.serve(), self.serve()
        Recorder.seen = []
        Recorder.target = f"http://127.0.0.1:{second.server_port}/target"
        self.assertIsNone(stm.http("GET", f"http://127.0.0.1:{first.server_port}/redirect", redirect_ok=True))
        self.assertEqual([s[1] for s in Recorder.seen], ["/redirect"])

    def test_a_connection_error_is_failed_without_the_query(self):
        server = http.server.HTTPServer(("127.0.0.1", 0), Recorder)
        port = server.server_port
        server.server_close()
        with self.assertRaisesRegex(stm.Failed, f"GET http://127.0.0.1:{port}/x: URLError") as raised:
            stm.http("GET", f"http://127.0.0.1:{port}/x?token=abc")
        self.assertNotIn("abc", str(raised.exception))

    def test_a_connection_error_in_the_preflight_writes_a_fail_summary(self):
        server = http.server.HTTPServer(("127.0.0.1", 0), Recorder)
        port = server.server_port
        server.server_close()
        run = make_run(stub=True, base_url=f"http://127.0.0.1:{port}")
        run.app = stm.App(run.args.base_url)
        self.assertEqual(run.execute(), 1)
        self.assertEqual((run.results[0]["step"], run.results[0]["status"]), ("preflight", "FAIL"))
        self.assertTrue(any(name.startswith("results-") for name in os.listdir(run.args.state)))


class StateTest(unittest.TestCase):
    def test_the_state_directory_is_private(self):
        run = make_run()
        os.makedirs(run.args.state, mode=0o755)
        os.chmod(run.args.state, 0o755)
        stm.state_dir(run.args)
        self.assertEqual(stat.S_IMODE(os.stat(run.args.state).st_mode), 0o700)

    def test_a_new_state_directory_is_private(self):
        run = make_run()
        stm.state_dir(run.args)
        self.assertEqual(stat.S_IMODE(os.stat(run.args.state).st_mode), 0o700)


class GuardTest(unittest.TestCase):
    def test_a_missing_production_hosts_file_is_refused(self):
        os.environ["STRIPE_TM_PRODUCTION_HOSTS_FILE"] = os.path.join(tempfile.gettempdir(), "no-such-hosts-file-qb")
        try:
            with self.assertRaisesRegex(stm.Refused, "production hosts file"):
                stm.production_hosts()
            with self.assertRaisesRegex(stm.Refused, "production hosts file"):
                stm.guard(stm.parse(["guard", "--stub"]))
        finally:
            del os.environ["STRIPE_TM_PRODUCTION_HOSTS_FILE"]


class BrowserTest(unittest.TestCase):
    def test_the_address_goes_in_the_environment_not_the_command_line(self):
        run = make_run(browser="node browser.mjs")
        seen = {}

        def fake(command, timeout, env):
            seen.update(command=command, url=env.get("STRIPE_TM_BROWSER_URL"))
            return subprocess.CompletedProcess(command, 0)

        original, stm.subprocess.run = stm.subprocess.run, fake
        try:
            self.assertTrue(run.browser("onboard", "https://connect.stripe.com/setup/e/acct_1/secret-link"))
        finally:
            stm.subprocess.run = original
        self.assertEqual(seen["command"], ["node", "browser.mjs", "onboard"])
        self.assertEqual(seen["url"], "https://connect.stripe.com/setup/e/acct_1/secret-link")


if __name__ == "__main__":
    unittest.main(verbosity=1)
