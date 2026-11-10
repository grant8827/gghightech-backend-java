-- Run once against the Railway Postgres as the admin user (POSTGRES_USER),
-- e.g.  railway connect Postgres  then  \i railway-james-db.sql
-- Replace the password with a long random value (openssl rand -base64 32)
-- and put the same value in /etc/james/james.env as JAMES_DB_PASSWORD.

CREATE ROLE james LOGIN PASSWORD 'REPLACE_WITH_LONG_RANDOM_PASSWORD'
    CONNECTION LIMIT 20;

CREATE DATABASE james OWNER james;

-- James creates its own tables on first start inside the "james" database.
-- It gets no privileges on the app's "railway" database, so it cannot read
-- the email schema, FastAPI tables, or anything else there.
REVOKE ALL ON DATABASE james FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE james TO james;
