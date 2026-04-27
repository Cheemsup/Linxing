# Personal Note RAG 登录功能前端实现文档

## 一、功能概述

本文档详细说明了Personal Note RAG系统的登录功能前端实现，包括用户认证、权限控制、会话管理等核心功能的完整技术方案。

### 1.1 技术栈

- **前端框架**: Vue 3 (Composition API)
- **路由管理**: Vue Router 4
- **HTTP客户端**: Axios 1.x
- **状态管理**: Vue 3 Reactive API（轻量级方案）
- **UI设计**: 原生CSS3 + 响应式设计
- **后端接口规范**: RESTful API + JWT Token认证

### 1.2 核心特性

✅ 用户登录/登出功能
✅ JWT Token自动管理与刷新
✅ 路由级别的权限控制守卫
✅ 全局请求拦截器（自动添加Authorization头）
✅ 全局响应拦截器（处理401/403等错误）
✅ 用户信息全局状态管理
✅ 响应式登录界面设计
✅ 安全的密码输入与显示切换

---

## 二、系统架构设计

### 2.1 文件结构

```
webconsole/src/
├── api/
│   ├── index.js              # API配置中心（已更新）
│   └── auth.js               # 认证相关API接口（新增）
├── utils/
│   └── auth.js               # 用户认证状态管理（新增）
├── views/
│   └── LoginView.vue         # 登录页面组件（新增）
├── layouts/
│   └── AppLayout.vue         # 应用布局组件（已更新）
└── router/
    └── index.js              # 路由配置（已更新）
```

### 2.2 数据流程图

```
用户操作 → 前端组件 → 状态管理(authStore) → API层(axios) → 后端服务
                ↓                    ↓
           localStorage          HTTP Headers
           (持久化存储)        (JWT Token)
```

---

## 三、核心模块详解

### 3.1 用户认证状态管理 (`utils/auth.js`)

