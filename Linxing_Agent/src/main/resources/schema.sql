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

COMMENT ON TABLE users IS '系统用户';
COMMENT ON COLUMN users.id IS '用户唯一ID';
COMMENT ON COLUMN users.username IS '登录用户名';
COMMENT ON COLUMN users.password_hash IS '密码哈希值';
COMMENT ON COLUMN users.created_at IS '账户创建时间';

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
    chunk_strategy  VARCHAR(50) DEFAULT 'auto',
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_user_status ON documents(user_id, status);
CREATE INDEX IF NOT EXISTS idx_documents_user_id ON documents(user_id);

COMMENT ON TABLE documents IS '原始文档元信息';
COMMENT ON COLUMN documents.id IS '文档唯一ID';
COMMENT ON COLUMN documents.user_id IS '所属用户ID';
COMMENT ON COLUMN documents.file_name IS '文件名';
COMMENT ON COLUMN documents.file_path IS '文件存储路径';
COMMENT ON COLUMN documents.file_size IS '文件大小（字节）';
COMMENT ON COLUMN documents.file_type IS '文件类型，如 docx, pdf, markdown, txt';
COMMENT ON COLUMN documents.status IS '处理状态：processing/completed/failed';
COMMENT ON COLUMN documents.chunk_strategy IS '文档最终采用的分块策略';
COMMENT ON COLUMN documents.created_at IS '上传时间';

-- =============================================
-- 3. 分块索引表 chunks（核心关联表，支持分层 Small-to-Big 检索）
-- =============================================
CREATE TABLE IF NOT EXISTS chunks (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    document_id     INT NOT NULL,
    parent_chunk_id INT,
    chunk_level     SMALLINT DEFAULT 1,
    chunk_text      TEXT NOT NULL,
    chunk_type      VARCHAR(30) DEFAULT 'general',
    title_path      TEXT,
    context_prefix  TEXT,
    source_strategy VARCHAR(50),
    is_searchable   BOOLEAN DEFAULT TRUE,
    ts_content      TSVECTOR,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT chunks_document_id_fkey FOREIGN KEY(document_id) REFERENCES documents(id),
    CONSTRAINT chunks_parent_chunk_id_fkey FOREIGN KEY(parent_chunk_id) REFERENCES chunks(id)
);

