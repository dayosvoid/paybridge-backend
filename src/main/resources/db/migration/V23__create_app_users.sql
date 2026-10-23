-- Users who can sign in. A customer user owns exactly one account number (opened at registration);
-- an admin owns none. Ids start at 1000 so a user id never collides with the demo and system customer ids,
-- because the account's customer_id is the user id.
CREATE SEQUENCE app_users_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE app_users (
    id             BIGINT       NOT NULL PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(255) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    account_number VARCHAR(10),
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_app_users_email UNIQUE (email),
    CONSTRAINT app_users_role_check CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

CREATE INDEX idx_app_users_account ON app_users (account_number);
