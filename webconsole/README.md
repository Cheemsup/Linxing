# webconsole

Linxing 平台的 Vue 前端服务。基于 Vue 3 + Element Plus，为用户提供笔记导入、知识检索、Agent 对话、知识测验与学习计划的可视化交互界面。

> 本 README 仅介绍当前服务。项目整体架构见根目录 [README.md](../README.md) 与 [AGENTS.md](../AGENTS.md)。

## 服务简介（Overview）

**职责**：前端交互层。承接用户登录认证、笔记上传触发后端入库、知识库检索、Agent 对话（SSE 流式渲染）、测验作答与学习计划进度的全部人机交互。

**在系统中的位置**：

```
浏览器 ──▶ webconsole (Vue, 3000/dev)
              │
              │  /api/**         ──proxy(rewrite ^/api→'')──▶  Linxing_Agent (8080)
              │  /chunk_images/** ──proxy(无重写)────────────▶  Linxing_Agent (8080)
              │  /agent/chat     ──fetch SSE 直连─────────────▶  Linxing_Agent (8080)
              ▼
           用户界面
```

**为什么存在**：后端只提供 REST + SSE 接口，本服务负责把这些接口组织成可用的学习平台界面：对话流式渲染、笔记富文本（图片/代码/表格/公式占位符回填）、对话树分支跳转、测验与学习计划的时间线管理。

## 核心功能（Features）

- **JWT 登录与会话保持**：token 持久化到 `localStorage`，axios 请求拦截器自动注入 `Authorization`
- **Agent 对话 SSE 流式渲染**：手写 fetch + ReadableStream 解析 `step`/`stream`/`result`/`done`/`error` 事件，逐步渲染思考过程与流式回答
- **对话树分支管理**：基于 `chat_messages.parent_id` 构建消息树，支持分支跳转、子树删除、活跃路径高亮
- **富文本 Chunk 展示**：`RichChunkText` 组件把 `chunkText` 中的 `[[LINXING:IMAGE:xxx]]` 等占位符按 `nodeMetadata` 回填为图片/代码/表格/公式
- **笔记入库编排**：上传文件触发后端 `/rag/ingest/file`，轮询文档状态
- **知识库检索页**：调用 `/rag/search`，展示命中 chunk 并可查看上下文
- **测验作答与草稿**：题目树渲染、答案提交、草稿保存/恢复
- **学习计划时间线**：阶段进度更新与计划导出（md/html）
- **路由守卫**：未登录跳转 `/login`，已登录访问 `/login` 重定向到 `/chat`

## 技术栈（Tech Stack）

| 技术 | 用途 |
|---|---|
| Vue 3.2（Options API + Composition API 混用） | 前端框架 |
| Vue Router 4 | 路由（`createWebHistory`，懒加载） |
| Element Plus 2.13 + `@element-plus/icons-vue` | UI 组件库与图标 |
| axios 1.15 | HTTP 客户端（统一 baseURL `/api`，拦截器注入 token） |
| vue3-d3-tree 1.0 | 对话树可视化（`ChatTreePanel`） |
| `@vue/cli-service` 5.0 | 构建/开发服务（webpack） |
| 原生 fetch + ReadableStream | SSE 流式对话（绕过 axios/devServer 缓冲） |

> 未使用 Pinia / Vuex。状态管理采用模块级 `reactive` 单例对象（见 [src/stores/](src/stores/)）。

## 项目结构（Project Structure）

