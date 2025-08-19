-- 會員角色表
CREATE TABLE IF NOT EXISTS roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

-- 會員表
CREATE TABLE IF NOT EXISTS members (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(50) NOT NULL UNIQUE,
    phone_number VARCHAR(20) UNIQUE,
    password VARCHAR(120) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP,
    login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    verification_code VARCHAR(64),
    verification_code_expiry TIMESTAMP,
    points INTEGER NOT NULL DEFAULT 0,
    membership_level VARCHAR(20) NOT NULL DEFAULT 'BASIC',
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- 會員角色關聯表
CREATE TABLE IF NOT EXISTS member_roles (
    member_id VARCHAR(36) NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (member_id, role_id),
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

-- 會員偏好設定表
CREATE TABLE IF NOT EXISTS member_preferences (
    id BIGSERIAL PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    preference_key VARCHAR(50) NOT NULL,
    preference_value TEXT,
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    UNIQUE (member_id, preference_key)
);

-- 會員活動記錄表
CREATE TABLE IF NOT EXISTS member_activities (
    id BIGSERIAL PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    activity_type VARCHAR(30) NOT NULL,
    activity_description TEXT,
    ip_address VARCHAR(45),
    user_agent TEXT,
    timestamp TIMESTAMP NOT NULL,
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
);

-- 會員重置密碼令牌表
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    token VARCHAR(64) NOT NULL UNIQUE,
    expiry_date TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
);

-- 會員刷新令牌表
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    expiry_date TIMESTAMP NOT NULL,
    FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
);

-- 初始化角色
INSERT INTO roles (name) VALUES 
    ('ROLE_USER'), 
    ('ROLE_MODERATOR'), 
    ('ROLE_ADMIN')
ON CONFLICT (name) DO NOTHING;