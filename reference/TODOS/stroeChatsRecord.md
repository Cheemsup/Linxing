考虑将用户的聊天记录存储在数据库中，并支持构建聊天记录的树形结构。
目前的思路：数据库新建一个表，用于存储用户的聊天记录。两个重要的字段：col1记录该节点直接的父节点，col2记录该节点所属的"树"的根节点。
为什么这样设计？我考虑到的是——1、col1是构成树的必要条件；2、col2直接记录了该节点所属的"树"的根节点，这样当从数据据库取出这棵树的所有节点构造树形结构时，可以直接一次性从数据库读出所有节点（想象如果只有col1那么找到树的·所有节点就需要多次查询数据库了），相反地，当删除一颗树时，只需要根据col2删除所有节点即可。
 
如何构造树形结构？前端用户选择类似"新建聊天"，此时创建树的根节点，之后的全部聊天挂载在这个节点下。

树形结构的好处？帮助用户建立清晰的记录结构，方便查找和回看；每次新对话只选择从根节点到当前节点的唯一一条路径，避免了LLM的上下文开销和注意力问题

额外的想法：在前端展示树形结构时，可以考虑添将当前激活的聊天记录"路径"高亮显示，方便用户快速定位。

注意，现在系统中与如上所述内容相关的构建完全没开始，相关的数据库表、代码文件都没有实现。后续追加详细计划到本文件，再执行改造

---

# 可行性评估与改进分析

> 评估日期：2026-05-01
> 基于对当前项目代码库的完整分析

## 一、现有系统状态

### 1.1 当前聊天流程（无状态）

[ChatServiceImpl](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/service/impl/ChatServiceImpl.java) 的核心逻辑：

```
用户问题 → Embedding → 向量检索 + BM25混合检索 → RRF融合 → Cross-Encoder重排序 → LLM生成回答 → 返回
```

**关键发现**：
- `sessionId` 在 [ChatRequest](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/ChatRequest.java) 中存在但**仅做透传**，不用于任何存储或上下文构建
- `ChatServiceImpl` 完全是**无状态的**——每次请求独立完成 RAG 全流程
- **没有任何聊天历史**被存储到数据库，也没有任何历史被注入 LLM 上下文
- 这意味着当前每次对话都是"一锤子买卖"，没有多轮对话能力

### 1.2 前端现状