```
webconsole/
├── public/
│   └── index.html              # HTML 模板（标题：临星 - 个人学习平台）
├── src/
│   ├── main.js                 # 入口：挂载 App、注册 Element Plus 与全部图标
│   ├── App.vue                 # 根组件（仅 <router-view/>）
│   ├── router/index.js         # 路由表 + beforeEach 鉴权守卫
│   ├── layouts/
│   │   └── AppLayout.vue       # 主布局：侧边栏导航 + 对话历史 + 内容区
│   ├── views/                  # 页面（按域组织）
│   │   ├── auth/LoginView.vue
│   │   └── agent/              # ChatView / SearchView / IngestView / NotesView /
│   │                           #   ExamListView / ExamDetailView /
│   │                           #   PlanListView / PlanDetailView
│   ├── components/agent/        # 业务组件（见下文「页面组织」）
│   ├── stores/                 # 模块级 reactive 单例（非 Pinia/Vuex）
│   │   ├── authStore.js
│   │   └── agent/
│   │       ├── chatSessionStore.js
│   │       └── chatTreeStore.js
│   ├── api/                    # HTTP 封装
│   │   ├── index.js            # axios 实例 + 拦截器
│   │   ├── auth.js
│   │   └── agent/              # chat / workflow / ingest / search / document / chunk / exam / studyPlan
│   └── composables/
│       └── useMarkdownRenderer.js  # ⚠️ 占位实现，尚未接入渲染库
├── vue.config.js               # devServer 代理（/api、/chunk_images）
├── babel.config.js
├── jsconfig.json
├── .env.development            # VUE_APP_SSE_BASE_URL=http://localhost:8080
├── .env.production             # VUE_APP_SSE_BASE_URL=（走 Nginx 反代）
└── package.json
```

## 系统职责（Responsibilities）

**本服务负责**：

- 用户登录/登出界面与 token 本地持久化
- 所有业务页面的 UI 渲染与交互
- Agent SSE 流的接收与逐步渲染（思考步骤、流式 token、最终结果、引用来源）
- 对话消息树的前端构建与分支导航
- `chunkText` 占位符 → 富文本（图片/代码/表格/公式）的前端回填
- 文件上传、检索、测验、学习计划等表单交互

**本服务不负责**：

- 业务逻辑与持久化（由 `Linxing_Agent` 承担）
- 文档解析/切分/向量化（由后端 + `document_analysis_service` 承担）
- 鉴权校验本身（前端只携带 token，校验在后端 `JwtTokenUserInterceptor`）
- Markdown 实际渲染（`useMarkdownRenderer` 当前为占位实现，未接入渲染库）

## 服务边界（Service Boundary）

| 维度 | 说明 |
|---|---|
| **输入** | 用户在浏览器的交互操作（登录、上传、对话、答题、进度更新） |
| **输出** | 对 `Linxing_Agent`（8080）的 REST 与 SSE 请求 |
| **调用方** | 浏览器终端用户 |
| **被调用方** | `Linxing_Agent`（经 devServer `/api` 代理或生产 Nginx 反代；SSE 直连） |
| **不负责** | 数据存储、检索计算、文档解析、LLM 编排 |

## 与其它服务协作（Integration）

### 与 Linxing_Agent 协作

所有业务请求都指向 `Linxing_Agent`（8080）。开发环境通过 `vue.config.js` 的 devServer proxy 转发：

| 前端发出的路径 | 代理行为 | 后端收到 |
|---|---|---|
| `/api/**` | `pathRewrite: {'^/api': ''}` | `/**`（裸路径） |
| `/chunk_images/**` | 不重写，直接转发 | `/chunk_images/**` |
| `/agent/chat`（SSE） | **不走路由代理**，fetch 直连 `VUE_APP_SSE_BASE_URL` | `/agent/chat` |

> SSE 直连后端是为了绕过 webpack devServer 对代理响应的缓冲。生产环境 `VUE_APP_SSE_BASE_URL` 留空，走 Nginx 反代（需配置 `X-Accel-Buffering: no`）。

### 数据流转

```
登录:  LoginView ──authApi.login──▶ /api/user/login ──▶ 后端签发 JWT
                                                              │
                                                              ▼
                                                    authStore 持久化 token
                                                              │
对话:  ChatPanel ──ragApi.chatStream(fetch SSE)──▶ /agent/chat
          │  onStep/onStream/onResult 回调逐步渲染
          ▼
       chatTreeStore 构建消息树（parentId）
          │
          ▼
       RichChunkText 回填 chunkText 占位符 → 图片走 /chunk_images/**

入库:  IngestPanel ──ingestApi.ingestFile──▶ /api/rag/ingest/file (multipart, 300s 超时)

检索:  SearchView ──searchApi.search──▶ /api/rag/search
          └─ 点击 chunk ──chunkApi.getContext──▶ /api/rag/chunks/{id}/context

测验:  QuizPanel ──examApi──▶ /api/exam/**（详情/提交/草稿）
计划:  StudyPlanTimeline ──studyPlanApi──▶ /api/study-plan/**（进度/导出）
```

