# Personal Note RAG 前后端接口对接规范

## 文档信息

- **文档版本**: v1.0
- **适用阶段**: 第一阶段（前端实现）→ 第二阶段（后端实现）
- **更新日期**: 2026-04-26
- **状态**: ✅ 前端已完成 | ⏳ 后端待实现

---

## 一、接口总览

### 1.1 认证相关接口

| 接口名称 | HTTP方法 | 路径 | 认证要求 | 状态 |
|----------|----------|------|----------|------|
| 用户登录 | POST | `/api/user/login` | ❌ 不需要 | 🔴 待后端实现 |
| 用户登出 | POST | `/api/user/logout` | ✅ 需要Token | 🔴 待后端实现 |
| 获取当前用户 | GET | `/api/user/current` | ✅ 需要Token | 🔴 待后端实现 |

### 1.2 已有业务接口（已适配认证）

| 接口名称 | HTTP方法 | 路径 | 变更说明 |
|----------|----------|------|----------|
| 智能问答 | POST | `/api/rag/chat` | 自动附加userId到请求体 |
| 文件上传 | POST | `/api/ingest/file` | 自动附加userId到FormData |
| 文档列表 | GET | `/api/documents` | 自动附加userId查询参数 |
| 文档详情 | GET | `/api/documents/{id}` | 自动附加userId查询参数 |
| 删除文档 | DELETE | `/api/documents/{id}` | 自动附加userId查询参数 |
| 预览文档 | GET | `/api/documents/{id}/preview` | 自动附加userId查询参数 |
| 下载文档 | GET | `/api/documents/{id}/download` | URL附加token参数 |

---

## 二、详细接口规范

### 2.1 用户登录接口

#### 基本信息

- **URL**: `POST /api/user/login`
- **Content-Type**: `application/json`
- **描述**: 用户使用用户名和密码进行身份验证，成功后返回JWT Token

#### 请求参数

**Headers**:
```
Content-Type: application/json
```

**Body (JSON)**:
```json
{
  "username": "string (必填, 1-50字符)",
  "password": "string (必填, 无长度限制)"
}
```

**字段说明**:

| 字段名 | 类型 | 必填 | 约束 | 说明 |
|--------|------|------|------|------|
| username | string | ✅ 是 | 1-50字符 | 用户登录名，唯一标识 |
| password | string | ✅ 是 | - | 用户密码明文（后端需加密验证） |

#### 响应格式

**成功响应 (HTTP 200 OK)**:
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "id": 1,
    "username": "admin"
  }
}
```

**响应字段说明**:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | integer | 业务状态码：1=成功，0=失败 |
| msg | string | 响应消息 |
| data.token | string | JWT认证令牌（有效期建议24小时） |
| data.id | integer | 用户唯一标识ID |
| data.username | string | 用户显示名称 |

**错误响应示例**:

*400 - 参数错误*:
```json
{
  "code": 0,
  "msg": "用户名和密码不能为空",
  "data": null
}
```

*401 - 认证失败*:
```json
{
  "code": 0,
  "msg": "用户名或密码错误",
  "data": null
}
```

*403 - 账户禁用*:
```json
{
  "code": 0,
  "msg": "账户已被禁用，请联系管理员",
  "data": null
}
```

#### 后端实现要点

**Controller层** ([UserController.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/controller/UserController.java)):
```java
@PostMapping("/login")
public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
    // 1. 参数校验（@Valid + BindingResult）
    // 2. 根据username查询数据库
    // 3. 使用BCrypt验证密码
    // 4. 生成JWT Token（包含userId、username等claims）
    // 5. 构建返回对象UserLoginVO
    // 6. 返回成功结果
}
```

**Service层**:
```java
// 伪代码示意
public UserLoginVO login(String username, String password) {
    // 查询用户
    User user = userMapper.findByUsername(username);
    if (user == null) {
        throw new AccountNotFoundException("用户不存在");
    }
    
    // 验证密码
    if (!bCryptPasswordEncoder.matches(password, user.getPasswordHash())) {
        throw new PasswordIncorrectException("密码错误");
    }
    
    // 检查账户状态
    if (!user.isEnabled()) {
        throw new AccountDisabledException("账户已被禁用");
    }
    
    // 生成Token
    String token = jwtUtil.createToken(
        user.getId().toString(),
        user.getUsername(),
        expirationHours
    );
    
    return UserLoginVO.builder()
        .token(token)
        .id(user.getId())
        .username(user.getUsername())
        .build();
}
```

**DTO定义**:
- [UserLoginDTO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/UserLoginDTO.java)
- [UserLoginVO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/UserLoginVO.java)

---

### 2.2 用户登出接口

#### 基本信息

- **URL**: `POST /api/user/logout`
- **Content-Type**: `application/json`
- **Authorization**: `Bearer <token>`
- **描述**: 注销当前用户的登录状态

#### 请求参数

**Headers**:
```
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

