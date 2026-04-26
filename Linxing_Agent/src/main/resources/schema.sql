-- =============================================
-- Linxing Agent RAG 系统 - 数据库表结构
-- 数据库: PostgreSQL + pgvector 扩展
-- 用途: 多用户隔离 RAG + 统计分析
-- =============================================

-- 启用 pgvector 扩展（如果尚未启用）
CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================
-- 1. 用户表 users
-- =============================================
CREATE TABLE IF NOT EXISTS users (
    id              SERIAL PRIMARY KEY,
    username        VARCHAR(50) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- =============================================
-- 2. 文档表 documents
-- =============================================
CREATE TABLE IF NOT EXISTS documents (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    file_size       BIGINT DEFAULT 0,
    file_type       VARCHAR(50) DEFAULT '',
    status          VARCHAR(20) DEFAULT 'processing' CHECK (status IN ('processing', 'completed', 'failed')),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_user_status ON documents(user_id, status);
CREATE INDEX IF NOT EXISTS idx_documents_user_id ON documents(user_id);

-- =============================================
-- 3. 分块索引表 chunks（核心关联表）
-- =============================================
CREATE TABLE IF NOT EXISTS chunks (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    document_id     INT NOT NULL,
    chunk_text      TEXT NOT NULL,
    page_number     INT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunks_user_doc ON chunks(user_id, document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_user_id ON chunks(user_id);

-- =============================================
-- 4. 向量存储表 embeddings（pgvector 核心表）
-- 自定义结构：增加 user_id 列支持多租户隔离
-- 必须在应用启动前手动创建此表，否则 LangChain4j 会用默认结构自动创建
-- =============================================
CREATE TABLE IF NOT EXISTS embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         INT,
    document_id     INT,
    chunk_id        INT,
    embedding       vector(512) NOT NULL,
    "text"          TEXT,
    metadata        JSONB
);

CREATE INDEX IF NOT EXISTS idx_embeddings_vector ON embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_embeddings_user_id ON embeddings(user_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_doc_id ON embeddings(document_id);

-- =============================================
-- 5. 操作日志表 activity_logs
-- =============================================
CREATE TABLE IF NOT EXISTS activity_logs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    action_type     VARCHAR(50) NOT NULL CHECK (action_type IN ('upload', 'query', 'delete')),
    target_type     VARCHAR(50) CHECK (target_type IN ('document', 'chunk')),
    target_id       VARCHAR(100),
    details         JSONB,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_activity_logs_user_time ON activity_logs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_logs_action_date ON activity_logs(action_type, created_at);

-- =============================================
-- 示例数据（可选，用于测试）
-- =============================================
-- INSERT INTO users (username, password_hash) VALUES ('admin', '$2a$10$...');
