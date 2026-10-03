#!/usr/bin/env python3
"""Recomputes the trial balance from the open archive alone (FIN-CT-021; docs/finance/ROADMAP.md F10 decision D7).

The archive is the platform's open export (POST /api/exports/data) of three data sets: the ledger accounts
(urn:jabiz:dataset:platform:LedgerAccount), the ledger entries (urn:jabiz:dataset:platform:LedgerEntry) and the
finance postings (urn:jabiz:dataset:default:FinPosting), which give each ledger transaction its posting date. The
balance of an account through a day is the sum of its entries, debits positive, whose transaction's posting date is
on or before the day; it is shown on the debit side when positive and on the credit side when negative, with the
totals, as the system's trial balance shows it.

Only the Python standard library is used. Writes CSV (account, debit, credit; a last row TOTAL) to standard output.

    python3 trial-balance-from-archive.py archive.zip --through 2026-01-31
"""
import argparse
import csv
import datetime
import io
import sys
import zipfile
from decimal import Decimal

ACCOUNTS = "data/urn_jabiz_dataset_platform_LedgerAccount.csv"
ENTRIES = "data/urn_jabiz_dataset_platform_LedgerEntry.csv"
POSTINGS = "data/urn_jabiz_dataset_default_FinPosting.csv"


def rows(archive, name):
    """The rows of a CSV file of the archive, as dictionaries by column name."""
    try:
        content = archive.read(name)
    except KeyError:
        raise SystemExit(f"{name}: not in the archive")
    return list(csv.DictReader(io.StringIO(content.decode("utf-8"), newline="")))


def trial_balance(path, through):
    """
    ({account code: balance, debits positive} through the day, accounts with a zero balance left out; the number of
    entries left out because no posting dates their transaction, as the system's trial balance leaves them out).
    """
    with zipfile.ZipFile(path) as archive:
        codes = {a["accountId"]: a["accountCode"] for a in rows(archive, ACCOUNTS)}
        dated = {p["transactionId"]: datetime.date.fromisoformat(p["postingDate"]) for p in rows(archive, POSTINGS)}
        balances = {}
        undated = 0
        for e in rows(archive, ENTRIES):
            day = dated.get(e["transactionId"])
            if day is None:
                undated += 1
                continue
            if day > through:
                continue
            code = codes.get(e["accountId"])
            if code is None:
                raise SystemExit(f"entry {e['entryId']}: account {e['accountId']} is not in {ACCOUNTS}")
            amount = Decimal(e["amount"])
            balances[code] = balances.get(code, Decimal("0")) + (amount if e["direction"] == "DEBIT" else -amount)
    return {code: balance for code, balance in balances.items() if balance != 0}, undated


def main(argv=None):
    parser = argparse.ArgumentParser(description="Recomputes the trial balance from the open archive alone.")
    parser.add_argument("archive", help="the export (ZIP)")
    parser.add_argument("--through", required=True, help="the last posting date (YYYY-MM-DD)")
    args = parser.parse_args(argv)
    try:
        through = datetime.date.fromisoformat(args.through)
    except ValueError:
        print(f"--through {args.through!r} is not a date (YYYY-MM-DD)", file=sys.stderr)
        return 2
    balances, undated = trial_balance(args.archive, through)
    if undated:
        print(f"{undated} entries without a posting left out", file=sys.stderr)
    out = csv.writer(sys.stdout, lineterminator="\n")
    out.writerow(["account", "debit", "credit"])
    debit = credit = Decimal("0")
    for code in sorted(balances):
        balance = balances[code]
        if balance > 0:
            debit += balance
            out.writerow([code, f"{balance:.2f}", "0.00"])
        else:
            credit -= balance
            out.writerow([code, "0.00", f"{-balance:.2f}"])
    out.writerow(["TOTAL", f"{debit:.2f}", f"{credit:.2f}"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