**Body**: 无（或空对象 `{}`）

#### 响应格式

**成功响应 (HTTP 200 OK)**:
```json
{
  "code": 1,
  "msg": "登出成功",
  "data": null
}
```

**未授权响应 (HTTP 401)**:
```json
{
  "code": 0,
  "msg": "Token无效或已过期",
  "data": null
}
```

#### 后端实现要点

此接口主要用于：
1. 服务端记录登出日志
2. 将Token加入黑名单（如果使用Redis存储）
3. 清理服务端会话数据

**简化版实现**（无Token黑名单）:
```java
@PostMapping("/logout")
public Result<String> logout(HttpServletRequest request) {
    String token = request.getHeader("Authorization");
    log.info("用户登出, token: {}", token);
    
    // 可选：将token加入Redis黑名单
    // redisTemplate.opsForValue().set("blacklist:" + token, "1", TOKEN_EXPIRE, TimeUnit.HOURS);
    
    return Result.success("登出成功");
}
```

---

### 2.3 获取当前用户信息接口

#### 基本信息

- **URL**: `GET /api/user/current`
- **Authorization**: `Bearer <token>`
- **描述**: 获取当前登录用户的详细信息

#### 请求参数

**Headers**:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

#### 响应格式

**成功响应 (HTTP 200 OK)**:
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "id": 1,
    "username": "admin",
    "createdAt": "2026-01-01T00:00:00Z"
  }
}
```

#### 后端实现要点

```java
@GetMapping("/current")
public Result<UserLoginVO> getCurrentUser() {
    // 从ThreadLocal获取当前用户ID
    Long userId = BaseContext.getCurrentId();
    
    // 查询用户详细信息
    User user = userMapper.findById(userId.intValue());
    
    // 返回用户信息（不包含敏感字段如passwordHash）
    return Result.success(convertToVO(user));
}
```

**重要**: 此接口依赖拦截器从JWT中提取userId并设置到BaseContext。

---

## 三、通用规范

### 3.1 统一响应格式

所有接口必须遵循统一的响应结构：

```json
{
  "code": 1,          // 业务状态码：1=成功，其他=失败
  "msg": "success",   // 响应消息
  "data": {} or [] or null  // 业务数据
}
```

**状态码约定**:

| code值 | 含义 | 使用场景 |
|--------|------|----------|
| 1 | 成功 | 操作正常完成 |
| 0 | 失败 | 业务逻辑错误 |
| -1 | 系统异常 | 未预期的服务器错误 |

### 3.2 HTTP状态码与业务状态码的关系

| HTTP Status | Business Code | 场景说明 |
|-------------|---------------|----------|
| 200 | 1 | 成功 |
| 200 | 0 | 业务逻辑失败（如参数校验不通过） |
| 401 | - | Token无效、过期或缺失 |
| 403 | - | 权限不足 |
| 404 | - | 资源不存在 |
| 500 | - | 服务器内部错误 |

**注意**: 前端主要依据HTTP状态码判断是否需要重新登录（401），依据business code判断操作是否成功。

### 3.3 错误处理规范

**前端错误处理流程**（已在[api/index.js](file:///d:/JavaProjects/Linxing/webconsole/src/api/index.js)的响应拦截器中实现）：

```
API调用 → 收到响应 → 
  ├─ HTTP 401 → 清除Token → 跳转/login
  ├─ HTTP 403 → 显示权限不足提示
  ├─ HTTP 404 → 显示资源不存在提示
  ├─ HTTP 500 → 显示服务器错误提示
  └─ business code ≠ 1 → 抛出Error（由调用方处理）
