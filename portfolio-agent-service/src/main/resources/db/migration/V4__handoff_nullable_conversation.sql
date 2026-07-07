-- Allow handoff tickets without an existing conversation (e.g. first-message handoff)
alter table handoff_ticket alter column conversation_id drop not null;
alter table handoff_ticket drop constraint handoff_ticket_conversation_id_fkey;
