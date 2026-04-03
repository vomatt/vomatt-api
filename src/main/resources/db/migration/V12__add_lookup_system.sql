-- 字典表（通用 key-value 配置，支援 JSON 值與階層結構）
CREATE TABLE IF NOT EXISTS template_lookup (
    id UUID PRIMARY KEY,
    lookup_type VARCHAR(50) NOT NULL,
    lookup_key VARCHAR(50) NOT NULL,
    lookup_value JSON NOT NULL,
    seq INT NOT NULL DEFAULT 0,
    parent_type VARCHAR(50),
    parent_key VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(100),
    frontend_using BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_lookup_type_key UNIQUE (lookup_type, lookup_key)
);

-- 依 type + is_active 加速公開查詢
CREATE INDEX idx_lookup_type_active ON template_lookup (lookup_type, is_active);

-- 依父節點查詢子項目
CREATE INDEX idx_lookup_parent ON template_lookup (parent_type, parent_key);

-- 前端用字典查詢
CREATE INDEX idx_lookup_frontend ON template_lookup (frontend_using, is_active);
