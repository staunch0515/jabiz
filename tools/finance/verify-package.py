#!/usr/bin/env python3
"""Checks an audit evidence package without the system (FIN-CT-012; docs/finance/ROADMAP.md F10 decision D5).

A package is the platform's open export (POST /api/exports/data, docs/design/21-audit-retention.md section 4): a ZIP
of CSV files, schema.json, the issued reports' PDFs and manifest.json, which lists every other file with its SHA-256,
its size and, for a CSV, its rows. The check recomputes each of them, refuses a file the manifest does not list and,
given the answer of FIN_AUDIT_PACKAGE (--expect), that the package holds exactly the reports issued for the request. With
--seal-hash it also compares the head of the seal chain the manifest names with one kept outside the system
(GET /api/integrity/head at the time), and with --package-sha256 (the whole file, as the evidence package page shows
it) or --manifest-sha256 (the manifest alone) with the hash the exporter handed over outside the system: the manifest
is not signed, so only such a hash shows that the files and their listing were not changed together.

Only the Python standard library is used. Exit status: 0 when the package is intact, 1 when not, 2 on bad usage.

    python3 verify-package.py package.zip [--expect package.json] [--seal-hash HEX] [--package-sha256 HEX]
                                          [--manifest-sha256 HEX]
"""
import argparse
import csv
import hashlib
import io
import json
import sys
import zipfile

MANIFEST = "manifest.json"
FORMAT = "jabiz-open-export/1"


def csv_rows(content):
    """The data rows of an RFC 4180 file with a header row (a quoted value may hold a line break)."""
    reader = csv.reader(io.StringIO(content.decode("utf-8"), newline=""))
    return max(sum(1 for _ in reader) - 1, 0)


def expected_runs(expect):
    """The run ids of the reports in FIN_AUDIT_PACKAGE's answer, or in the whole response that carries it as output."""
    answer = expect.get("output", expect) if isinstance(expect, dict) else None
    reports = answer.get("reports") if isinstance(answer, dict) else None
    if not isinstance(reports, list) or not all(isinstance(r, dict) and r.get("runId") for r in reports):
        raise ValueError("not an answer of FIN_AUDIT_PACKAGE: no reports with their runId")
    return [(r["runId"], r.get("templateId")) for r in reports]


def check(path, expect=None, seal_hash=None, manifest_sha256=None, package_sha256=None):
    """The problems found in the package at path, as texts; none when it is intact."""
    problems = []
    if package_sha256 is not None:
        try:
            digest = hashlib.sha256()
            with open(path, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    digest.update(chunk)
            if digest.hexdigest() != package_sha256.lower():
                problems.append(f"{path}: SHA-256 differs from the one handed over")
        except OSError as e:
            return [f"{path}: cannot be read ({e})"]
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        return [f"{path}: not a ZIP file ({e})"]
    with archive:
        names = [n for n in archive.namelist() if not n.endswith("/")]
        # A second entry of the same name would be read in place of the first by one tool and not by another.
        for name in sorted({n for n in names if names.count(n) > 1}):
            problems.append(f"{name}: in the package more than once")
        if MANIFEST not in names:
            return problems + [f"{MANIFEST}: missing"]
        manifest_bytes = archive.read(MANIFEST)
        if manifest_sha256 is not None and hashlib.sha256(manifest_bytes).hexdigest() != manifest_sha256.lower():
            problems.append(f"{MANIFEST}: SHA-256 differs from the one handed over")
        try:
            manifest = json.loads(manifest_bytes.decode("utf-8"))
        except ValueError as e:
            return [f"{MANIFEST}: not JSON ({e})"]
        if manifest.get("format") != FORMAT:
            problems.append(f"{MANIFEST}: format {manifest.get('format')!r}, expected {FORMAT!r}")
        listed = {}
        for entry in manifest.get("files") or []:
            if entry.get("path") in listed:
                problems.append(f"{entry.get('path')}: in the manifest more than once")
            listed[entry.get("path")] = entry
        for name in sorted(set(names)):
            if name != MANIFEST and name not in listed:
                problems.append(f"{name}: not in the manifest")
        for name, entry in sorted(listed.items()):
            if name not in names:
                problems.append(f"{name}: in the manifest, missing from the package")
                continue
            content = archive.read(name)
            if hashlib.sha256(content).hexdigest() != entry.get("sha256"):
                problems.append(f"{name}: SHA-256 differs from the manifest")
            if entry.get("bytes") is not None and len(content) != entry["bytes"]:
                problems.append(f"{name}: {len(content)} bytes, the manifest says {entry['bytes']}")
            if entry.get("rows") is not None:
                try:
                    rows = csv_rows(content)
                except (UnicodeDecodeError, csv.Error) as e:
                    problems.append(f"{name}: not a CSV file ({e})")
                    continue
                if rows != entry["rows"]:
                    problems.append(f"{name}: {rows} rows, the manifest says {entry['rows']}")
        if expect is not None:
            runs = [run for run, _ in expect]
            for run, template in expect:
                prefix = f"reports/{run}-"
                if not any(n.startswith(prefix) and n.endswith(".pdf") for n in names):
                    problems.append(f"report {template} ({run}): not in the package")
            for name in sorted(set(names)):
                if name.startswith("reports/") and not any(name.startswith(f"reports/{run}-") for run in runs):
                    problems.append(f"{name}: a report the request did not ask for")
        if seal_hash is not None:
            head = manifest.get("integrityHead") or {}
            if (head.get("sealHash") or "").lower() != seal_hash.lower():
                problems.append(f"{MANIFEST}: seal chain head {head.get('sealHash')!r}, expected {seal_hash!r}")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description="Checks an audit evidence package without the system.")
    parser.add_argument("package", help="the package (ZIP)")
    parser.add_argument("--expect", help="the answer of FIN_AUDIT_PACKAGE (JSON): its reports must be in the package")
    parser.add_argument("--seal-hash", help="the seal chain's head hash kept outside the system")
    parser.add_argument("--package-sha256", help="the package's SHA-256 the exporter handed over outside the system")
    parser.add_argument("--manifest-sha256", help="the manifest's SHA-256 the exporter handed over outside the system")
    args = parser.parse_args(argv)
    expect = None
    if args.expect:
        try:
            with open(args.expect, encoding="utf-8") as f:
                expect = expected_runs(json.load(f))
        except (OSError, ValueError) as e:
            print(f"cannot read {args.expect}: {e}", file=sys.stderr)
            return 2
    problems = check(args.package, expect, args.seal_hash, args.manifest_sha256, args.package_sha256)
    for problem in problems:
        print(f"FAIL {problem}")
    if problems:
        return 1
    with zipfile.ZipFile(args.package) as archive:
        manifest_bytes = archive.read(MANIFEST)
    manifest = json.loads(manifest_bytes.decode("utf-8"))
    head = manifest.get("integrityHead") or {}
    print(f"OK {len(manifest.get('files') or [])} files as listed; exported {manifest.get('exportedTime')} by "
          f"{manifest.get('exportedBy')}; seal chain head {head.get('sealNo')} {head.get('sealHash')}; "
          f"manifest SHA-256 {hashlib.sha256(manifest_bytes).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
