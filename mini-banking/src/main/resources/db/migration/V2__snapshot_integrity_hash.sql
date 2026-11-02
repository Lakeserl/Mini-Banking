ALTER TABLE snapshots ADD COLUMN IF NOT EXISTS last_event_id UUID;
ALTER TABLE snapshots ADD COLUMN IF NOT EXISTS state_hash VARCHAR(64);

-- Snapshots are rebuildable caches; discard old rows rather than trusting unverified state.
DELETE FROM snapshots;

ALTER TABLE snapshots ALTER COLUMN last_event_id SET NOT NULL;
ALTER TABLE snapshots ALTER COLUMN state_hash SET NOT NULL;

DO $$
BEGIN
	IF NOT EXISTS (
				SELECT 1
				FROM pg_constraint
				WHERE conname = 'ck_snapshots_state_hash_length'
					AND conrelid = 'snapshots'::regclass
	) THEN
		ALTER TABLE snapshots
			ADD CONSTRAINT ck_snapshots_state_hash_length CHECK (length(state_hash) = 64);
	END IF;
END;
$$;