```

**后端异常处理**（参考[GlobalExceptionHandler.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/exception/GlobalExceptionHandler.java)）：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public Result<String> handleAccountNotFound(AccountNotFoundException e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统繁忙，请稍后再试");
    }
}
```

---

## 四、认证机制详解

### 4.1 JWT Token结构

**Header（头部）**:
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload（载荷）**:
```json
{
  "userId": 1,
  "username": "admin",
  "iat": 1745422800,
  "exp": 1745509200
}
```

**Signature（签名）**:
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secretKey
)
```

### 4.2 Token生成配置

**后端配置项**（建议在application.yaml中配置）:

```yaml
jwt:
  user:
    secret-key: your-secret-key-must-be-at-least-256-bits-long-for-hs256
    ttl: 86400000           # Token有效期（毫秒）：24小时
    token-name: Authorization  # 请求头中的Token字段名
```

**Claims常量定义**（参考templates代码）:

```java
public class JwtClaimsConstant {
    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
}
```

### 4.3 Token传递方式

**请求头格式**:
```
Authorization: Bearer <JWT_TOKEN>
```

**前端自动添加机制**（已在axios请求拦截器中实现）:

```javascript
api.interceptors.request.use(config => {
  const token = authStore.getToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})
```

### 4.4 Token过期处理

**过期时间策略**:
- Access Token: 24小时（短期）
- Refresh Token: 7天（可选，用于无感刷新）

**前端处理逻辑**:
1. 发送API请求
2. 收到401响应
3. 清除本地存储的Token和用户信息
4. 重定向到登录页
5. 提示用户重新登录

**后端处理逻辑**（在拦截器中）:
```java
try {
    Claims claims = JwtUtil.parseJWT(secretKey, token);
    Long userId = Long.valueOf(claims.get(USER_ID).toString());
    BaseContext.setCurrentId(userId);
    return true;
} catch (ExpiredJwtException e) {
    response.setStatus(401);  // Token过期
    return false;
} catch (Exception e) {
    response.setStatus(401);  // Token无效
    return false;
}
```

---

## 五、现有接口适配说明

### 5.1 自动化userId注入

为了减少前端代码改动，所有需要userId的接口现在都采用**自动获取**模式。

**改造前**（手动传参）:
```javascript
// 前端代码
documentApi.list(1, 10, userId)  // 需要显式传入userId
```

**改造后**（自动获取）:
```javascript
// api/index.js内部实现
export const documentApi = {
  list(page = 1, size = 10) {
    const userId = authStore.getUserId()  // 自动从authStore获取
    return api.get('/documents', {
      params: { page, size, userId }
    })
  }
}

