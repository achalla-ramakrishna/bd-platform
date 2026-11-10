-- Account profile for targeting (backlog item 1): exact headcount and open engineering roles.
-- headcount_band and year_established already exist (V2).

ALTER TABLE account
    ADD COLUMN employee_count    INT          NULL AFTER headcount_band,
    ADD COLUMN open_roles        INT          NULL AFTER year_established,
    ADD COLUMN open_roles_as_of  DATE         NULL AFTER open_roles,
    ADD COLUMN open_roles_source VARCHAR(500) NULL AFTER open_roles_as_of,
    ADD CONSTRAINT ck_account_employee_count CHECK (employee_count IS NULL OR employee_count >= 0),
    ADD CONSTRAINT ck_account_open_roles CHECK (open_roles IS NULL OR open_roles >= 0);

CREATE INDEX ix_account_year_established ON account (year_established);
