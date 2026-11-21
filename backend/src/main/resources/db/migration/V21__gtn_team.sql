-- GTN phase 4 (ADR-0021): when a nudge or beep was due, so "sent on time" can be measured.
ALTER TABLE network_touch ADD COLUMN due_on DATE NULL;