**文件路径**: [auth.js](file:///d:/JavaProjects/Linxing/webconsole/src/utils/auth.js)

**功能说明**:
- 使用Vue 3的`reactive`API实现响应式状态管理
- 通过localStorage实现Token和用户信息的持久化存储
- 提供统一的认证状态访问接口

**核心方法**:

| 方法名 | 参数 | 返回值 | 说明 |
|--------|------|--------|------|
| `setToken(token)` | String (JWT) | void | 设置并存储认证令牌 |
| `setUser(user)` | Object | void | 设置当前登录用户信息 |
| `getUser()` | - | Object/null | 获取当前用户信息 |
| `getUserId()` | - | Number/null | 获取当前用户ID |
| `getUsername()` | - | String/null | 获取当前用户名 |
| `getToken()` | - | String | 获取当前JWT令牌 |
| `clearAuth()` | - | void | 清除所有认证信息 |
| `isAuthenticated()` | - | Boolean | 检查是否已认证 |

**使用示例**:
```javascript
import { authStore } from '@/utils/auth'

// 登录成功后
authStore.setToken('eyJhbGciOiJIUzI1NiIs...')
authStore.setUser({ id: 1, username: 'admin' })

// 在其他组件中使用
const userId = authStore.getUserId()
const isAuthenticated = authStore.isAuthenticated()

// 登出时
authStore.clearAuth()
```

---

### 3.2 登录页面组件 (`views/LoginView.vue`)

**文件路径**: [LoginView.vue](file:///d:/JavaProjects/Linxing/webconsole/src/views/LoginView.vue)

#### 3.2.1 UI设计特点

**视觉风格**:
- 渐变背景：紫色渐变（#667eea → #764ba2）营造现代感
- 白色卡片居中布局，带阴影和圆角
- 动态背景装饰元素（浮动的圆形动画）
- 统一的品牌标识和系统名称展示

**交互体验**:
- 表单验证：实时检查用户名和密码是否为空
- 密码显示切换：支持明文/密文切换显示
- 加载状态反馈：登录按钮显示加载动画
- 错误提示：红色警告框，带抖动动画效果
- 键盘友好：支持Enter键提交表单

**响应式断点**:
- 桌面端（> 480px）：标准宽度420px卡片
- 平板端（≤ 480px）：自适应边距，缩小内边距
- 移动端（≤ 360px）：最小化内边距，优化触控区域

#### 3.2.2 核心逻辑

```javascript
// 表单数据模型
loginForm = {
  username: '',      // 用户名
  password: ''       // 密码
}

// 表单验证规则
isFormValid = computed(() => 
  loginForm.username.trim() !== '' && 
  loginForm.password.trim() !== ''
)

// 提交处理函数
handleLoginSubmit = async () => {
  // 1. 验证表单
  // 2. 调用登录API
  // 3. 处理响应（成功/失败）
  // 4. 存储Token和用户信息
  // 5. 跳转到主页
}
```

**错误处理策略**:
- **401未授权**: 显示"用户名或密码错误"
- **403禁止访问**: 显示"账户已被禁用"
- **网络超时**: 显示"请求超时，请检查网络连接"
- **其他错误**: 显示通用错误消息

---

### 3.3 API请求拦截器 (`api/index.js`)

**文件路径**: [index.js](file:///d:/JavaProjects/Linxing/webconsole/src/api/index.js)

#### 3.3.1 请求拦截器

**功能**: 自动在每个请求头中添加JWT Token

```javascript
api.interceptors.request.use(config => {
  const token = authStore.getToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})
```

**工作原理**:
1. 从authStore获取当前Token
2. 如果Token存在，添加到请求头的`Authorization`字段
3. 格式遵循Bearer Token标准：`Bearer <token>`

#### 3.3.2 响应拦截器

**功能**: 统一处理HTTP响应和错误

**成功响应处理**:
- 检查后端返回的code字段
- code=1表示业务成功，正常返回
- 其他code值视为业务失败，抛出异常

**错误码处理矩阵**:

| HTTP状态码 | 处理方式 | 用户提示 |
|------------|----------|----------|
| 401 | 清除认证信息，跳转登录页 | 自动重定向到/login |
| 403 | 控制台输出错误 | "权限不足" |
| 404 | 控制台输出错误 | "资源不存在" |
| 500 | 控制台输出错误 | "服务器内部错误" |
| timeout | 控制台输出错误 | "请求超时" |
| 其他 | 控制台输出错误 | "网络错误" |

---

### 3.4 路由守卫配置 (`router/index.js`)

**文件路径**: [index.js](file:///d:/JavaProjects/Linxing/webconsole/src/router/index.js)

#### 3.4.1 路由元信息定义

每个路由配置包含`meta`字段：

```javascript
{
  path: '/chat',
  name: 'Chat',
  component: ChatView,
  meta: {
    title: '智能问答',       // 页面标题
    requiresAuth: true       // 是否需要认证
  }
}
```

#### 3.4.2 守卫逻辑

```javascript
router.beforeEach((to, from, next) => {
  // 1. 设置页面标题
  document.title = to.meta.title ? `${to.meta.title} - Personal Note RAG` : 'Personal Note RAG'
  
  // 2. 检查认证状态
  const isAuthenticated = authStore.isAuthenticated()
  
  // 3. 权限判断
  if (to.meta.requiresAuth && !isAuthenticated) {
    // 未登录用户访问受保护页面 → 重定向到登录页
    next('/login')
  } else if (to.path === '/login' && isAuthenticated) {
    // 已登录用户访问登录页 → 重定向到首页
    next('/chat')
  } else {
    // 正常放行
    next()
  }
})
```

**路由保护列表**:
- ✅ `/login` - 公开页面（无需认证）
- 🔒 `/chat` - 受保护页面（需要认证）
- 🔒 `/ingest` - 受保护页面（需要认证）
- 🔒 `/notes` - 受保护页面（需要认证）

---

### 3.5 应用布局更新 (`layouts/AppLayout.vue`)

**文件路径**: [AppLayout.vue](file:///d:/JavaProjects/Linxing/webconsole/src/layouts/AppLayout.vue)

#### 新增功能

**用户信息显示区**:
```vue
<div v-if="isLoggedIn" class="user-info">
  <span class="username">{{ username }}</span>
  <button @click="handleLogout" class="logout-btn">退出登录</button>
</div>
```

**退出登录处理**:
```javascript
const handleLogout = () => {
  authStore.clearAuth()     // 清除本地认证信息
  router.push('/login')     // 跳转到登录页
}
```

**响应式适配**:
- 桌面端：用户信息显示在导航栏右侧
- 移动端：用户信息独占一行，居中显示在导航栏下方

---

## 四、API接口规范

### 4.1 用户登录接口

**接口地址**: `POST /api/user/login`

**请求头**:
```
Content-Type: application/json
```

**请求体**:
```json
{
  "username": "string",   // 用户名（必填）
  "password": "string"    // 密码（必填）
}
```

**成功响应** (HTTP 200):
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",  // JWT令牌
    "id": 1,                              // 用户ID
    "username": "admin"                   // 用户名
  }
}
```

**失败响应示例**:

*用户名或密码错误* (HTTP 401):
```json
{
  "code": 0,
  "msg": "用户名或密码错误",
  "data": null
}
```

*账户被禁用* (HTTP 403):
```json
{
  "code": 0,
  "msg": "账户已被禁用，请联系管理员",
  "data": null"
}
```

---

### 4.2 用户登出接口

**接口地址**: `POST /api/user/logout`

**请求头**:
```
Content-Type: application/json
Authorization: Bearer <token>
```

**响应** (HTTP 200):
```json
{
  "code": 1,
  "msg": "登出成功",
  "data": null
}
```

---

### 4.3 获取当前用户信息

**接口地址**: `GET /api/user/current`

**请求头**:
```
Authorization: Bearer <token>
```

**响应** (HTTP 200):
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

---

### 4.4 已有接口的参数调整说明

以下接口已自动适配新的认证机制，无需手动传递userId参数：

| 接口 | 变更说明 |
|------|----------|
| `POST /rag/chat` | 自动从authStore获取userId附加到请求体 |
| `POST /ingest/file` | 自动从authStore获取userId附加到FormData |
| `GET /documents` | 自动从authStore获取userId作为查询参数 |
| `GET /documents/{id}` | 同上 |
| `DELETE /documents/{id}` | 同上 |
| `GET /documents/{id}/preview` | 同上 |
| `GET /documents/{id}/download` | URL中附加token参数用于鉴权 |

**重要变更**: 所有API调用不再需要显式传入`userId`参数，系统会自动从登录状态中提取。

---

## 五、安全机制说明

### 5.1 Token存储策略

- **存储位置**: localStorage（浏览器本地存储）
- **键名规范**: 
  - Token: `linxing_token`
  - 用户信息: `linxing_user`
- **安全性考虑**:
  - ⚠️ localStorage容易受到XSS攻击
  - 建议：后续可升级为HttpOnly Cookie方案
  - 当前阶段适合开发测试环境使用

### 5.2 传输安全

- **协议要求**: 生产环境必须使用HTTPS
- **Token格式**: JWT (JSON Web Token)
- **Header格式**: `Authorization: Bearer <token>`
- **过期时间**: 建议设置为24小时（后端配置）

### 5.3 前端防护措施

1. **路由守卫**: 防止未认证用户直接访问受保护URL
2. **请求拦截器**: 确保每个API请求都携带有效Token
3. **响应拦截器**: 自动处理401错误，防止信息泄露
4. **状态清理**: 登出时彻底清除所有敏感信息

---

## 六、使用指南

### 6.1 开发环境启动

```bash
# 进入前端项目目录
cd webconsole

# 安装依赖（首次运行）
npm install 或 yarn install

# 启动开发服务器
npm run serve 或 yarn serve

# 访问地址
http://localhost:8080  # 默认端口，具体以控制台输出为准
```

### 6.2 测试账号准备

由于后端尚未完全实现，建议先准备测试数据：

**SQL脚本示例**（基于[theTables.md](file:///d:/JavaProjects/Linxing/theTables.md)中的users表结构）：

```sql
-- 创建测试用户（密码需使用BCrypt加密）
INSERT INTO users (username, password_hash, created_at) 
VALUES (
  'admin',
  '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',  -- 密码: admin123
  NOW()
);
```

### 6.3 功能测试清单

#### 基础功能测试

- [ ] 打开浏览器访问系统首页，是否自动跳转到登录页？
- [ ] 输入正确的用户名和密码，点击登录按钮
- [ ] 登录成功后是否跳转到智能问答页面？
- [ ] 导航栏是否显示当前登录的用户名？
- [ ] 点击"退出登录"按钮是否返回登录页？

#### 权限控制测试

- [ ] 未登录状态下，直接在地址栏输入 `/chat` 是否被重定向到登录页？
- [ ] 登录后访问 `/login` 是否自动跳转到首页？
- [ ] 清除浏览器缓存后，刷新页面是否需要重新登录？

#### 错误处理测试

- [ ] 不输入用户名和密码，点击登录按钮是否有验证提示？
- [ ] 输入错误的密码，是否显示错误信息？
- [ ] 断开网络连接后点击登录，是否有友好的错误提示？

#### 响应式设计测试

- [ ] 在Chrome开发者工具中切换到移动设备视图
- [ ] 调整窗口大小至480px以下，界面是否正常显示？
- [ ] 调整窗口大小至360px以下，界面是否正常显示？
- [ ] 在真实手机浏览器中打开，触摸操作是否流畅？

---

## 七、后续阶段规划

### 第二阶段：后端JWT工具类开发

**待开发内容**:
1. JWT工具类（生成/解析/验证Token）
2. BCrypt密码加密工具
3. UserDetailsService实现
4. UserController完整业务逻辑
5. 自定义UserDetails类

**参考文件位置**:
- [BaseContext.java](file:///d:/JavaProjects/Linxing/templates/BaseContext.java)
- [JwtTokenUserInterceptor.java](file:///d:/JavaProjects/Linxing/templates/JwtTokenUserInterceptor.java)

### 第三阶段：拦截器配置与WebMvc整合

**待开发内容**:
1. 将templates目录下的代码集成到项目中
2. 配置JWT属性（密钥、过期时间等）
3. 注册自定义拦截器
4. 配置静态资源映射
5. 测试拦截器的拦截/放行逻辑

**关键配置点**:
- 拦截路径：`/user/**`（排除 `/user/user/login`）
- Token头名称：`Authorization`
- ThreadLocal用户上下文维护

### 第四阶段：前后端联调

**联调步骤**:
1. 启动后端服务，确认数据库连接正常
2. 使用Postman/curl测试登录接口
3. 前端对接真实后端接口
4. 验证完整的登录→访问→登出流程
5. 性能测试与压力测试

### 第五阶段：安全加固

**优化方向**:
1. Token刷新机制（Refresh Token）
2. HttpOnly Cookie替代localStorage
3. CSRF防护
4. Rate Limiting（登录频率限制）
5. Security Headers配置
6. 敏感操作二次验证

---

## 八、常见问题FAQ

### Q1: 为什么选择localStorage而不是Vuex/Pinia？

**A**: 本项目采用轻量级的状态管理方案。Vue 3的reactive API已经足够满足当前的认证状态管理需求，无需引入额外的状态管理库。如果未来项目复杂度增加，可以平滑迁移到Pinia。

### Q2: 如何修改JWT Token的过期时间？

**A**: Token的过期时间在后端JWT工具类中配置。前端的响应拦截器会在收到401状态码时自动清除过期Token并跳转登录页。

### Q3: 登录页面可以自定义样式吗？

**A**: 可以。[LoginView.vue](file:///d:/JavaProjects/Linxing/webconsole/src/views/LoginView.vue)使用了scoped样式，您可以自由修改CSS来调整颜色、字体、间距等视觉元素。

### Q4: 如何添加第三方登录（如微信、GitHub）？

**A**: 可以在`api/auth.js`中添加新的API方法（如`loginWithWechat()`），然后在LoginView.vue中添加对应的登录按钮和逻辑。OAuth流程通常涉及后端回调地址配置。

### Q5: 多标签页同时登录不同账号会有冲突吗？

**A**: 会的。因为localStorage在同一域名下是共享的，一个标签页的登录会影响其他标签页。解决方案：
1. 使用sessionStorage替代（关闭标签页即失效）
2. 监听storage事件，检测变化时提示用户
3. 采用Tab ID机制隔离不同标签页

---

## 九、代码规范总结

### 9.1 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 文件名 | kebab-case | `login-view.vue`, `auth.js` |
| Vue组件名 | PascalCase | `LoginView`, `AppLayout` |
| JavaScript函数 | camelCase | `handleLoginSubmit`, `getToken` |
| CSS类名 | kebab-case | `.submit-btn`, `.error-message` |
| 常量 | UPPER_SNAKE_CASE | `LOGIN_API_URL`, `TOKEN_KEY` |
| API接口路径 | lowercase | `/user/login`, `/user/current` |

### 9.2 注释规范

- **JSDoc注释**: 用于导出的函数和对象
- **行内注释**: 解释复杂的业务逻辑
- **TODO标记**: 标记待完成的功能点
- **组件说明**: 每个Vue组件顶部添加功能描述

### 9.3 Git提交规范

建议使用Conventional Commits格式：
```
feat: 添加用户登录功能
fix: 修复Token过期未自动跳转的问题
docs: 更新登录接口文档
style: 调整登录页面样式
refactor: 重构认证状态管理模块
```

---

## 十、联系方式与技术支持

如有问题或建议，请通过以下方式联系：

- 📧 技术文档：查看本项目README.md
- 💬 Issue反馈：在Git仓库提交Issue
- 📚 相关资料：
  - [Vue 3官方文档](https://cn.vuejs.org/)
  - [Vue Router文档](https://router.vuejs.org/zh/)
  - [Axios文档](https://axios-http.com/zh/docs/intro)
  - [JWT简介](https://jwt.io/introduction)

---

**文档版本**: v1.0  
**最后更新**: 2026-04-26  
**作者**: AI Assistant  
**适用范围**: Personal Note RAG 项目第一阶段（前端登录功能实现）

---

> 🎉 **恭喜！您已完成登录功能的前端实现工作！**
>
> 下一步请继续推进后端JWT工具类的开发和前后端联调工作。