## 配置说明（Configuration）

### 环境变量

| 变量 | 文件 | 用途 |
|---|---|---|
| `VUE_APP_SSE_BASE_URL` | [.env.development](.env.development) | 开发环境 SSE 直连地址，默认 `http://localhost:8080` |
| `VUE_APP_SSE_BASE_URL` | [.env.production](.env.production) | 生产环境留空，走 Nginx 反代 |

> 其余所有业务配置（数据库、LLM、Redis 等）均在后端 `Linxing_Agent` 侧，前端无需配置。

### devServer 代理（[vue.config.js](vue.config.js)）

| 配置 | 值 |
|---|---|
| `devServer.port` | 3000 |
| `/api` 代理目标 | `http://localhost:8080`，`changeOrigin: true`，`pathRewrite: {'^/api': ''}` |
| `/chunk_images` 代理目标 | `http://localhost:8080`，`changeOrigin: true`（无重写） |

### 浏览器持久化键（[src/stores/authStore.js](src/stores/authStore.js) 等）

| localStorage key | 内容 |
|---|---|
| `linxing_token` | JWT |
| `linxing_user` | 用户对象（含 id / username） |
| `lx_active_session` | 上次活跃会话 ID |
| `linxing_sidebar_collapsed` | 侧栏折叠状态 |
| `linxing_sidebar_collapsed_groups` | 折叠的导航分组 |

## 快速启动（Quick Start）

### 环境要求

- Node.js 16+（Vue 3 + Vue CLI 5）
- 包管理器：yarn（仓库提供 `yarn.lock`）或 npm
- `Linxing_Agent` 已启动（默认 `http://localhost:8080`）

### 1. 安装依赖

```bash
cd webconsole
yarn install        # 或 npm install
```

### 2. 启动开发服务器

```bash
yarn serve          # 或 npm run serve
```

### 访问地址

| 项 | 地址 |
|---|---|
| 开发前端 | http://localhost:3000 |
| 后端 API（经代理） | http://localhost:3000/api → http://localhost:8080 |
| 后端直连 | http://localhost:8080 |

### 构建生产包

```bash
yarn build           # 输出到 dist/
```

## API（封装的调用方式）

### axios 实例（[src/api/index.js](src/api/index.js)）

- `baseURL: '/api'`，统一超时 60s
- 请求拦截器：从 `authStore` 取 token 注入 `Authorization: Bearer <token>`
- 响应拦截器：401 自动 `clearAuth()` 并跳转 `/login`；403/404/500/timeout/network 统一控制台告警

### 按域封装（[src/api/agent/](src/api/agent/)）

| 模块 | 主要方法 | 对应后端路径 |
|---|---|---|
| [auth.js](src/api/auth.js) | `login` / `register` / `logout` | `/user/login` `/user/register` `/user/logout` |
| [chat.js](src/api/agent/chat.js) | `ragApi.chatStream`（SSE fetch）/ `chatSessionApi.{create,list,delete,updateTitle,autoTitle,getMessages,deleteSubtree,getMessageSteps}` | `/agent/chat` / `/agent/sessions/**` / `/agent/messages/**` |
| [workflow.js](src/api/agent/workflow.js) | `submitClarification` | `/agent/workflow/clarify` |
| [ingest.js](src/api/agent/ingest.js) | `ingestFile`（multipart，超时 300s） | `/rag/ingest/file` |
| [search.js](src/api/agent/search.js) | `search({query,topK,hybrid})` | `/rag/search` |
| [document.js](src/api/agent/document.js) | `documentApi.{list,getDetail,delete,preview,download}` / `noteApi` | `/rag/documents/**` |
| [chunk.js](src/api/agent/chunk.js) | `getContext(id)` | `/rag/chunks/{id}/context` |
| [exam.js](src/api/agent/exam.js) | `getExam` / `listExams` / `listByPlanId` / `submitAnswer` / `saveDraft` / `getDraft` | `/exam/**` |
| [studyPlan.js](src/api/agent/studyPlan.js) | `getPlanDetail` / `listPlans` / `updatePhaseStatus` / `exportPlan` | `/study-plan/**` |

