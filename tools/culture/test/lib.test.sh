#!/bin/sh
# Tests of tools/culture/lib.sh without a database: stand-ins for the PostgreSQL tools on PATH.
#   tools/culture/test/lib.test.sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir "$WORK/bin"
for tool in pg_dump psql; do printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/$tool"; done
# pg_restore -a -t sys_file -f - <dump>: the data section as pg_restore prints it.
cat > "$WORK/bin/pg_restore" <<'STUB'
#!/bin/sh
cat <<'OUT'
--
-- Data for Name: sys_file; Type: TABLE DATA; Schema: public; Owner: culture
--

COPY public.sys_file (file_id, policy, content_type) FROM stdin;
01a0e5fe-199d-7712-925d-88d4582c0605	culture.image	image/jpeg
01a0e5fc-0ead-7807-aebb-217527025e3b	culture.image	image/jpeg
\.

OUT
STUB
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" CULTURE_BACKUP_MODE=direct JABIZ_FILES_LOCAL_ROOT="$WORK/files"

ok() { printf 'ok   %s\n' "$1"; }
no() { printf 'FAIL %s\n' "$1"; exit 1; }

# lib.sh finds its place from $0: source it as if from tools/culture.
. "$HERE/../lib.sh"

dump_file_rows /dev/null > "$WORK/rows"
[ "$(cat "$WORK/rows")" = "$(printf '%s\n' 01a0e5fc-0ead-7807-aebb-217527025e3b 01a0e5fe-199d-7712-925d-88d4582c0605)" ] \
    && ok "the file rows of a dump are its sys_file data" || no "dump_file_rows: $(cat "$WORK/rows")"

F=$WORK/files
mkdir -p "$F/2026/09/01a0e5fc-0ead-7807-aebb-217527025e3b" "$F/2026/09/01a0e5fe-199d-7712-925d-88d4582c0605" "$F/.uploads"
for id in 01a0e5fc-0ead-7807-aebb-217527025e3b 01a0e5fe-199d-7712-925d-88d4582c0605; do
    echo original > "$F/2026/09/$id/original"
    echo variant > "$F/2026/09/$id/w320"
done
echo receiving > "$F/.uploads/5b0f.part"
echo partial > "$F/2026/09/.01a0e5fe-199d-7712-925d-88d4582c0605.9c1e.part"
archive_files "$F" "$WORK/files.tar.gz"
tar -tzf "$WORK/files.tar.gz" | grep -q 'part$\|\.uploads' && no "the upload area or a partial object was archived" \
    || ok "the upload area and partial objects are left out"
tar -tzf "$WORK/files.tar.gz" | grep -q '01a0e5fe-199d-7712-925d-88d4582c0605/w320$' \
    && ok "variants are archived" || no "a variant is missing"

archived_objects "$WORK/files.tar.gz" > "$WORK/objects"
report_missing "$WORK/rows" "$WORK/objects" 2> /dev/null && ok "every row has its object" || no "report_missing"

rm -r "$F/2026/09/01a0e5fe-199d-7712-925d-88d4582c0605"
archive_files "$F" "$WORK/files.tar.gz"
archived_objects "$WORK/files.tar.gz" > "$WORK/objects"
if report_missing "$WORK/rows" "$WORK/objects" 2> "$WORK/report"; then
    no "a row without its object was not reported"
fi
grep -q '01a0e5fe-199d-7712-925d-88d4582c0605' "$WORK/report" && ok "a row without its object is reported, by id" \
    || no "the report does not name the file: $(cat "$WORK/report")"

echo "all tests passed"
