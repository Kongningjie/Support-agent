ALTER TABLE managed_document
    ADD COLUMN active_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE
                WHEN deleted = FALSE AND status <> 'ARCHIVED' THEN content_hash
                ELSE NULL
            END
        ) STORED COMMENT '未删除且未归档文档的内容哈希，仅用于并发唯一约束',
    ADD UNIQUE KEY uk_managed_document_active_content_hash (active_content_hash);
