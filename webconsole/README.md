# webconsole

Linxing 前端 —— Vue 3 + Element Plus 的 Agent 驱动学习平台前端。

## Project setup
```
yarn install
```

### Compiles and hot-reloads for development
```
yarn serve
```
开发服务运行在 `http://localhost:3000`，代理将 `/api/*` 转发到后端 `:8080` 并剥离 `/api` 前缀。

### Compiles and minifies for production
```
yarn build
```

### Lints and fixes files
```
yarn lint
```

### Customize configuration
See [Configuration Reference](https://cli.vuejs.org/config/).

## 关键约束

- 使用 `yarn`，不要用 `npm`（锁文件为 `yarn.lock`）
- 所有前端调用统一加 `/api` 前缀（如 `/api/agent/chat`），由 `vue.config.js` 代理剥离后转发到后端
