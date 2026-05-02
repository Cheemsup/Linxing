# Linxing - Personal Note RAG

基于 **LangChain4j + BGE + PostgreSQL/pgvector** 的个人笔记知识库问答系统。

## 项目简介

Linxing 是一个面向个人用户的知识库问答系统，支持导入多种格式的文档，通过 RAG（检索增强生成）技术实现智能问答。系统采用向量检索与全文检索混合策略，结合 Cross-encoder 重排序，提供精准的知识检索能力。

### 核心特性

- **多格式文档支持**：TXT、Markdown、PDF、Word、Excel、HTML、CSV、Java 代码等
- **智能分块策略**：根据文档类型自动选择最优分块策略（Markdown、HTML、代码、语义分块等）
- **混合检索**：向量检索 + BM25 全文检索 + RRF（Reciprocal Rank Fusion）融合
- **Cross-encoder 重排序**：基于 ONNX 的 ms-marco-MiniLM-L-6-v2 模型
- **多 LLM 支持**：MiniMax、DeepSeek、GLM、Kimi（OpenAI 兼容 API）
- **树形对话**：支持多轮对话的树形结构，可追溯任意分支
- **多用户隔离**：JWT 认证 + 用户级数据隔离

## 技术栈

### 后端

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.0.5 | 核心框架 |
| LangChain4j | 1.7.1 | RAG 框架 |
| PostgreSQL + pgvector | - | 向量数据库 |
| MyBatis | 4.0.0 | ORM 框架 |
| Druid | 1.2.28 | 数据库连接池 |
| ONNX Runtime | 1.20.0 | 重排序模型推理 |
| BGE-small-zh | - | 中文嵌入模型（512 维） |

### 前端

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.2.13 | 前端框架 |
| Element Plus | 2.13.7 | UI 组件库 |
| Vue Router | 4 | 路由管理 |
| Axios | 1.15.2 | HTTP 客户端 |
| vue3-d3-tree | 1.0.2 | 树形可视化 |

## 项目结构

```
Linxing/
├── Linxing_Agent/                 # 后端项目
│   ├── src/main/java/org/linxing/linxing_agent/
│   │   ├── config/               # 配置类
│   │   ├── controller/           # REST 控制器
│   │   ├── service/              # 业务服务
│   │   ├── mapper/               # MyBatis Mapper
│   │   ├── entity/               # 实体类
│   │   ├── dto/                  # 数据传输对象
│   │   ├── vo/                   # 视图对象
│   │   ├── strategy/             # 分块策略（策略模式）
│   │   ├── pipeline/             # 处理管道
│   │   ├── utils/                # 工具类
│   │   └── interceptor/          # JWT 拦截器
│   └── src/main/resources/
│       ├── mapper/               # MyBatis XML
│       ├── models/               # ONNX 模型文件
│       ├── application.yaml      # 主配置
│       └── schema.sql            # 数据库建表脚本
│
└── webconsole/                    # 前端项目
    └── src/
        ├── api/                   # API 接口
        ├── components/            # Vue 组件
        ├── views/                 # 页面视图
        ├── layouts/               # 布局组件
        ├── router/                # 路由配置
        └── utils/                 # 工具函数
```

## 快速开始

### 环境要求

- JDK 17+
- Node.js 16+
- PostgreSQL 14+（需安装 pgvector 扩展）
- Yarn（前端包管理）

### 1. 数据库准备

```sql
CREATE DATABASE vectordb;
\c vectordb
CREATE EXTENSION vector;
```

执行建表脚本：

```bash
psql -d vectordb -f Linxing_Agent/src/main/resources/schema.sql
```

### 2. 后端启动

```bash
cd Linxing_Agent
mvn spring-boot:run
```

后端服务运行在 `http://localhost:8080`

### 3. 前端启动

```bash
cd webconsole
yarn install
yarn serve
```

前端服务运行在 `http://localhost:3000`

## 核心架构

### 文档处理流程

```
文件上传 → 文件解析 → 智能分块 → 向量嵌入 → 持久化存储
                           ↓
                    分块策略选择
                           ↓
         ┌─────────┬───────┴───────┬─────────┐
    Markdown   HTML    Code    Semantic  Recursive
```

### 检索流程

```
用户提问 → 向量检索 + BM25检索 → RRF融合 → Cross-encoder重排序 → LLM生成
```

### 分块策略

系统采用策略模式实现智能分块，按优先级自动选择：

1. **MarkdownChunkStrategy** - Markdown 文档（识别标题层级）
2. **HtmlChunkStrategy** - HTML 文档
3. **CodeChunkStrategy** - 代码文件（识别函数/类结构）
4. **StructureAwareChunkStrategy** - 结构化文档
5. **LineBasedChunkStrategy** - 行式文档
6. **RecursiveChunkStrategy** - 通用兜底策略

## 数据库设计

| 表名 | 说明 |
|------|------|
| users | 用户信息 |
| documents | 文档元数据 |
| chunks | 分块索引（支持分层 Small-to-Big 检索） |
| embeddings | 向量存储（pgvector） |
| chat_sessions | 聊天会话 |
| chat_messages | 聊天消息 |
| activity_logs | 操作日志 |

## 配置说明

### 支持的 LLM 提供商

在 `application-dev.yaml` 中配置 `LLM_DEFAULT_PROVIDER`：

- `minimax` - MiniMax
- `deepseek` - DeepSeek
- `glm` - 智谱 GLM
- `kimi` - Moonshot Kimi

### 文件存储路径

- `RAG_STORE_PATH` - 上传文件存储目录
- `rag.reranker.model-path` - ONNX 重排序模型路径
