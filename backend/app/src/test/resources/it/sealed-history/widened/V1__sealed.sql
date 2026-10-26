CREATE TABLE it_sh_sealed (id bigint PRIMARY KEY, qty integer, note varchar(40), amount numeric(12, 2));
SELECT jabiz_protect_append_only('it_sh_sealed');
