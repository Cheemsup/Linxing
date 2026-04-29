日志记录表，记录用户及其执行的操作
CREATE TABLE activity_logs(
    id SERIAL NOT NULL,
    user_id integer NOT NULL,
    action_type varchar(50) NOT NULL,
    target_type varchar(50),
    target_id varchar(100),
    details jsonb,
    created_at timestamp with time zone DEFAULT now(),
    PRIMARY KEY(id),
    CONSTRAINT activity_logs_action_type_check CHECK ((action_type)::text = ANY ((ARRAY['upload'::character varying, 'query'::character varying, 'delete'::character varying])::text[])),
    CONSTRAINT activity_logs_target_type_check CHECK ((target_type)::text = ANY ((ARRAY['document'::character varying, 'chunk'::character varying])::text[]))
);
CREATE INDEX idx_activity_logs_user_time ON public.activity_logs USING btree (user_id, created_at DESC);
CREATE INDEX idx_activity_logs_action_date ON public.activity_logs USING btree (action_type, created_at);




文档chunk之后的表，记录chunk段及其所属的文档和用户等
CREATE TABLE chunks(
    id SERIAL NOT NULL,
    user_id integer NOT NULL,
    document_id integer NOT NULL,
    chunk_text text NOT NULL,
    page_number integer DEFAULT 0,
    created_at timestamp with time zone DEFAULT now(),
    PRIMARY KEY(id)
);
CREATE INDEX idx_chunks_user_doc ON public.chunks USING btree (user_id, document_id);
CREATE INDEX idx_chunks_user_id ON public.chunks USING btree (user_id);



原始文档存储表，记录文档的存储路径、所属用户等
CREATE TABLE documents(
    id SERIAL NOT NULL,
    user_id integer NOT NULL,
    file_name varchar(255) NOT NULL,
    file_path varchar(500) NOT NULL,
    file_size bigint DEFAULT 0,
    file_type varchar(50) DEFAULT '',
    status varchar(20) DEFAULT 'processing'::character varying,
    created_at timestamp with time zone DEFAULT now(),
    PRIMARY KEY(id),
    CONSTRAINT documents_status_check CHECK ((status)::text = ANY ((ARRAY['processing'::character varying, 'completed'::character varying, 'failed'::character varying])::text[]))
);
CREATE INDEX idx_documents_user_status ON public.documents USING btree (user_id, status);
CREATE INDEX idx_documents_user_id ON public.documents USING btree (user_id);


向量表，记录向量对应的chunk文本段、所属原始文档、用户等
CREATE TABLE embeddings(
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id integer,
    document_id integer,
    chunk_id integer,
    embedding vector NOT NULL,
    text text,
    metadata jsonb,
    PRIMARY KEY(id)
);
CREATE INDEX idx_embeddings_vector ON public.embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists='100');
CREATE INDEX idx_embeddings_user_id ON public.embeddings USING btree (user_id);
CREATE INDEX idx_embeddings_doc_id ON public.embeddings USING btree (document_id);



用户表
CREATE TABLE users(
    id SERIAL NOT NULL,
    username varchar(50) NOT NULL,
    password_hash varchar(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    PRIMARY KEY(id)
);
CREATE UNIQUE INDEX users_username_key ON public.users USING btree (username);
CREATE INDEX idx_users_username ON public.users USING btree (username);