[ChatPanel.vue](file:///d:/JavaProjects/Linxing/Linxing/webconsole/src/components/ChatPanel.vue) 中：
- `messages` 数组存储在 Vue 组件的 `data` 中（内存），页面刷新即丢失
- `sessionId` 由前端生成（`'session-' + Date.now()`），每次刷新页面都会创建新 sessionId
- 没有任何持久化机制

### 1.3 数据库现状

当前 `schema.sql` 中的表：`users` / `documents` / `chunks` / `embeddings` / `activity_logs`。无聊天记录相关表。

---

## 二、方案可行性评估

### 2.1 核心设计：`parent_id` + `root_id` 双字段 ✅ 完全可行

| 评估维度 | 结论 | 说明 |
|---------|------|------|
| 数据库层面 | ✅ 可行 | PostgreSQL 完全支持，两个 INTEGER 外键即可 |
| 查询性能 | ✅ 优秀 | `WHERE root_id = ?` 一次查询拉取整棵树的所有节点，O(1) 定位 |
| 删除操作 | ✅ 简单 | `DELETE WHERE root_id = ?` 即可删除整棵树 |
| 构造树形结构 | ✅ 简单 | 后端内存中按 `parent_id` 组装为树即可 |

### 2.2 概念改进建议：拆分"会话"与"消息"两张表 🔧 强烈建议

原计划用**一张表**同时表达"树的根节点"和"树中的消息节点"，会导致：
- 根节点也是一个"消息"（但它实际上没有内容，只是一个会话容器）
- 字段语义模糊（根节点的 `parent_id` 为 NULL，`question`/`answer` 为空）

**建议改为两张表**：

```
┌─────────────────┐        ┌──────────────────────┐
│  chat_sessions   │ 1───N │   chat_messages       │
│  (会话/树的根)    │       │   (消息/树的节点)       │
├─────────────────┤       ├──────────────────────┤
│ id (PK)         │       │ id (PK)              │
│ user_id         │       │ user_id              │
│ title           │       │ session_id (FK + 根)  │ ← 这就是 col2(root_id)
│ created_at      │       │ parent_id (直接父节点) │ ← 这就是 col1
│ updated_at      │       │ role (user/assistant)│
└─────────────────┘       │ content              │
                          │ sources (JSONB)      │
                          │ created_at           │
                          └──────────────────────┘
```

**这样做的好处**：
1. **语义清晰**：会话(session)和消息(message)各司其职
2. **会话列表查询极简**：`SELECT * FROM chat_sessions WHERE user_id = ? ORDER BY updated_at DESC` 即可列出用户所有会话
3. **`root_id` 直接就是 `session_id`**，不需要额外字段，概念统一
4. **扩展性好**：后续可以在 session 上加更多属性（标签、置顶、归档等）
5. **与现有 `sessionId` 对接**：现有的 ChatRequest.sessionId 可直接对应 `chat_sessions.id` 或一个业务键

### 2.3 树形结构的实际形态 📐 完全可第一时间实现

原计划将聊天记录视为"树"——因为每次拿到的是整个 session 下的**全部消息节点**（一次 SQL 查询），每个节点携带 `session_id` 和 `parent_id`。前端在内存中按 `parent_id` 即可组装完整的树。

具体操作：

- **常规多轮对话（线性链）**：每个新消息 `parent_id` 指向上一条消息 → 自然形成 Q1→A1→Q2→A2→Q3→A3...
- **分支场景（形成树）**：用户点击历史消息"从此处重新提问"（[stroeChatsRecord.md:L570](file:///d:/JavaProjects/Linxing/reference/TODOS/stroeChatsRecord.md#L570)），新消息的 `parent_id` 指向被点击的历史消息 → 该历史消息产生两个子节点，自然分叉
- **级联删除某节点**：后端递归收集该节点及所有子孙节点 ID → 批量 DELETE
- **路径高亮**：沿当前消息的 `parent_id` 链回溯到根，即得到"激活路径"

**结论**：不需要异步实现，树形结构展示、分支、级联删除在 Phase 5~6 **同步实现**。

### 2.4 LLM 上下文构建 🧠 关键问题

原计划提到"每次新对话只选择从根节点到当前节点的唯一一条路径，避免了LLM的上下文开销"——这个思路正确，但需要补充：

**补充建议：**
- 沿 `parent_id` 链回溯，收集从根到当前节点的所有消息，组装为 LLM 的多轮对话上下文
- 需要增加**最大回溯轮数**限制（如最多取最近 10 轮），防止超出 LLM 的上下文窗口
- 需要增加**token 估算**（简单按字符数估算即可），超出阈值时截断早期消息

### 2.5 与现有 `sessionId` 的整合 🔗

当前 `ChatRequest.sessionId` 是 String 类型，前端传 `"session-"+Date.now()`。建议：
- `sessionId` → 对应 `chat_sessions` 表的某个唯一标识
- 首次聊天（sessionId 对应的 session 不存在）→ 自动创建新 session
- 后续使用同一个 sessionId → 消息挂载到该 session 下

### 2.6 外键设计分析 🔗

| 外键 | 建议 | 理由 |
|------|------|------|
| `chat_messages.session_id → chat_sessions(id)` | ✅ **保留** + `ON DELETE CASCADE` | 删除会话 = 删除全部消息，语义明确；非自引用，无循环风险；省去应用层手动清理代码 |
| `chat_messages.parent_id → chat_messages(id)` | ❌ **移除** | 自引用外键增加复杂度，`ON DELETE CASCADE` 过于危险（误删一个节点牵连整棵子树），`ON DELETE SET NULL` 产生语义混乱的孤儿消息。子树级联删除由应用层**显式处理**（递归收集子孙 ID → 批量 DELETE），更可控、可记录日志 |

**parent_id 无外键时的应用层保障**：
- 插入消息时，由 Service 层校验 `parentId` 是否属于同一 `sessionId`
- 子树删除时，Service 层通过递归 CTE 或逐层查询收集所有子孙节点 ID，然后 `DELETE WHERE id IN (...)`
- MyBatis Mapper 不需要额外改动（无外键不影响查询/插入）

---

## 三、还需考虑的问题

| 问题 | 建议 |
|------|------|
| 删除会话时是否级联删除关联消息？ | 是，通过 `session_id` 外键 `ON DELETE CASCADE` 自动完成 |
| 删除消息及其子树？ | 应用层递归收集子孙节点 ID，批量 DELETE；不依赖自引用外键 |
| 消息列表是否需要分页？ | 拉取树的所有节点通常数量不大（单次会话几十条），暂不需要分页；但会话列表（展示用户所有会话）需要分页 |
| 是否提供消息的"编辑"？ | 是，此处的“编辑”指的是用户可以对于不满意的提问重新编辑、提问，其性质与“从当前节点继续提问”相同，创建新消息节点（类似deepseek官网的设计） |
| 回答中引用的来源（sources）是否随消息存储？ | 是，存储为 JSONB 字段，方便回看时展示引用来源 |
| 多用户隔离？ | 与现有体系一致，所有表带 `user_id` |

---

# 详细施工计划

> ⚠️ 数据库 DDL 部分由人工执行，后端/前端代码由开发者按计划逐步实施。
> 以下 Phase 按依赖顺序排列，必须依次执行。

---

## Phase 1 — 数据库建表（DDL）⚠️ 需人工执行

### 1.1 新建表 `chat_sessions`

```sql
-- 会话表：每次"新建聊天"创建一个会话，即一棵树的根
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
```

### 1.2 新建表 `chat_messages`

```sql
-- 消息表：会话中的每一条问答消息，通过 parent_id 形成树形结构
-- 注意：parent_id 不设外键约束，子树级联删除由应用层显式处理（见评估 2.6 节）
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
```

### 1.3 设计要点说明

| 字段 | 对应原计划 | 说明 |
|------|-----------|------|
| `chat_messages.parent_id` | **col1**（直接父节点） | NULL 表示该节点直接挂在根下；普通消息指向上一轮消息 |
| `chat_messages.session_id` | **col2**（树根节点） | 直接用会话ID作为根标识，消除冗余字段 |
| `chat_sessions` 表 | 原计划无 | 额外拆出，承载会话元信息（标题、时间等），让消息表专注于树结构 |

### 1.4 树结构示意

```
chat_sessions (id=1, title="讨论RAG方案")
  │
  ├── chat_messages (id=1, session_id=1, parent_id=NULL, role="user",     content="什么是RAG？")
  │     └── chat_messages (id=2, session_id=1, parent_id=1, role="assistant", content="RAG是检索增强生成...")
  │           └── chat_messages (id=3, session_id=1, parent_id=2, role="user",     content="如何优化检索？")
  │                 └── chat_messages (id=4, session_id=1, parent_id=3, role="assistant", content="可以从以下方面...")
  │
  └── chat_messages (id=5, session_id=1, parent_id=NULL, role="user",     content="新建一个话题：数据库选型")
        └── chat_messages (id=6, session_id=1, parent_id=5, role="assistant", content="PostgreSQL是...")
```

- 查到整棵树：`SELECT * FROM chat_messages WHERE session_id = 1 ORDER BY created_at`
- 回溯路径（id=4 到根）：沿 `parent_id` 链回溯 → 4→3→2→1→NULL，收集到 4 条消息作为 LLM 上下文
- 删除整棵树：`DELETE FROM chat_sessions WHERE id = 1`（CASCADE 自动删除所有消息）

---

## Phase 2 — 后端实体层（Entity / DTO / VO）

### 2.1 新建 Entity

**文件**：`src/main/java/org/linxing/linxing_agent/entity/ChatSession.java`

```java
package org.linxing.linxing_agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private Integer id;
    private Integer userId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
```

**文件**：`src/main/java/org/linxing/linxing_agent/entity/ChatMessage.java`

```java
package org.linxing.linxing_agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private Integer id;
    private Integer userId;
    private Integer sessionId;
    private Integer parentId;
    private String role;
    private String content;
    private String sources;
    private OffsetDateTime createdAt;
}
```

### 2.2 修改 DTO

**修改 [ChatRequest](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/ChatRequest.java)**：
1. `sessionId` 字段含义从"透传字符串"改为"会话ID（对应 `chat_sessions.id`）"，类型改为 `Integer`
2. 新增 `parentMessageId` 字段（Integer, 可为空），用于分支场景：用户点击历史消息"从此处重新提问"时，前端传入该历史消息的 ID 作为 `parentMessageId`

**修改 [ChatResponse](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/ChatResponse.java)**：新增 `messageId` 字段，返回刚保存的消息ID。

### 2.3 新建 VO（前端展示用）

**文件**：`src/main/java/org/linxing/linxing_agent/vo/ChatSessionVO.java`

```java
package org.linxing.linxing_agent.vo;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class ChatSessionVO {
    private Integer id;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer messageCount;
}
```

---

## Phase 3 — 后端 Mapper 层

### 3.1 新建 Mapper 接口

**文件**：`src/main/java/org/linxing/linxing_agent/mapper/ChatSessionMapper.java`

```java
package org.linxing.linxing_agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.ChatSession;
import java.util.List;

@Mapper
public interface ChatSessionMapper {
    int insert(ChatSession session);
    ChatSession selectById(@Param("id") Integer id);
    List<ChatSession> selectByUserId(@Param("userId") Integer userId,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);
    int countByUserId(@Param("userId") Integer userId);
    int updateTitle(@Param("id") Integer id, @Param("title") String title);
    int updateUpdatedAt(@Param("id") Integer id);
    int deleteById(@Param("id") Integer id);
}
```

**文件**：`src/main/java/org/linxing/linxing_agent/mapper/ChatMessageMapper.java`

```java
package org.linxing.linxing_agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.entity.ChatMessage;
import java.util.List;

@Mapper
public interface ChatMessageMapper {
    int insert(ChatMessage message);
    ChatMessage selectById(@Param("id") Integer id);
    // 按会话拉取所有消息（一次查询拿到整棵树）
    List<ChatMessage> selectBySessionId(@Param("sessionId") Integer sessionId);
    // 查询某会话中最新的消息ID（线性对话时确定 parent_id）
    Integer selectLatestIdBySessionId(@Param("sessionId") Integer sessionId);
    // 子树删除：递归收集某节点所有子孙节点 ID 后批量删除
    int deleteByIds(@Param("ids") List<Integer> ids);
    int deleteBySessionId(@Param("sessionId") Integer sessionId);
}
```

### 3.2 新建 Mapper XML

**文件**：`src/main/resources/mapper/ChatSessionMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.linxing.linxing_agent.mapper.ChatSessionMapper">

    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO chat_sessions (user_id, title, created_at, updated_at)
        VALUES (#{userId}, #{title}, NOW(), NOW())
    </insert>

    <select id="selectById" resultType="org.linxing.linxing_agent.entity.ChatSession">
        SELECT id, user_id, title, created_at, updated_at
        FROM chat_sessions WHERE id = #{id}
    </select>

    <select id="selectByUserId" resultType="org.linxing.linxing_agent.entity.ChatSession">
        SELECT id, user_id, title, created_at, updated_at
        FROM chat_sessions
        WHERE user_id = #{userId}
        ORDER BY updated_at DESC
        LIMIT #{limit} OFFSET #{offset}
    </select>

    <select id="countByUserId" resultType="int">
        SELECT COUNT(*) FROM chat_sessions WHERE user_id = #{userId}
    </select>

    <update id="updateTitle">
        UPDATE chat_sessions SET title = #{title}, updated_at = NOW()
        WHERE id = #{id}
    </update>

    <update id="updateUpdatedAt">
        UPDATE chat_sessions SET updated_at = NOW() WHERE id = #{id}
    </update>

    <delete id="deleteById">
        DELETE FROM chat_sessions WHERE id = #{id}
    </delete>

</mapper>
```

**文件**：`src/main/resources/mapper/ChatMessageMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.linxing.linxing_agent.mapper.ChatMessageMapper">

    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO chat_messages (user_id, session_id, parent_id, role, content, sources, created_at)
        VALUES (#{userId}, #{sessionId}, #{parentId}, #{role}, #{content},
                #{sources}::jsonb, NOW())
    </insert>

    <select id="selectById" resultType="org.linxing.linxing_agent.entity.ChatMessage">
        SELECT id, user_id, session_id, parent_id, role, content, sources, created_at
        FROM chat_messages WHERE id = #{id}
    </select>

    <select id="selectBySessionId" resultType="org.linxing.linxing_agent.entity.ChatMessage">
        SELECT id, user_id, session_id, parent_id, role, content, sources, created_at
        FROM chat_messages
        WHERE session_id = #{sessionId}
        ORDER BY created_at ASC
    </select>

    <select id="selectLatestIdBySessionId" resultType="int">
        SELECT id FROM chat_messages
        WHERE session_id = #{sessionId}
        ORDER BY created_at DESC
        LIMIT 1
    </select>

    <delete id="deleteByIds">
        DELETE FROM chat_messages WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
    </delete>

    <delete id="deleteBySessionId">
        DELETE FROM chat_messages WHERE session_id = #{sessionId}
    </delete>

</mapper>
```

---

## Phase 4 — 后端 Service 层改造

### 4.1 新建 `IChatSessionService` / `ChatSessionServiceImpl`

提供会话管理能力：
- `createSession(userId)` → 创建新会话，返回 sessionId
- `listSessions(userId, page, size)` → 分页列出用户的会话列表
- `deleteSession(sessionId)` → 删除会话及其所有消息
- `updateTitle(sessionId, title)` → 修改会话标题

### 4.2 改造 `ChatServiceImpl.chat()` 核心逻辑

**改造前**（当前）：
```
接收问题 → RAG检索 → LLM生成 → 返回回答（无状态）
```

**改造后**：
```
1. 接收 question + sessionId + parentMessageId
2. 若 sessionId 为空 → 创建新 chat_session（首次聊天）
3. 若 sessionId 有效 → 查找 chat_session，不存在则创建
4. 确定 parent_id：
   - 若 parentMessageId 有值（分支场景）→ 直接使用
   - 否则查找该 session 的最新一条消息 ID → 使用
   - 若 session 无任何消息 → parent_id = NULL
5. 保存用户消息到 chat_messages（role=user, parent_id如上确定）
6. 沿 parent_id 链回溯历史消息，构建 LLM 多轮对话上下文
7. RAG检索（同改造前）
8. LLM 生成（Prompt中加入历史对话上下文 + 检索到的知识库内容）
9. 保存助手回答到 chat_messages（role=assistant, parent_id=刚保存的用户消息ID, sources=引用来源）
10. 更新 chat_sessions.updated_at
11. 返回回答 + messageId（用户消息和助手消息各一个？或返回助手消息ID）
```

### 4.3 LLM Prompt 改造

**改造前**每次请求的 Prompt 结构：
```
系统指令 + 检索到的参考内容 + 用户问题
```

**改造后 Prompt 结构**：
```
系统指令 + 历史对话（沿树回溯） + 检索到的参考内容 + 用户问题
```

历史对话格式示例：
```
历史对话：
用户：什么是RAG？
助手：RAG是检索增强生成技术...

用户：如何优化检索？
（当前问题）
```

### 4.4 上下文窗口管理

在 Service 层增加简单策略：
- 设置最大回溯轮数常量 `MAX_HISTORY_ROUNDS = 10`（约20条消息）
- 回溯时按 `parent_id` 链从当前节点向上追溯，直到达到上限或到达根
- 将收集到的消息按时间正序排列，注入 LLM context
- 估算总 token 数（简单按字符数/2），超出模型上限时截断最早的消息

---

## Phase 5 — 后端 Controller 层改造

**设计决策**：不新建独立的 `ChatSessionController`。会话管理是 chat 功能的"附属"能力，所有端点直接集成到现有 `ChatController` 中。`POST /chat/sessions` 创建会话接口去掉，由 ChatServiceImpl通过接口间接调用`ChatServiceImpl.resolveSession()` 在首次聊天时自动创建。

### 5.1 改造现有 `ChatController`（新增 4 个端点）

在现有 [ChatController](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/controller/ChatController.java) 中新增以下方法，注入 `IChatSessionService` 和 `ChatMessageMapper`：

```java
// 新增依赖注入
private final IChatSessionService chatSessionService;
private final ChatMessageMapper chatMessageMapper;

// 1. 会话列表（侧边栏用）
@GetMapping("/sessions")
public Result<PageResult<ChatSessionVO>> listSessions(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
    Integer userId = getCurrentUserId();
    return Result.success(chatSessionService.listSessions(userId, page, size));
}

// 2. 删除会话（级联删除所有消息）
@DeleteMapping("/sessions/{sessionId}")
public Result<Void> deleteSession(@PathVariable Integer sessionId) {
    chatSessionService.deleteSession(sessionId);
    return Result.success();
}

// 3. 获取会话消息树（返回平面列表，前端按 parent_id 组装）
@GetMapping("/sessions/{sessionId}/messages")
public Result<List<ChatMessageVO>> getMessages(@PathVariable Integer sessionId) {
    List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
    List<ChatMessageVO> vos = messages.stream().map(this::toMessageVO).collect(Collectors.toList());
    return Result.success(vos);
}

// 4. 删除消息子树（递归收集子孙节点后批量删除）
@DeleteMapping("/messages/{messageId}/subtree")
public Result<Void> deleteSubtree(@PathVariable Integer messageId) {
    List<Integer> ids = collectSubtreeIds(messageId);
    if (!ids.isEmpty()) {
        chatMessageMapper.deleteByIds(ids);
    }
    return Result.success();
}
```

`collectSubtreeIds` 逻辑：从 `messageId` 出发，BFS/DFS 收集所有 `parent_id` 指向该节点的子孙消息 ID，返回 ID 列表。

### 5.2 现有 `/rag/chat` 保持不变

现有 `POST /rag/chat` 接口签名不变，内部逻辑已按 Phase 4.2 改造完成。

---

## Phase 6 — 前端改造

### 6.1 ChatPanel.vue 改造

| 改造项 | 说明 |
|--------|------|
| 会话列表 | 新增左侧会话列表栏，展示用户所有会话（调用 `GET /rag/sessions`），支持切换、删除 |
| 新建聊天 | "新建聊天"按钮 → 前端将 `sessionId` 置为 `null` → 用户输入问题并发送 → 后端自动创建会话 |
| 加载历史 | 切换 session 时 → 调用 `GET /rag/sessions/{id}/messages` → 前端按 `parent_id` 构建树形数据结构 → 渲染为对话 |
| 发送消息 | 携带当前 `sessionId` + `parentMessageId`（默认指向最新消息，分支时为被点击的历史消息ID） |
| sessionId 持久化 | `localStorage` 记住当前活跃 sessionId，刷新后恢复 |
| 树形展示 | 消息按树形层级展示，每个节点可折叠/展开子节点 |
| 路径高亮 | 当前激活的消息链（从根到当前节点的 `parent_id` 链）高亮显示 |
| 分支入口 | 每条历史消息提供"从此重新提问"按钮 → 输入框激活 → 发送时 `parentMessageId` 指向该消息 |
| 删除子树 | 每条消息提供"删除此分支"按钮 → 调用 `DELETE /rag/messages/{id}/subtree`（后端递归收集+批量删除）→ 前端移除对应节点 |

### 6.2 前端树构建伪代码

```javascript
// 从后端获取平面列表，按 parent_id 构建树
function buildTree(messages) {
  const map = new Map()
  const roots = []

  messages.forEach(msg => {
    map.set(msg.id, { ...msg, children: [] })
  })

  messages.forEach(msg => {
    const node = map.get(msg.id)
    if (msg.parentId && map.has(msg.parentId)) {
      map.get(msg.parentId).children.push(node)
    } else {
      roots.push(node)
    }
  })

  return roots
}

// 从当前节点回溯激活路径（根 → 当前节点的 parent_id 链）
function getActivePath(nodeId, map) {
  const path = []
  let current = map.get(nodeId)
  while (current) {
    path.unshift(current.id)
    current = current.parentId ? map.get(current.parentId) : null
  }
  return new Set(path)
}
```

### 6.3 新增 API 调用（api/index.js）

```javascript
// 不再有 create() —— 会话由首次聊天自动创建
export const chatSessionApi = {
  list(page = 1, size = 20) {
    return api.get('/rag/sessions', { params: { page, size } })
  },
  delete(id) {
    return api.delete(`/rag/sessions/${id}`)
  },
  getMessages(sessionId) {
    return api.get(`/rag/sessions/${sessionId}/messages`)
  },
  deleteSubtree(messageId) {
    return api.delete(`/rag/messages/${messageId}/subtree`)
  }
}
```

---

## Phase 7 — 执行顺序与依赖

```
Phase 1 (DDL) ──→ Phase 2 (Entity/DTO) ──→ Phase 3 (Mapper) ──→ Phase 4 (Service)
                                                                       │
                                                                       ▼
                                                              Phase 5 (Controller)
                                                                       │
                                                                       ▼
                                                              Phase 6 (Frontend)
```

| 阶段 | 负责方 | 预估产出 |
|------|--------|---------|
| Phase 1 | **人工执行 DDL** | 数据库两张新表 + 索引 |
| Phase 2 | 开发者 | 2 个 Entity + 2 个 VO + DTO 改造 |
| Phase 3 | 开发者 | 2 个 Mapper 接口 + 2 个 XML |
| Phase 4 | 开发者 | 1 个新 Service + ChatServiceImpl 核心改造 |
| Phase 5 | 开发者 | ChatController 改造（新增 4 个端点，不新建 Controller） |
| Phase 6 | 开发者 | ChatPanel.vue 改造 + 新增 API + 会话管理 UI |

---

## 八、风险与注意事项

1. **DDL 务必先执行**：Phase 2~6 的所有代码依赖数据库表存在，否则启动即报错
2. **多用户隔离**：所有查询必须带 `user_id` 条件（与现有体系保持一致）
3. **外键策略**：`session_id → chat_sessions(id)` 保留 `ON DELETE CASCADE`（删除会话自动清理消息）；`parent_id` 不设外键（自引用风险），子树级联删除由 Service 层递归收集子孙 ID 后调用 `deleteByIds` 批量执行
4. **subtree 删除端点**：Controller 需新增 `DELETE /chat/messages/{messageId}/subtree`，Service 层递归收集子孙节点后批量删除
5. **向后兼容**：现有 `/rag/chat` 接口签名不变，只是内部逻辑增强了持久化和多轮对话能力
6. **前端代理路径**：新增的 `/chat/sessions` 等接口后端路径不带 `/api` 前缀，前端调用时使用 `/api/chat/sessions`（由 vue.config.js 代理转发）
7. **首次聊天自动创建会话**：当 sessionId 为空或无效时，自动创建新会话，避免额外的 createSession 调用
