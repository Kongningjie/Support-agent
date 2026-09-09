ALTER TABLE resolved_case
    ADD CONSTRAINT uk_resolved_case_source_ticket UNIQUE (source_ticket_id);
