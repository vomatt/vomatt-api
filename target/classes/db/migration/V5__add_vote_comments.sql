-- Vote comments table
CREATE TABLE IF NOT EXISTS vote_comments (
    id BIGSERIAL PRIMARY KEY,
    vote_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (vote_id) REFERENCES votes (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Indexes for better performance
CREATE INDEX IF NOT EXISTS idx_vote_comments_vote_id ON vote_comments(vote_id);
CREATE INDEX IF NOT EXISTS idx_vote_comments_user_id ON vote_comments(user_id);
CREATE INDEX IF NOT EXISTS idx_vote_comments_created_at ON vote_comments(created_at);
CREATE INDEX IF NOT EXISTS idx_vote_comments_is_deleted ON vote_comments(is_deleted);
