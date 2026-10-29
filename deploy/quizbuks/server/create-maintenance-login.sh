#!/usr/bin/env bash
# Usage: create-maintenance-login.sh <database>
# Creates (or, run again, gives a new password to) the maintenance login of one application's database, for the
# platform's purge of an erased account's personal data (ERASURE_PURGE, platform docs/design/10-security.md section
# 17.2), and prints the three JABIZ_ERASURE_DATASOURCE_* lines for that environment's .env.
#
# The login is <database>_maint, a member of the server's role jabiz_maintenance (created here once per server, never
# by a migration): only such a login, in a purge's transaction, gets past the append-only tables' guard. It may read,
# insert and update the database's tables (the purge overwrites rows and records what it did), never delete or
# truncate them. The application's own login (create-db.sh's <database>) must never be a member: the script refuses
# if it is, as the application's startup check would.
set -euo pipefail

db="${1:?usage: create-maintenance-login.sh <database>}"
if [[ ! "$db" =~ ^[a-z][a-z0-9_]*$ ]]; then
  echo "invalid database name (use lowercase letters, digits, underscore)" >&2
  exit 1
fi
login="${db}_maint"
pw="$(openssl rand -hex 24)"

psql_as_postgres() {
  docker exec -i postgres psql -U postgres -v ON_ERROR_STOP=1 -tA "$@"
}

if [[ "$(psql_as_postgres -c "SELECT 1 FROM pg_database WHERE datname = '${db}'")" != "1" ]]; then
  echo "no database ${db} (create it first: create-db.sh ${db})" >&2
  exit 1
fi
if [[ "$(psql_as_postgres -c "SELECT 1 FROM pg_roles WHERE rolname = '${login}'")" == "1" ]]; then
  create="ALTER ROLE \"${login}\" LOGIN PASSWORD '${pw}';"
else
  create="CREATE ROLE \"${login}\" LOGIN PASSWORD '${pw}';"
fi

# The DO block's dollar quotes are escaped from the shell (\$\$); the password is hex, so it needs no quoting.
psql_as_postgres -q -d "$db" <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jabiz_maintenance') THEN
    CREATE ROLE jabiz_maintenance NOLOGIN;
  END IF;
  IF pg_has_role('${db}', 'jabiz_maintenance', 'MEMBER') THEN
    RAISE EXCEPTION 'the application login ${db} is a member of jabiz_maintenance: revoke that first';
  END IF;
END \$\$;
${create}
GRANT jabiz_maintenance TO "${login}";
REVOKE ALL ON DATABASE "${db}" FROM "${login}";
GRANT CONNECT ON DATABASE "${db}" TO "${login}";
GRANT USAGE ON SCHEMA public TO "${login}";
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO "${login}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${login}";
-- Tables and sequences later migrations create (as the application's login) are granted the same.
ALTER DEFAULT PRIVILEGES FOR ROLE "${db}" IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO "${login}";
ALTER DEFAULT PRIVILEGES FOR ROLE "${db}" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "${login}";
SQL

# R2DBC, as SPRING_R2DBC_URL: not the JDBC URL Flyway takes (the platform refuses it at startup).
echo "JABIZ_ERASURE_DATASOURCE_URL=r2dbc:postgresql://postgres:5432/${db}"
echo "JABIZ_ERASURE_DATASOURCE_USERNAME=${login}"
echo "JABIZ_ERASURE_DATASOURCE_PASSWORD=${pw}"
