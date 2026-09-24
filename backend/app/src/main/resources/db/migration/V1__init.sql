create table todo (
    id    bigint generated always as identity primary key,
    title text    not null,
    done  boolean not null default false
);