# AGENTS.md — Linxing (Agent-Driven Learning Platform)

## Project overview

Monorepo: Spring Boot 4.x backend (`Linxing_Agent/`) + Vue 3 frontend (`webconsole/`). 
Agent-driven personal learning platform — RAG knowledge retrieval + LLM conversation, built with PG vector + LangChain4j + MiniMax LLM.

## Commands

```bash
# Backend (from Linxing_Agent/)
./mvnw spring-boot:run                    # start backend on :8080
./mvnw compile                            # compile-only

# Frontend (from webconsole/)
yarn serve                                # dev server on :3000, proxies /api → :8080
yarn build                                # production build
yarn lint                                 # ESLint
```

## Architecture

- **Backend package**: `org.linxing.linxing_agent`
- **Domain-driven layout**: `common/` (shared infra) → `user/` → `rag/` (knowledge retrieval) → `agent/` (conversation & orchestration)
- **Data access**: MyBatis XML mappers under `resources/mapper/`
- **Multi-tenant**: All tables carry `user_id`; JWT interceptor extracts user on every request
- **Auth**: JWT via `JwtTokenUserInterceptor` — excludes only `/user/login` and `/user/register` (no `/api` prefix on backend paths)
- **Database**: PostgreSQL `vectordb` on localhost:5432, requires `pgvector` extension, schema in `schema.sql`

## Critical gotchas

### Frontend proxy strips `/api` prefix
`vue.config.js` rewrites `^/api` → `''`. So frontend calls `/api/search` but backend receives `/search`. 
When adding new API endpoints, match the backend path (no `/api` prefix).

### Non-standard Maven source layout
`pom.xml` explicitly sets `<sourceDirectory>src/main/java</sourceDirectory>` and `<testSourceDirectory>src/test/java</testSourceDirectory>`. 
This is the default but is declared explicitly — do not change these paths.

### `application-dev.yaml` is gitignored but required
Contains DB password, LLM API key, and model paths. Must exist locally. 
Template fields visible in `application.yaml` (non-secret settings only).

### Model files and file store are gitignored
`models/` (ONNX reranker model) and `files_store/` (uploaded documents) are in `.gitignore`. 
Local paths configured in `application-dev.yaml` under `rag.reranker.model-path` and `rag.store-path`.

### ONNX runtime
The reranker uses `langchain4j-onnx-scoring` with `ms-marco-MiniLM-L-6-v2`. 
ONNX native libs are auto-downloaded by the Java library (no manual install needed).

### Vue CLI 5 + yarn
Use `yarn` not `npm`. Lockfile is `yarn.lock`.

## Key dependencies

| Library | Purpose |
|---|---|
| `langchain4j` 1.7.1 | Core RAG framework |
| `langchain4j-embeddings-bge-small-zh` | Local embedding model (512-dim) |
| `langchain4j-pgvector` | PG vector store integration |
| `langchain4j-open-ai` | LLM client (MiniMax uses OpenAI-compatible API) |
| `langchain4j-onnx-scoring` | Cross-encoder reranker |
| `mybatis-spring-boot-starter` 4.0.0 | ORM (XML mappers) |
| `druid-spring-boot-4-starter` | Connection pool |
