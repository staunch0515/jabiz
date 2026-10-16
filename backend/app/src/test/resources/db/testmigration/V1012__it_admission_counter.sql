-- Counters of the admission tests (com.jabiz.app.it.fixture.ItAdmissionFixtures, decision D45): read and written back
-- in two statements by runs that only the named lock keeps apart.
CREATE TABLE it_admission_counter (
    k varchar(100) PRIMARY KEY,
    n bigint       NOT NULL
);