// 前端调用（无需传userId）
documentApi.list(1, 10)
```

### 5.2 受影响的前端组件

以下组件无需任何修改即可正常工作：

✅ [ChatPanel.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/ChatPanel.vue) - 聊天面板  
✅ [IngestPanel.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/IngestPanel.vue) - 文件上传面板  
✅ [NotesPanel.vue](file:///d:/JavaProjects/Linxing/webconsole/src/components/NotesPanel.vue) - 笔记管理面板  

**原因**: 这些组件通过导入`@/api`使用封装好的API方法，而API方法内部已经集成了authStore。

### 5.3 特殊情况：文件下载接口

文件下载接口需要在URL中携带Token，因为浏览器的下载行为无法自定义请求头：

```javascript
getDownloadUrl(id) {
  const token = authStore.getToken()
  return `/api/documents/${id}/download?token=${token}`
}
```

**后端对应处理**:
```java
@GetMapping("/{id}/download")
public ResponseEntity<Resource> downloadDocument(
    @PathVariable Integer id,
    @RequestParam(required = false) String token) {
    
    // 方式1：从query parameter获取token（用于文件下载场景）
    // 方式2：从header获取token（常规API调用场景）
    
    String authToken = (token != null) ? token : 
                       request.getHeader("Authorization").replace("Bearer ",");
    
    // 验证token并执行下载逻辑
    // ...
}
```

---

## 六、测试用例

### 6.1 Postman测试集合

**导入Postman Collection JSON**:

```json
{
  "info": {
    "name": "Personal Note RAG API",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "用户登录",
      "request": {
        "method": "POST",
        "header": [
          { "key": "Content-Type", "value": "application/json" }
        ],
        "body": {
          "mode": "raw",
          "raw": "{\n  \"username\": \"admin\",\n  \"password\": \"admin123\"\n}"
        },
        "url": {
          "raw": "{{base_url}}/user/login",
          "host": ["{{base_url}}"],
          "path": ["user", "login"]
        }
      }
    },
    {
      "name": "获取当前用户",
      "request": {
        "method": "GET",
        "header": [
          { "key": "Authorization", "value": "Bearer {{token}}" }
        ],
        "url": {
          "raw": "{{base_url}}/user/current",
          "host": ["{{base_url}}"],
          "path": ["user", "current"]
        }
      }
    },
    {
      "name": "用户登出",
      "request": {
        "method": "POST",
        "header": [
          { "key": "Content-Type", "value": "application/json" },
          { "key": "Authorization", "value": "Bearer {{token}}" }
        ],
        "url": {
          "raw": "{{base_url}}/user/logout",
          "host": ["{{base_url}}"],
          "path": ["user", "logout"]
        }
      }
    }
  ],
  "variable": [
    { "key": "base_url", "value": "http://localhost:8080/api" },
    { "key": "token", "value": "" }
  ]
}
```

### 6.2 测试步骤

#### 步骤1: 准备测试数据

```sql
-- 在PostgreSQL中插入测试用户
INSERT INTO users (username, password_hash, created_at)
VALUES (
  'testuser',
  '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
  NOW()
);

-- 密码是: test123456（使用BCrypt加密后的hash值）
-- 在线生成工具: https://bcrypt-generator.com/
```

#### 步骤2: 测试登录接口

1. 打开Postman，导入上述Collection
2. 设置环境变量 `base_url` 为你的后端地址
3. 执行"用户登录"请求
4. 复制响应中的token值到环境变量 `token`

#### 步骤3: 测试受保护接口

1. 使用环境变量中的token执行"获取当前用户"请求
2. 应该返回当前登录用户的信息
3. 尝试删除或修改token值，应该收到401错误

#### 步骤4: 测试登出接口

1. 执行"用户登出"请求
2. 再次尝试访问"获取当前用户"，应该收到401错误（如果实现了Token黑名单）

### 6.3 cURL命令测试

```bash
# 登录
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123456"}'

# 使用Token访问受保护资源（替换<YOUR_TOKEN>）
curl -X GET http://localhost:8080/api/user/current \
  -H "Authorization: Bearer <YOUR_TOKEN>"

# 登出
curl -X POST http://localhost:8080/api/user/logout \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <YOUR_TOKEN>"
```

---

## 七、安全注意事项

### 7.1 密码安全

⚠️ **严禁**在前端代码或日志中输出明文密码！

**正确做法**:
- 前端仅传输密码明文给后端（HTTPS加密传输）
- 后端接收后立即使用BCrypt验证
- 日志中只记录操作类型，不记录具体内容

```java
// ✅ 正确
log.info("用户登录: {}", username);  // 只记录用户名

// ❌ 错误
log.info("用户登录: {}, 密码: {}", username, password);  // 绝对禁止！
```

### 7.2 Token安全

**存储位置权衡**:

| 存储方式 | 安全性 | 便利性 | 推荐场景 |
|----------|--------|--------|----------|
| localStorage | ⚠️ 中等 | ✅ 高 | 开发环境 |
| sessionStorage | ⚠️ 中等 | ✅ 高 | 敏感操作 |
| HttpOnly Cookie | ✅ 高 | ⚠️ 中等 | 生产环境 |
| Memory (Variable) | ✅✅ 最高 | ❌ 低 | 极高安全需求 |

**当前选择**: localStorage（适合开发阶段）

**生产升级方案**: 
- 后端设置Set-Cookie头，标记HttpOnly、Secure、SameSite
- 前端不再手动管理Token，由浏览器自动携带Cookie

### 7.3 XSS防护

虽然当前使用localStorage存在XSS风险，但可以通过以下措施降低威胁：

1. **输入过滤**: 对所有用户输入进行转义
2. **CSP策略**: 配置Content-Security-Policy响应头
3. **依赖审计**: 定期检查第三方库的安全性
4. **HTTPS强制**: 防止中间人攻击

**Vue 3内置防护**:
- 模板默认转义HTML（v-html需谨慎使用）
- 属性绑定自动处理XSS

---

## 八、性能优化建议

### 8.1 Token缓存

对于频繁调用的接口，可以考虑在前端缓存Token解析后的用户信息：

```javascript
// utils/auth.js增强版
let cachedUser = null

