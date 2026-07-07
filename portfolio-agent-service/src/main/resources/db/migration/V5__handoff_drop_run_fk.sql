-- Drop FK on run_id so handoff can be created without a pre-existing agent_run record
alter table handoff_ticket drop constraint handoff_ticket_run_id_fkey;
