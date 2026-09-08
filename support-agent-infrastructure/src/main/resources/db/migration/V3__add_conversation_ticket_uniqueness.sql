ALTER TABLE ticket
    ADD UNIQUE KEY uk_ticket_conversation_source_turn (conversation_id, source_turn_id);
