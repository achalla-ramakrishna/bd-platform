-- A connection's photo (ADR-0014): cropped by the team member from a screenshot they took, or uploaded by hand.
-- Stored re-encoded as a small JPEG; removed with the connection.
CREATE TABLE connection_photo (
    connection_id BIGINT       NOT NULL PRIMARY KEY,
    content_type  VARCHAR(40)  NOT NULL,
    bytes         MEDIUMBLOB   NOT NULL,
    updated_by    BIGINT       NOT NULL,
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_connection_photo_connection FOREIGN KEY (connection_id) REFERENCES connection (id) ON DELETE CASCADE,
    CONSTRAINT fk_connection_photo_user FOREIGN KEY (updated_by) REFERENCES app_user (id)
);