export const authStore = {
  // ...existing methods...
  
  getUser() {
    if (!cachedUser && state.user) {
      cachedUser = { ...state.user }  // 浅拷贝避免引用问题
    }
    return cachedUser || state.user
  },
  
  clearAuth() {
    cachedUser = null
    // ...existing logic...
  }
}
```

### 8.2 请求去重

防止用户快速多次点击登录按钮：

```javascript
// LoginView.vue增强
let isSubmitting = false

const handleLoginSubmit = async () => {
  if (isSubmitting || !isFormValid.value) return
  
  isSubmitting = true
  try {
    // ...login logic...
  } finally {
    isSubmitting = false
  }
}
```

### 8.3 Token预刷新

在Token即将过期时提前刷新（可选功能）:

```javascript
// 在响应拦截器中检查Token剩余时间
api.interceptors.response.use(response => {
  const authorization = response.config.headers['Authorization']
  if (authorization) {
    const token = authorization.replace('Bearer ', '')
    const remainingTime = getTokenRemainingTime(token)
    
    if (remainingTime < 300000) {  // 少于5分钟
      refreshToken()  // 静默刷新Token
    }
  }
  return response
})
```

---

## 九、故障排查指南

### 9.1 常见问题及解决方案

#### 问题1: 登录后页面没有跳转

**可能原因**:
- 登录接口返回的数据结构与预期不符
- router.push()被路由守卫拦截

**排查步骤**:
1. 打开浏览器开发者工具（F12）
2. 切换到Network标签
3. 执行登录操作
4. 查看登录请求的Response是否符合格式：
   ```json
   { "code": 1, "data": { "token": "...", "id": 1, "username": "..." } }
   ```
5. 检查Console是否有JavaScript错误

**解决方案**:
- 根据实际返回结构调整[LoginView.vue](file:///d:/JavaProjects/Linxing/webconsole/src/views/LoginView.vue)第83-90行的数据处理逻辑

---

#### 问题2: 登录后访问其他页面提示401

**可能原因**:
- Token未正确保存到localStorage
- 请求头未正确携带Token
- 后端拦截器配置有误

**排查步骤**:
1. 登录成功后，打开Application > Local Storage
2. 检查是否存在`linxing_token`键
3. 访问其他页面时，查看Network请求的Request Headers
4. 确认是否有`Authorization: Bearer ...`头

**解决方案**:
- 检查[utils/auth.js](file:///d:/JavaProjects/Linxing/webconsole/src/utils/auth.js)的setToken方法是否正确执行
- 检查[api/index.js](file:///d:/JavaProjects/Linxing/webconsole/src/api/index.js)请求拦截器是否生效

---

#### 问题3: 刷新页面后丢失登录状态

**可能原因**:
- localStorage被浏览器清除
- 隐私模式限制
- 代码逻辑错误

**排查步骤**:
1. 刷新前确认localStorage中有token
2. 刷新后再次检查localStorage
3. 如果消失，检查是否有代码调用了clearAuth()

**解决方案**:
- 检查main.js初始化流程
- 确保authStore在应用启动时正确读取localStorage

---

#### 问题4: 密码显示切换按钮不工作

**可能原因**:
- CSS样式遮挡了按钮点击事件
- Vue事件绑定问题

**排查步骤**:
1. 右键点击眼睛图标，检查元素
2. 确认button元素存在且可点击
3. 查看Console是否有报错

**解决方案**:
- 检查[LoginView.vue](file:///d:/JavaProjects/Linxing/webconsole/src/views/LoginView.vue)中toggle-password按钮的CSS z-index属性

---

### 9.2 调试技巧

**启用Vue DevTools**:
1. 安装Chrome扩展：Vue.js devtools
2. 打开开发者工具，切换到Vue标签
3. 查看组件树和响应式数据状态

**网络请求调试**:
```javascript
// 在api/index.js临时添加日志
api.interceptors.request.use(config => {
  console.log('🚀 API Request:', config.url, config.params)
  return config
})

