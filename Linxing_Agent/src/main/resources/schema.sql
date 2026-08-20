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
    file_path       VARCHAR(500),
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
    sort_order      INTEGER,
    node_metadata   JSONB DEFAULT '[]'::jsonb,
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
CREATE INDEX IF NOT EXISTS idx_chunks_document_sort ON chunks(document_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_chunks_node_metadata_gin ON chunks USING GIN (node_metadata);

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
COMMENT ON COLUMN chunks.source_strategy IS '该块的生成策略名称（**Node体系下已不再使用，但也可以重新接入使用**）';
COMMENT ON COLUMN chunks.is_searchable IS '是否参与向量检索（仅小粒度块为true）';
COMMENT ON COLUMN chunks.ts_content IS '全文检索向量，由chunk_text使用to_tsvector生成';
COMMENT ON COLUMN chunks.sort_order IS '同一文档内 chunk 的全局排序序号，保证与原始文档内容顺序一致';
COMMENT ON COLUMN chunks.node_metadata IS 'Chunk 内所有 Node 的元信息，JSON数组格式，记录类型/位置/语义描述等，用于前端还原图片/代码/表格原文形态';
COMMENT ON COLUMN chunks.created_at IS '创建时间';

-- =============================================
-- 4. 向量存储表 embeddings（pgvector 核心表）
-- =============================================
CREATE TABLE IF NOT EXISTS embeddings (
    id              SERIAL PRIMARY KEY,
    user_id         INT,
    document_id     INT NOT NULL,
    chunk_id        INT NOT NULL,
    embedding       vector(1024) NOT NULL, -- 维度与 embedding 模型输出一致（bge-m3=1024），换模型需迁移维度
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
COMMENT ON COLUMN embeddings.embedding IS '向量数据，pgvector 类型；维度与 embedding 模型输出一致（bge-m3=1024），需与 rag.vector-store.dimension 及 Insert cast 保持一致';
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
    type            VARCHAR(10) NOT NULL CHECK (type IN ('user', 'assistant', 'summary')),
    content         TEXT NOT NULL,
    sources         JSONB DEFAULT '[]',
    nearest_summary_message_id INT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT chat_messages_session_id_fkey
        FOREIGN KEY(session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

COMMENT ON TABLE chat_messages IS '聊天消息表，会话中的每条问答消息，通过 parent_id 构成树形结构';
COMMENT ON COLUMN chat_messages.id IS '消息唯一ID';
COMMENT ON COLUMN chat_messages.user_id IS '所属用户ID，冗余以支持按用户快速查询';
COMMENT ON COLUMN chat_messages.session_id IS '所属会话ID（即树的根节点ID），用于一次拉取整棵树的所有消息';
COMMENT ON COLUMN chat_messages.parent_id IS '直接父消息ID，NULL 表示该消息是树根下的一级节点；沿此字段回溯可得从根到当前节点的完整路径';
COMMENT ON COLUMN chat_messages.type IS '消息类型：user=用户提问，assistant=助手回答，summary=对话历史摘要节点（thePlan P1-1：原 role 更名并扩 CHECK）';
COMMENT ON COLUMN chat_messages.content IS '消息正文内容';
COMMENT ON COLUMN chat_messages.sources IS '助手回答时引用的来源列表，JSON数组格式，如 [{"chunkId":1,"fileName":"xxx.md","titlePath":"章节"}]';
COMMENT ON COLUMN chat_messages.nearest_summary_message_id IS '当前节点回溯路径上最近的 summary 节点 id（用于 Recovery 快速定位 summary 停靠点）；只对 summary 之后新增的消息填值，被压缩的旧消息与 summary 自身为 NULL（thePlan P1-1/P1-2）';
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
-- 索引：按会话定位 summary 节点（Recovery 点查加速，thePlan P1-1）
CREATE INDEX idx_chat_messages_summary
    ON chat_messages(session_id) WHERE type = 'summary';

-- =============================================
-- 8、Agent执行步骤表 agent_steps
-- =============================================
CREATE TABLE IF NOT EXISTS agent_steps (
    id              SERIAL PRIMARY KEY,
    chat_message_id INT,
    session_id      INT NOT NULL,
    step_order      INT NOT NULL DEFAULT 0,
    step_type       VARCHAR(30) NOT NULL,
    content         TEXT,
    step_data       JSONB NOT NULL DEFAULT '{}'::jsonb,
    parent_step_id  INT,
    agent_id        VARCHAR(50),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT agent_steps_session_id_fkey
        FOREIGN KEY(session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    CONSTRAINT agent_steps_chat_message_id_fkey
        FOREIGN KEY(chat_message_id) REFERENCES chat_messages(id) ON DELETE CASCADE
);

COMMENT ON TABLE agent_steps IS 'Agent执行步骤记录表，记录ReAct循环中的推理过程（思考内容/工具调用/工具结果/错误……）';
COMMENT ON COLUMN agent_steps.id IS '步骤唯一ID';
COMMENT ON COLUMN agent_steps.chat_message_id IS '关联的助手消息ID，NULL表示步骤尚未绑定消息；消息删除时CASCADE';
COMMENT ON COLUMN agent_steps.session_id IS '所属会话ID；会话删除时CASCADE';
COMMENT ON COLUMN agent_steps.step_order IS '步骤顺序，从1开始（全局到达顺序，所有 step 共享递增）';
COMMENT ON COLUMN agent_steps.step_type IS '步骤类型：thinking（推理思考）/……_call/……_result/error等，应用层校验；注意：final类型不写DB，仅SSE推送';
COMMENT ON COLUMN agent_steps.content IS '主文本内容：thinking时为完整推理文本，……_call时为调用参数，……_result时为返回结果';
COMMENT ON COLUMN agent_steps.step_data IS '类型特有结构化数据（JSONB），如……_call_id/arguments/is_success/error_code/thinking_tokens等';
COMMENT ON COLUMN agent_steps.parent_step_id IS '父步骤ID，NULL表示根层（主Agent step）。表达树形嵌套——子Agent内部step挂到对应sub_agent step下';
COMMENT ON COLUMN agent_steps.agent_id IS '所属Agent标识（main/plan_generator/exam_generator等）。并行子Agent交错到达时按此分组重建各子序列';
COMMENT ON COLUMN agent_steps.created_at IS '创建时间';

CREATE INDEX idx_agent_steps_session_id ON agent_steps(session_id);
CREATE INDEX idx_agent_steps_chat_message_id ON agent_steps(chat_message_id);
CREATE INDEX idx_agent_steps_session_step ON agent_steps(session_id, step_order);
CREATE INDEX idx_agent_steps_step_data_gin ON agent_steps USING GIN (step_data);
CREATE INDEX idx_agent_steps_parent ON agent_steps(parent_step_id);
CREATE INDEX idx_agent_steps_agent_id ON agent_steps(agent_id);

-- =============================================
-- 9、知识测验表 exams
-- =============================================
CREATE TABLE IF NOT EXISTS exams (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'created'
                    CHECK (status IN ('created', 'in_progress', 'completed')),
    source_type     VARCHAR(20) NOT NULL CHECK (source_type IN ('notes', 'web_search', 'mixed')),
    source_refs     JSONB DEFAULT '[]',
    question_count  INT NOT NULL DEFAULT 0,
    linked_plan_id  INT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exams_user_id ON exams(user_id);
CREATE INDEX IF NOT EXISTS idx_exams_user_created ON exams(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_exams_user_status ON exams(user_id, status);
CREATE INDEX IF NOT EXISTS idx_exams_linked_plan_id ON exams(linked_plan_id) WHERE linked_plan_id IS NOT NULL;

COMMENT ON TABLE exams IS '知识测验表，记录每次生成的测验元信息';
COMMENT ON COLUMN exams.id IS '测验唯一ID';
COMMENT ON COLUMN exams.user_id IS '所属用户ID';
COMMENT ON COLUMN exams.title IS '测验标题，如"数据结构测验"';
COMMENT ON COLUMN exams.description IS '测验描述或生成要求';
COMMENT ON COLUMN exams.status IS '测验状态：created=已生成未作答，in_progress=作答中，completed=已完成';
COMMENT ON COLUMN exams.source_type IS '素材来源类型：notes=用户笔记，web_search=联网搜索，mixed=混合';
COMMENT ON COLUMN exams.source_refs IS '素材来源引用，JSON数组，如笔记chunk_ids或搜索URL';
COMMENT ON COLUMN exams.question_count IS '题目总数';
COMMENT ON COLUMN exams.linked_plan_id IS '关联的学习计划ID，study_plan工作流并行生成时建立；可为空';
COMMENT ON COLUMN exams.created_at IS '创建时间';

-- =============================================
-- 10、测验试题上下文表 exam_context
-- =============================================
CREATE TABLE IF NOT EXISTS exam_context (
    id              SERIAL PRIMARY KEY,
    exam_id         INT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    user_id         INT NOT NULL,
    question_order  INT NOT NULL DEFAULT 0,
    question_type   VARCHAR(20) NOT NULL CHECK (question_type IN
                     ('single_choice', 'multi_choice', 'fill_blank', 'true_false', 'short_answer')),
    stem            TEXT NOT NULL,
    options         JSONB,
    answer          TEXT NOT NULL,
    explanation     TEXT,
    difficulty      VARCHAR(10) DEFAULT 'medium',
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exam_context_exam_id ON exam_context(exam_id);
CREATE INDEX IF NOT EXISTS idx_exam_context_user_id ON exam_context(user_id);
CREATE INDEX IF NOT EXISTS idx_exam_context_exam_order ON exam_context(exam_id, question_order);

COMMENT ON TABLE exam_context IS '测验试题上下文表，存储每道题的完整上下文（题干、选项、答案、解析）';
COMMENT ON COLUMN exam_context.id IS '试题唯一ID';
COMMENT ON COLUMN exam_context.exam_id IS '所属测验ID，测验删除时级联删除';
COMMENT ON COLUMN exam_context.user_id IS '所属用户ID，冗余以支持按用户快速查询';
COMMENT ON COLUMN exam_context.question_order IS '题目在测验中的顺序，从1开始';
COMMENT ON COLUMN exam_context.question_type IS '题型：single_choice/multi_choice/fill_blank/true_false/short_answer';
COMMENT ON COLUMN exam_context.stem IS '题目内容';
COMMENT ON COLUMN exam_context.options IS '选项列表，JSON数组格式，如["A.选项1","B.选项2"]；填空题和简答题为NULL';
COMMENT ON COLUMN exam_context.answer IS '正确答案；单选/判断为单个值，多选为JSON数组，填空/简答为文本';
COMMENT ON COLUMN exam_context.explanation IS '答案解析';
COMMENT ON COLUMN exam_context.difficulty IS '难度：easy/medium/hard';
COMMENT ON COLUMN exam_context.created_at IS '创建时间';

-- =============================================
-- 11、用户答题记录表 exam_answers
-- =============================================
CREATE TABLE IF NOT EXISTS exam_answers (
    id              SERIAL PRIMARY KEY,
    exam_id         INT NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    user_id         INT NOT NULL,
    answers         JSONB NOT NULL,
    score           INT,
    total           INT,
    is_completed        BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exam_answers_exam_id ON exam_answers(exam_id);
CREATE INDEX IF NOT EXISTS idx_exam_answers_user_id ON exam_answers(user_id);
CREATE INDEX IF NOT EXISTS idx_exam_answers_user_completed ON exam_answers(user_id, completed_at DESC);

COMMENT ON TABLE exam_answers IS '用户答题记录表，记录每次测验的作答情况和得分';
COMMENT ON COLUMN exam_answers.id IS '答题记录唯一ID';
COMMENT ON COLUMN exam_answers.exam_id IS '所属测验ID，测验删除时级联删除';
COMMENT ON COLUMN exam_answers.user_id IS '答题用户ID';
COMMENT ON COLUMN exam_answers.answers IS '用户答案，JSON对象格式，如{"q1":"C","q2":["A","C"],"q3":"有序"}';
COMMENT ON COLUMN exam_answers.score IS '得分/答对题数';
COMMENT ON COLUMN exam_answers.total IS '总题数';
COMMENT ON COLUMN exam_answers.is_completed IS '是否已完成，true表示已完成，false表示未完成';
COMMENT ON COLUMN exam_answers.completed_at IS '答题完成时间';

-- =============================================
-- 12、学习计划主表 study_plans
-- =============================================
CREATE TABLE IF NOT EXISTS study_plans (
    id              SERIAL PRIMARY KEY,
    user_id         INT NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    goal            TEXT NOT NULL,
    duration        VARCHAR(50),
    source_type     VARCHAR(20) NOT NULL CHECK (source_type IN ('notes', 'web_search', 'mixed')),
    source_refs     JSONB DEFAULT '[]',
    status          VARCHAR(20) NOT NULL DEFAULT 'created'
                    CHECK (status IN ('created', 'in_progress', 'completed', 'archived')),
    phase_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_study_plans_user_id ON study_plans(user_id);
CREATE INDEX IF NOT EXISTS idx_study_plans_user_status ON study_plans(user_id, status);
CREATE INDEX IF NOT EXISTS idx_study_plans_user_created ON study_plans(user_id, created_at DESC);

COMMENT ON TABLE study_plans IS '学习计划主表，记录用户生成的学习计划元信息';
COMMENT ON COLUMN study_plans.id IS '学习计划唯一ID';
COMMENT ON COLUMN study_plans.user_id IS '所属用户ID';
COMMENT ON COLUMN study_plans.title IS '计划标题，如"Rust 3个月学习计划"';
COMMENT ON COLUMN study_plans.description IS '计划描述或背景说明';
COMMENT ON COLUMN study_plans.goal IS '学习目标，如"从零到能写项目"';
COMMENT ON COLUMN study_plans.duration IS '计划总时长，如"3个月"';
COMMENT ON COLUMN study_plans.source_type IS '素材来源类型：notes=用户笔记，web_search=联网搜索，mixed=混合';
COMMENT ON COLUMN study_plans.source_refs IS '素材来源引用，JSON数组，如笔记chunk_ids或搜索URL';
COMMENT ON COLUMN study_plans.status IS '计划状态：created=已生成，in_progress=进行中，completed=已完成，archived=已归档';
COMMENT ON COLUMN study_plans.phase_count IS '阶段总数';
COMMENT ON COLUMN study_plans.created_at IS '创建时间';
COMMENT ON COLUMN study_plans.updated_at IS '最后更新时间';

-- exams.linked_plan_id 外键约束（study_plans 表已定义，延迟添加以避免顺序依赖）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_exams_linked_plan_id' AND table_name = 'exams'
    ) THEN
        ALTER TABLE exams
            ADD CONSTRAINT fk_exams_linked_plan_id
            FOREIGN KEY (linked_plan_id) REFERENCES study_plans(id) ON DELETE SET NULL;
    END IF;
END $$;

-- =============================================
-- 13、学习阶段表 study_plan_phases
-- =============================================
CREATE TABLE IF NOT EXISTS study_plan_phases (
    id              SERIAL PRIMARY KEY,
    plan_id         INT NOT NULL REFERENCES study_plans(id) ON DELETE CASCADE,
    user_id         INT NOT NULL,
    phase_order     INT NOT NULL DEFAULT 0,
    title           VARCHAR(200) NOT NULL,
    duration        VARCHAR(50),
    objective       TEXT,
    key_topics      JSONB DEFAULT '[]',
    resources       JSONB DEFAULT '[]',
    practice_tasks  JSONB DEFAULT '[]',
    milestones      JSONB DEFAULT '[]',
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_study_plan_phases_plan_id ON study_plan_phases(plan_id);
CREATE INDEX IF NOT EXISTS idx_study_plan_phases_user_id ON study_plan_phases(user_id);
CREATE INDEX IF NOT EXISTS idx_study_plan_phases_plan_order ON study_plan_phases(plan_id, phase_order);

COMMENT ON TABLE study_plan_phases IS '学习阶段表，存储每个学习计划的分阶段详情';
COMMENT ON COLUMN study_plan_phases.id IS '阶段唯一ID';
COMMENT ON COLUMN study_plan_phases.plan_id IS '所属计划ID，计划删除时级联删除';
COMMENT ON COLUMN study_plan_phases.user_id IS '所属用户ID，冗余以支持按用户快速查询';
COMMENT ON COLUMN study_plan_phases.phase_order IS '阶段顺序，从1开始';
COMMENT ON COLUMN study_plan_phases.title IS '阶段标题，如"第1月：基础语法"';
COMMENT ON COLUMN study_plan_phases.duration IS '阶段时长，如"4周"';
COMMENT ON COLUMN study_plan_phases.objective IS '阶段学习目标';
COMMENT ON COLUMN study_plan_phases.key_topics IS '关键知识点，JSON数组，如["变量与类型","所有权机制"]';
COMMENT ON COLUMN study_plan_phases.resources IS '学习资源，JSON数组，如[{"name":"The Rust Book","url":"https://doc.rust-lang.org/book/"}]';
COMMENT ON COLUMN study_plan_phases.practice_tasks IS '实践任务，JSON数组，如["实现一个CLI计算器","完成Exercism前10题"]';
COMMENT ON COLUMN study_plan_phases.milestones IS '阶段里程碑，JSON数组，如["能独立写出Hello World","理解所有权规则"]';
COMMENT ON COLUMN study_plan_phases.created_at IS '创建时间';

-- =============================================
-- 14、学习计划进度表 study_plan_progress
-- =============================================
CREATE TABLE IF NOT EXISTS study_plan_progress (
    id              SERIAL PRIMARY KEY,
    plan_id         INT NOT NULL REFERENCES study_plans(id) ON DELETE CASCADE,
    phase_id        INT NOT NULL REFERENCES study_plan_phases(id) ON DELETE CASCADE,
    user_id         INT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'not_started'
                    CHECK (status IN ('not_started', 'in_progress', 'completed')),
    notes           TEXT,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(phase_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_study_plan_progress_plan_id ON study_plan_progress(plan_id);
CREATE INDEX IF NOT EXISTS idx_study_plan_progress_user_id ON study_plan_progress(user_id);

COMMENT ON TABLE study_plan_progress IS '学习计划进度追踪表，记录用户在每个阶段的完成情况';
COMMENT ON COLUMN study_plan_progress.id IS '进度记录唯一ID';
COMMENT ON COLUMN study_plan_progress.plan_id IS '所属计划ID，计划删除时级联删除';
COMMENT ON COLUMN study_plan_progress.phase_id IS '所属阶段ID，阶段删除时级联删除；同一用户同一阶段唯一';
COMMENT ON COLUMN study_plan_progress.user_id IS '所属用户ID';
COMMENT ON COLUMN study_plan_progress.status IS '阶段状态：not_started=未开始，in_progress=进行中，completed=已完成';
COMMENT ON COLUMN study_plan_progress.notes IS '用户学习笔记/心得';
COMMENT ON COLUMN study_plan_progress.completed_at IS '阶段完成时间';
COMMENT ON COLUMN study_plan_progress.created_at IS '创建时间';
COMMENT ON COLUMN study_plan_progress.updated_at IS '最后更新时间';
