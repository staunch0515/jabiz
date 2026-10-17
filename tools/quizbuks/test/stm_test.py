"""Tests of tools/quizbuks/stripe-test-mode/stm.py's Stripe-mode branches with fakes (no network): the test-mode checks
at the checkout, the funding of the available balance, the dispute and the payout. The stub dry run covers the rest
against a real application; these cover what only real Stripe would reach. Run by stripe-test-mode.test.sh:

    python3 -I -B tools/quizbuks/test/stm_test.py
"""

import os
import sys
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
        self.ledger = {}

    def _now(self, value):
        return value.pop(0) if isinstance(value, list) and len(value) > 1 else (
            value[0] if isinstance(value, list) else value)

    def run(self, session, process, data):
        self.runs.append((process, data))
        if process == "QB_TOPUP_START":
            return {"topUpId": "t1"}
        return {}

    def rows(self, session, dataset, filters=(), limit=100, sorts=None):
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


if __name__ == "__main__":
    unittest.main(verbosity=1)