> 后端路径**无 `/api` 前缀**，前端封装里写 `/rag/...`，由 axios `baseURL='/api'` 拼成 `/api/rag/...`，再由 devServer 代理剥离 `/api`。新增后端接口时前端这里直接写裸路径即可，不要手写 `/api` 前缀。

### SSE 对话回调契约（[chat.js](src/api/agent/chat.js)）

`ragApi.chatStream({ question, sessionId, parentMessageId, onStep, onStream, onResult, onDone, onError })` 按后端 SSE 事件名分发：

| event | 回调 | 数据 |
|---|---|---|
| `step` | `onStep(data)` | 推理步骤、工具调用、最终回答等 |
| `stream` | `onStream(data)` | 流式 token（含 `stepNumber`） |
| `result` | `onResult(data)` | 最终结果（answer/sources/sessionId/messageId） |
| `done` | `onDone()` | 流结束 |
| `error` | `onError(data)` | 服务端错误 |

## 开发说明（Development）

### 页面组织

页面按业务域放在 [src/views/](src/views/)，业务组件放在 [src/components/agent/](src/components/agent/)，二者一一对应：

| 路由路径 | View | 主组件 | 说明 |
|---|---|---|---|
| `/login` | `LoginView` | — | 登录/注册（左品牌叙事 + 右表单） |
| `/chat` | `ChatView` | `ChatPanel` / `ChatTreePanel` / `ChunkContextPanel` | Agent 对话 + 对话树 + 上下文 |
| `/ingest` | `IngestView` | `IngestPanel` | 笔记上传 |
| `/notes` | `NotesView` | `NotesPanel` / `DocumentPreview` | 笔记列表与预览 |
| `/search` | `SearchView` | `RichChunkText` | 知识检索结果展示 |
| `/quiz`、`/quiz/:examId` | `ExamListView` / `ExamDetailView` | `QuizPanel` / `QuestionNode` | 测验列表与作答 |
| `/study-plan`、`/study-plan/:planId` | `PlanListView` / `PlanDetailView` | `StudyPlanTimeline` | 计划列表与时间线详情 |

### Router（[src/router/index.js](src/router/index.js)）

- `createWebHistory` 模式，根路径 `/` 由 `AppLayout` 包裹，子路由为业务页
- 业务页全部懒加载（`() => import(...)`）以减小首屏体积
- `meta.requiresAuth` 控制鉴权；`beforeEach` 中未登录跳 `/login`，已登录访问 `/login`/`/register` 跳 `/chat`
- `meta.title` 用于 `document.title`（`{title} - 临星`）
- 兜底 `/:pathMatch(.*)*` → `/chat`

### Store（模块级 reactive 单例）

未使用 Pinia/Vuex。`src/stores/` 下每个文件导出一个模块级 `reactive` 单例对象：

| Store | 职责 |
|---|---|
| [authStore](src/stores/authStore.js) | token / user 状态 + `localStorage` 持久化 + `isAuthenticated()` |
| [chatSessionStore](src/stores/agent/chatSessionStore.js) | 会话列表（首屏 100 条）、活跃会话、AI 自动命名、`localStorage` 恢复上次活跃会话 |
| [chatTreeStore](src/stores/agent/chatTreeStore.js) | 消息树状态（`messages` / `activeLeafId` / `branchParentId`），提供 `getMessageMap` / `getActivePathIds` / `findLeafDescendant` 等树操作 |

> `chatSessionStore` 被 `AppLayout`（侧栏对话历史）与 `ChatPanel`（活跃会话）共同读写，避免跨组件 props 透传。

### Components / Views / API / Assets / Utils 约定

- **Components**：可复用业务组件放 [src/components/agent/](src/components/agent/)，与具体 View 强绑定的不抽象为通用组件。
- **Views**：每个路由对应一个 View，View 负责数据加载与布局编排，把渲染委托给主组件。
- **API**：按后端域分文件 [src/api/agent/](src/api/agent/)，全部基于 [src/api/index.js](src/api/index.js) 的 axios 实例；SSE 走原生 fetch，不进 axios。
- **Assets**：当前无 `src/assets/` 目录，图标用 `@element-plus/icons-vue`，品牌 SVG 直接内联在 `AppLayout.vue` / `LoginView.vue` 模板中。
- **Utils**：当前无独立 `src/utils/` 目录；树操作等工具方法内聚在 [chatTreeStore.js](src/stores/agent/chatTreeStore.js)。
- **Composables**：[useMarkdownRenderer.js](src/composables/useMarkdownRenderer.js) 为占位实现，尚未接入 markdown-it/marked。

