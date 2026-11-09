-- Pro poll (set by an admin): only phone-verified users may vote, Voter Visibility is NOBODY and no Ballot is Anonymous
ALTER TABLE vomatt.votes ADD COLUMN IF NOT EXISTS pro BOOLEAN NOT NULL DEFAULT false;
-- Results stay Sealed until this time even after the Poll has Ended; null = unsealed once Ended. Never before end_time
ALTER TABLE vomatt.votes ADD COLUMN IF NOT EXISTS reveal_at TIMESTAMPTZ NULL;
