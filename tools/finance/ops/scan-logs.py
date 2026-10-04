#!/usr/bin/env python3
"""FIN-NF-007: no taxpayer identification number or bank account number in the logs.

    scan-logs.py LOG [LOG ...]

Looks in the logs for every TIN and bank account number the books hold (the columns named tin, tax_id, *_tin,
account_number and *_account_number text columns of the application's database, read with psql and the PG* variables), wherever
they are not part of a longer number, and for anything written like a TIN on its own (12-3456789, 123-45-6789);
masked forms (*****6789) are fine. Prints each finding as file:line and the kind of value (never the value) and
exits 1 when there is any; 2 when the database holds no such value to look for (nothing would be proven) or cannot
be read.
"""
import re
import subprocess
import sys

COLUMNS = """
SELECT column_name || E'\t' || format('SELECT DISTINCT %I::text FROM %I.%I WHERE %I IS NOT NULL', column_name, table_schema, table_name,
              column_name)
FROM information_schema.columns
WHERE table_schema = current_schema() AND data_type IN ('character varying', 'text', 'character')
  AND (column_name IN ('tin', 'tax_id', 'account_number') OR column_name LIKE '%\\_tin'
       OR column_name LIKE '%\\_account\\_number')
"""
SHAPES = [("TIN-shaped (EIN)", re.compile(r"(?<![\w-])\d{2}-\d{7}(?![\w-])")),
          ("TIN-shaped (SSN/ITIN)", re.compile(r"(?<![\w-])\d{3}-\d{2}-\d{4}(?![\w-])"))]


def psql(sql: str) -> list[str]:
    out = subprocess.run(["psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql], check=True,
                         capture_output=True, text=True).stdout
    return [line for line in out.splitlines() if line.strip()]


def main(logs: list[str]) -> int:
    try:
        return scan(logs)
    except subprocess.CalledProcessError as e:
        print(f"psql failed: {e.stderr.strip()}", file=sys.stderr)
        return 2


def scan(logs: list[str]) -> int:
    if not logs:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    values: dict[str, str] = {}
    for row in psql(COLUMNS):
        column, query = row.split("\t", 1)
        kind = "TIN" if column in ("tin", "tax_id") or column.endswith("_tin") else "bank account number"
        for value in psql(query):
            digits = value.strip()
            # Masked values and short fragments prove nothing and would match everywhere.
            if "*" not in digits and len(re.sub(r"\D", "", digits)) >= 6:
                values[digits] = kind
                values.setdefault(re.sub(r"\D", "", digits), kind)
    if not values:
        print("the database holds no TIN or bank account number to look for", file=sys.stderr)
        return 2
    # The values themselves anywhere not inside a longer number (after letters too: "TIN123456789").
    exact = re.compile(r"(?<![0-9])(" + "|".join(re.escape(v) for v in sorted(values, key=len, reverse=True))
                       + r")(?![0-9])")
    found = 0
    for log in logs:
        with open(log, encoding="utf-8", errors="replace") as f:
            for number, line in enumerate(f, 1):
                kinds = {values[m.group(1)] for m in exact.finditer(line)}
                kinds |= {name for name, shape in SHAPES if shape.search(line)}
                for kind in sorted(kinds):
                    print(f"{log}:{number}: {kind}")
                    found += 1
    print(f"{len(values)} values looked for in {len(logs)} log(s): {found} found", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