api.interceptors.response.use(response => {
  console.log('✅ API Response:', response.config.url, response.data)
  return response
})
```

**路由调试**:
```javascript
// 在router/index.js中添加日志
router.beforeEach((to, from, next) => {
  console.log('🔄 Route Change:', from.path, '->', to.path)
  console.log('🔐 Auth Status:', authStore.isAuthenticated())
  // ...existing logic...
})
```

---

## 十、版本变更记录

| 版本 | 日期 | 变更内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-04-26 | 初始版本，完成前端登录功能实现 | AI Assistant |

---

## 十一、附录

### A. 相关文件索引

**前端新增/修改文件**:
- [webconsole/src/utils/auth.js](file:///d:/JavaProjects/Linxing/webconsole/src/utils/auth.js) - 认证状态管理
- [webconsole/src/views/LoginView.vue](file:///d:/JavaProjects/Linxing/webconsole/src/views/LoginView.vue) - 登录页面
- [webconsole/src/api/auth.js](file:///d:/JavaProjects/Linxing/webconsole/src/api/auth.js) - 认证API
- [webconsole/src/api/index.js](file:///d:/JavaProjects/Linxing/webconsole/src/api/index.js) - API配置中心（已修改）
- [webconsole/src/router/index.js](file:///d:/JavaProjects/Linxing/webconsole/src/router/index.js) - 路由配置（已修改）
- [webconsole/src/layouts/AppLayout.vue](file:///d:/JavaProjects/Linxing/webconsole/src/layouts/AppLayout.vue) - 应用布局（已修改）

**后端新增文件**:
- [Linxing_Agent/.../controller/UserController.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/controller/UserController.java) - 用户控制器
- [Linxing_Agent/.../dto/UserLoginDTO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/UserLoginDTO.java) - 登录请求DTO
- [Linxing_Agent/.../dto/UserLoginVO.java](file:///d:/JavaProjects/Linxing/Linxing_Agent/src/main/java/org/linxing/linxing_agent/dto/UserLoginVO.java) - 登录响应VO

**参考模板文件**:
- [templates/BaseContext.java](file:///d:/JavaProjects/Linxing/templates/BaseContext.java) - ThreadLocal上下文
- [templates/JwtTokenUserInterceptor.java](file:///d:/JavaProjects/Linxing/templates/JwtTokenUserInterceptor.java) - JWT拦截器
- [templates/WebMvcConfiguration.java](file:///d:/JavaProjects/Linxing/templates/WebMvcConfiguration.java) - WebMvc配置

**数据库表结构**:
- [theTables.md](file:///d:/JavaProjects/Linxing/theTables.md) - 所有表DDL语句

**完整实现文档**:
- [docs/LOGIN_IMPLEMENTATION_GUIDE.md](file:///d:/JavaProjects/Linxing/docs/LOGIN_IMPLEMENTATION_GUIDE.md) - 详细实现指南

### B. 技术栈版本信息

```json
{
  "frontend": {
    "vue": "^3.2.13",
    "vue-router": "^4.0.0",
    "axios": "^1.15.2"
  },
  "backend": {
    "spring-boot": "2.x+",
    "postgresql": "14+",
    "jjwt": "0.11.x"
  }
}
```

### C. 有用的在线工具

- **JWT调试器**: https://jwt.io/
- **BCrypt生成器**: https://bcrypt-generator.com/
- **Postman**: https://www.postman.com/downloads/
- **Swagger UI**: 后端集成后访问 http://localhost:8080/doc.html

---

**📌 提示**: 本文档将与项目代码同步更新。如有疑问，请优先查阅最新的文档版本。
