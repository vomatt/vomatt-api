-- Vote system tables

-- Main votes table
CREATE TABLE IF NOT EXISTS votes (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    creator_id VARCHAR(36) NOT NULL,
    start_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    allow_multiple_choices BOOLEAN NOT NULL DEFAULT FALSE,
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    max_choices INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Vote options table
CREATE TABLE IF NOT EXISTS vote_options (
    id VARCHAR(36) PRIMARY KEY,
    text VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    vote_id VARCHAR(36) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE
);

-- User votes table - tracks individual votes
CREATE TABLE IF NOT EXISTS user_votes (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    vote_id VARCHAR(36) NOT NULL,
    option_id VARCHAR(36) NOT NULL,
    voted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    FOREIGN KEY (option_id) REFERENCES vote_options (id) ON DELETE CASCADE,
    UNIQUE(user_id, vote_id, option_id)
);

-- Indexes for better performance
CREATE INDEX IF NOT EXISTS idx_votes_creator_id ON votes(creator_id);
CREATE INDEX IF NOT EXISTS idx_votes_is_active ON votes(is_active);
CREATE INDEX IF NOT EXISTS idx_votes_start_time ON votes(start_time);
CREATE INDEX IF NOT EXISTS idx_votes_end_time ON votes(end_time);
CREATE INDEX IF NOT EXISTS idx_votes_created_at ON votes(created_at);

CREATE INDEX IF NOT EXISTS idx_vote_options_vote_id ON vote_options(vote_id);
CREATE INDEX IF NOT EXISTS idx_vote_options_display_order ON vote_options(vote_id, display_order);

CREATE INDEX IF NOT EXISTS idx_user_votes_user_id ON user_votes(user_id);
CREATE INDEX IF NOT EXISTS idx_user_votes_vote_id ON user_votes(vote_id);
CREATE INDEX IF NOT EXISTS idx_user_votes_option_id ON user_votes(option_id);
CREATE INDEX IF NOT EXISTS idx_user_votes_voted_at ON user_votes(voted_at);