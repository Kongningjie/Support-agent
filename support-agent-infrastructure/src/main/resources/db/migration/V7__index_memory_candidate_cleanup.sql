CREATE INDEX idx_user_memory_status_created
    ON user_memory (status, created_at, id);
