CREATE TABLE it_sh_sealed (id bigint PRIMARY KEY, amount numeric(12, 2), note varchar(40));
SELECT jabiz_protect_append_only('it_sh_sealed');
