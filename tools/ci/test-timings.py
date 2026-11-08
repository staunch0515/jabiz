#!/usr/bin/env python3
"""Writes a module's test-timings.txt from the JUnit XML results of one run (ROADMAP 17zz13).

    python3 tools/ci/test-timings.py backend/<module>/build/test-results/test > backend/<module>/test-timings.txt

One line per test class, "<class> <seconds>", longest first. The build splits a module's classes into CI shards by
these times (backend/build-logic TestShards); a class missing from the file still runs, in a shard picked by its name,
so the file only needs regenerating when the shards drift apart (a shard much slower than the others).

A class's time is the time its result reports, except for a jqwik container: its reported time spans the whole run
(it stays open until the engine ends), so a class reporting over five minutes and more than a hundred times the sum of
its tests counts the sum of its tests.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def class_time(suite):
    reported = float(suite.get("time") or 0)
    tests = sum(float(case.get("time") or 0) for case in suite.iter("testcase"))
    if reported > 300 and reported > 100 * max(tests, 0.001):
        return tests
    return reported


def main(argv):
    if len(argv) != 2 or not os.path.isdir(argv[1]):
        sys.stderr.write("usage: test-timings.py <test results directory>\n")
        return 2
    times = {}
    for path in sorted(glob.glob(os.path.join(argv[1], "TEST-*.xml"))):
        suite = ET.parse(path).getroot()
        name = suite.get("name")
        if name:
            times[name] = times.get(name, 0.0) + class_time(suite)
    if not times:
        sys.stderr.write("no TEST-*.xml in %s\n" % argv[1])
        return 1
    print("# Test class times of one run, for splitting the tests into CI shards (ROADMAP 17zz13).")
    print("# Regenerate: python3 tools/ci/test-timings.py <module>/build/test-results/test > <module>/test-timings.txt")
    for name, seconds in sorted(times.items(), key=lambda item: (-item[1], item[0])):
        print("%s %.1f" % (name, seconds))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
