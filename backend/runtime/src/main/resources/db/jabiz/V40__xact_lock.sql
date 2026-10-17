-- A named lock taken with a wait shorter than the transaction's own, in one statement (docs/design/06-process.md
-- section 4.1; decision D46; ROADMAP phase 16p, T1). HoldLock used to send four: read lock_timeout, set the shorter
-- one, take the lock, put the transaction's back. The function does the same in that order within one call; the
-- order is plpgsql's, not a query plan's.
--
-- A wait that runs out fails the statement with SQLSTATE 55P03 (409 busy() in the application): the transaction is
-- aborted then, so the transaction's own timeout need not be put back. A lock that HoldLock takes with the
-- transaction's own timeout (the usual case) needs no function: it is the one statement of the lock itself.
-- It names built-in functions only, resolved in pg_catalog whatever the caller's search_path holds.
CREATE FUNCTION jabiz_xact_lock(p_key text, p_shared boolean, p_timeout text) RETURNS boolean
    LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog AS $$
DECLARE
    previous text := current_setting('lock_timeout');
BEGIN
    PERFORM set_config('lock_timeout', p_timeout, true);
    IF p_shared THEN
        PERFORM pg_advisory_xact_lock_shared(hashtextextended(p_key, 0));
    ELSE
        PERFORM pg_advisory_xact_lock(hashtextextended(p_key, 0));
    END IF;
    PERFORM set_config('lock_timeout', previous, true);
    RETURN true;
END $$;