CREATE INDEX IF NOT EXISTS idx_chunks_user_doc ON chunks(user_id, document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_user_id ON chunks(user_id);
CREATE INDEX IF NOT EXISTS idx_chunks_parent ON chunks(parent_chunk_id) WHERE (parent_chunk_id IS NOT NULL);
CREATE INDEX IF NOT EXISTS idx_chunks_level ON chunks(chunk_level);
CREATE INDEX IF NOT EXISTS idx_chunks_type ON chunks(chunk_type);
CREATE INDEX IF NOT EXISTS idx_chunks_source_strategy ON chunks(source_strategy);
CREATE INDEX IF NOT EXISTS idx_chunks_ts_content ON chunks USING GIN (ts_content);

COMMENT ON TABLE chunks IS '文档切片表（chunk），支持分层、Small-to-Big检索和全文检索';
COMMENT ON COLUMN chunks.id IS 'chunk唯一ID';
COMMENT ON COLUMN chunks.user_id IS '所属用户ID';
COMMENT ON COLUMN chunks.document_id IS '所属文档ID';
COMMENT ON COLUMN chunks.parent_chunk_id IS '父chunk ID，用于Small-to-Big';
COMMENT ON COLUMN chunks.chunk_level IS '层级：1=大粒度块，2=小粒度检索块';
COMMENT ON COLUMN chunks.chunk_text IS 'chunk的原始文本型内容';
COMMENT ON COLUMN chunks.chunk_type IS '块类型：general, section, qa_pair, context_weak, code, table 等';
COMMENT ON COLUMN chunks.title_path IS '标题路径，如"项目Alpha > 2025-01 会议 > 决策"，用于增强语义和前端展示';
COMMENT ON COLUMN chunks.context_prefix IS '为弱上下文块生成的背景描述文本，检索时拼接到向量化文本前';
COMMENT ON COLUMN chunks.source_strategy IS '该块的生成策略名称';
COMMENT ON COLUMN chunks.is_searchable IS '是否参与向量检索（仅小粒度块为true）';
COMMENT ON COLUMN chunks.ts_content IS '全文检索向量，由chunk_text使用to_tsvector生成';
COMMENT ON COLUMN chunks.created_at IS '创建时间';

-- =============================================
-- 4. 向量存储表 embeddings（pgvector 核心表）
-- =============================================
CREATE TABLE IF NOT EXISTS embeddings (
    id              SERIAL PRIMARY KEY,
    user_id         INT,
    document_id     INT NOT NULL,
    chunk_id        INT NOT NULL,
    embedding       vector NOT NULL,
    "text"          TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT embeddings_chunk_id_fkey FOREIGN KEY(chunk_id) REFERENCES chunks(id),
    CONSTRAINT fk_embeddings_document FOREIGN KEY(document_id) REFERENCES documents(id),
    CONSTRAINT fk_embeddings_chunk FOREIGN KEY(chunk_id) REFERENCES chunks(id)
);

CREATE INDEX IF NOT EXISTS idx_embeddings_user_id ON embeddings(user_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_doc_id ON embeddings(document_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_chunk_id ON embeddings(chunk_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_meta_chunk_type ON embeddings(((metadata ->> 'chunk_type')));
CREATE INDEX IF NOT EXISTS idx_embeddings_meta_parent_id ON embeddings(((metadata ->> 'parent_chunk_id')));

COMMENT ON TABLE embeddings IS '向量存储，映射chunks表';
COMMENT ON COLUMN embeddings.id IS '自增主键ID';
COMMENT ON COLUMN embeddings.user_id IS '所属用户ID（冗余，便于按用户过滤）';
COMMENT ON COLUMN embeddings.document_id IS '所属文档ID';
COMMENT ON COLUMN embeddings.chunk_id IS '关联的chunk ID，一对一关系';
COMMENT ON COLUMN embeddings.embedding IS '向量数据，pgvector类型';
COMMENT ON COLUMN embeddings.text IS '可空；实际输入embedding模型的文本，调试使用';
COMMENT ON COLUMN embeddings.metadata IS '冗余存储的chunk元数据，用于快速过滤：包含parent_chunk_id, chunk_type, title_path, strategy等';

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

COMMENT ON TABLE activity_logs IS '用户操作日志，用于审计和检索质量分析';
COMMENT ON COLUMN activity_logs.id IS '日志ID';
COMMENT ON COLUMN activity_logs.user_id IS '用户ID';
COMMENT ON COLUMN activity_logs.action_type IS '操作类型：upload/query/delete';
COMMENT ON COLUMN activity_logs.target_type IS '操作目标类型：document/chunk';
COMMENT ON COLUMN activity_logs.target_id IS '操作目标ID';
COMMENT ON COLUMN activity_logs.details IS '操作详情（JSON），记录策略、耗时、召回结果等';
COMMENT ON COLUMN activity_logs.created_at IS '操作时间';

-- =============================================
-- 6、聊天会话表 chat_sessions
-- =============================================

CREATE TABLE IF NOT EXISTS chat_sessions (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    title           VARCHAR(200) DEFAULT '新对话',
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

COMMENT ON TABLE chat_sessions IS '聊天会话表，每次新建聊天会创建一个会话，即一棵对话树的根';
COMMENT ON COLUMN chat_sessions.id IS '会话唯一ID';
COMMENT ON COLUMN chat_sessions.user_id IS '所属用户ID';
COMMENT ON COLUMN chat_sessions.title IS '会话标题，用户建立新聊天必须手动输入';
COMMENT ON COLUMN chat_sessions.created_at IS '会话创建时间';
COMMENT ON COLUMN chat_sessions.updated_at IS '会话最后活跃时间';

CREATE INDEX idx_chat_sessions_user_updated 
    ON chat_sessions(user_id, updated_at DESC);

-- =============================================
-- 7、聊天消息表 chat_messages
-- =============================================
CREATE TABLE IF NOT EXISTS chat_messages (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    session_id      INT NOT NULL,
    parent_id       INT,
    role            VARCHAR(10) NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    sources         JSONB DEFAULT '[]',
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT chat_messages_session_id_fkey 
        FOREIGN KEY(session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

COMMENT ON TABLE chat_messages IS '聊天消息表，会话中的每条问答消息，通过 parent_id 构成树形结构';
COMMENT ON COLUMN chat_messages.id IS '消息唯一ID';
COMMENT ON COLUMN chat_messages.user_id IS '所属用户ID，冗余以支持按用户快速查询';
COMMENT ON COLUMN chat_messages.session_id IS '所属会话ID（即树的根节点ID），用于一次拉取整棵树的所有消息';
COMMENT ON COLUMN chat_messages.parent_id IS '直接父消息ID，NULL 表示该消息是树根下的一级节点；沿此字段回溯可得从根到当前节点的完整路径';
COMMENT ON COLUMN chat_messages.role IS '角色：user=用户提问，assistant=助手回答';
COMMENT ON COLUMN chat_messages.content IS '消息正文内容';
COMMENT ON COLUMN chat_messages.sources IS '助手回答时引用的来源列表，JSON数组格式，如 [{"chunkId":1,"fileName":"xxx.md","titlePath":"章节"}]';
COMMENT ON COLUMN chat_messages.created_at IS '消息创建时间';

-- 索引：按会话拉取所有消息（构造树的核心查询）
CREATE INDEX idx_chat_messages_session_id 
    ON chat_messages(session_id);
-- 索引：按用户+会话过滤
CREATE INDEX idx_chat_messages_user_session 
    ON chat_messages(user_id, session_id);
-- 索引：支持按 parent_id 回溯路径
CREATE INDEX idx_chat_messages_parent_id 
    ON chat_messages(parent_id) WHERE parent_id IS NOT NULL;