### 富文本占位符回填（[RichChunkText.vue](src/components/agent/RichChunkText.vue)）

后端 `chunkText` 用占位符 `[[LINXING:IMAGE:nodeId]]` / `[[LINXING:CODE:...]]` / `[[LINXING:TABLE:...]]` / `[[LINXING:FORMULA:...]]` 表示非文本节点。组件按正则切分 `chunkText`，用 `nodeMetadata`（按 `id` 建 Map）回填：

- `image`：`src = meta.imagePath`，图片实际从后端 `/chunk_images/**` 取（devServer 代理转发）
- `code` / `table` / `formula`：取 `meta.code` / `meta.html` / `meta.formula`
- 元信息缺失时保留原占位符作为 fallback

### 新增模块方式

- **新增页面**：在 [src/views/agent/](src/views/agent/) 下建 View，在 [router/index.js](src/router/index.js) `AppLayout.children` 加路由（懒加载 + `meta.requiresAuth`/`meta.title`），在 [AppLayout.vue](src/layouts/AppLayout.vue) 的 `navGroups` 补导航项。
- **新增 API**：在 [src/api/agent/](src/api/agent/) 下按域建文件，导出方法调用 `api.get/post/...`，路径写裸路径（如 `/rag/xxx`），不要加 `/api` 前缀（由 `baseURL` 拼接）。
- **新增状态**：在 [src/stores/](src/stores/) 下建模块级 `reactive` 单例文件并导出，组件 `import` 后直接读写。

### 构建与代码检查

```bash
yarn serve          # 开发服务器（端口 3000）
yarn build          # 生产构建
yarn lint           # ESLint（vue3-essential + eslint:recommended）
```

### 调试方法

- SSE 对话不流式：检查 `VUE_APP_SSE_BASE_URL`（开发应指向 `http://localhost:8080`）；生产需确认 Nginx 配置 `X-Accel-Buffering: no`。
- 请求 401：响应拦截器会自动清 token 跳 `/login`；检查 token 是否过期或后端 `JwtTokenUserInterceptor` 是否放行该路径。
- 图片 404：`RichChunkText` 取 `meta.imagePath`，经 `/chunk_images/**` 代理到后端；确认后端 `WebMvcConfig` 暴露的物理目录与 Python 解析时落盘路径一致。
- 对话树分支异常：`chatTreeStore.getMessageMap` 依赖 `parentId`，确认后端 `/agent/sessions/{id}/messages` 返回的 `parentId` 字段名一致。

## 常见问题（FAQ）

**Q：为什么 SSE 用 fetch 而不是 axios？**
A：axios 与 webpack devServer 代理会缓冲响应，无法逐 token 推送。`chat.js` 用原生 fetch + `ReadableStream.getReader()` 直接读取，开发环境直连 `VUE_APP_SSE_BASE_URL`（默认 `localhost:8080`），生产走 Nginx 反代。

**Q：前端调 `/api/agent/chat` 后端报 404？**
A：后端路径无 `/api` 前缀。REST 请求经 `baseURL='/api'` + devServer `pathRewrite` 自动剥离；但 SSE 是 fetch 直连，[chat.js](src/api/agent/chat.js) 里写的是 `/agent/chat`（不带 `/api`），不要在这里加 `/api`。

**Q：图片显示不出来？**
A：`RichChunkText` 用 `meta.imagePath` 作为 `src`，路径需以 `/chunk_images/` 开头才能命中 devServer 代理。确认后端 `nodeMetadata` 里 `imagePath` 字段格式。

## 进一步阅读

- [AGENTS.md](../AGENTS.md) — 整体架构、关键约束
- [根目录 README.md](../README.md) — 项目总览
- [Linxing_Agent/README.md](../Linxing_Agent/README.md) — 后端服务（API 路径与鉴权契约的权威来源）
- [vue.config.js](vue.config.js) — devServer 代理配置
- [src/router/index.js](src/router/index.js) — 路由表与鉴权守卫
