-- A mail may leave through a server of the application's naming instead of the platform's (decision D56): the key the
-- application's MailSenderSource resolves. Null: the platform's server. Appending a column leaves the append-only
-- protection as it is.
ALTER TABLE sys_mail_message ADD COLUMN sender_key varchar(100);
