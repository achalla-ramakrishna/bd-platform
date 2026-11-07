-- Upload batches (module: imports, ADR-0006). Files themselves are not stored.

CREATE TABLE import_batch (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type   VARCHAR(20)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    total_rows    INT          NOT NULL,
    created_count INT          NOT NULL DEFAULT 0,
    updated_count INT          NOT NULL DEFAULT 0,
    failed_count  INT          NOT NULL DEFAULT 0,
    uploaded_by   BIGINT       NOT NULL,
    uploaded_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_import_batch PRIMARY KEY (id),
    CONSTRAINT fk_import_batch_user FOREIGN KEY (uploaded_by) REFERENCES app_user (id),
    CONSTRAINT ck_import_batch_entity CHECK (entity_type IN ('ACCOUNTS','CONTACTS','CONTRACTS','ENGINEERS','DEPLOYMENTS')),
    CONSTRAINT ck_import_batch_status CHECK (status IN ('FAILED','COMMITTED'))
);

CREATE TABLE import_row_error (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    batch_id   BIGINT        NOT NULL,
    row_no      INT          NOT NULL,
    column_name VARCHAR(60)  NULL,
    message    VARCHAR(500)  NOT NULL,
    CONSTRAINT pk_import_row_error PRIMARY KEY (id),
    CONSTRAINT fk_import_row_error_batch FOREIGN KEY (batch_id) REFERENCES import_batch (id) ON DELETE CASCADE
);
