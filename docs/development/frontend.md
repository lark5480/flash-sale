# 前端开发

## 1. 技术栈

| 项 | 技术 |
|----|------|
| 框架 | Vue 3 |
| 构建工具 | Vite |
| HTTP 客户端 | Axios |
| 路由 | Vue Router |
| UI 组件（管理端） | Element Plus |

## 2. 代理配置

**用户前端（flash-frontend，端口 5173）：**

```javascript
// vite.config.js
proxy: {
  '/api':  { target: 'http://localhost:8080', changeOrigin: true },
  '/admin': { target: 'http://localhost:8080', changeOrigin: true }
}
```

**管理前端（flash-admin-frontend，端口 5174）：**

```javascript
// vite.config.js
proxy: {
  '/admin': { target: 'http://localhost:8080', changeOrigin: true }
}
```

所有前端请求先到达 Vite 开发服务器，再由代理转发到 Gateway（8080），Gateway 根据路径转发到对应的后端服务。

## 3. 认证流程

```
1. 用户登录 → 获取 accessToken + refreshToken
2. 将 accessToken 存入 localStorage（key: 'accessToken'）
3. Axios 请求拦截器自动添加 Authorization: Bearer {token} 请求头
4. 收到 401 响应时，清除 Token 并跳转到登录页
```

**用户前端 Token Key：** `accessToken`、`refreshToken`
**管理前端 Token Key：** `adminToken`

## 4. 页面路由

**用户前端（flash-frontend）：**

| Path | 组件 | 说明 | 需要认证 |
|------|------|------|----------|
| `/` | Home.vue | 首页（商品列表、秒杀活动列表） | 是 |
| `/login` | Login.vue | 登录页 | 否 |
| `/register` | Register.vue | 注册页 | 否 |
| `/flash-sale/:id` | FlashSaleDetail.vue | 秒杀活动详情与下单 | 是 |
| `/orders` | OrderList.vue | 我的订单列表 | 是 |

**管理前端（flash-admin-frontend）：**

| Path | 组件 | 说明 | 需要认证 |
|------|------|------|----------|
| `/login` | Login.vue | 管理员登录页 | 否 |
| `/` | Dashboard.vue | 仪表盘（布局容器，默认重定向到 /items） | 是 |
| `/items` | ItemList.vue | 商品管理 | 是 |
| `/flash-sales` | FlashSaleList.vue | 秒杀活动管理 | 是 |
| `/orders` | OrderList.vue | 订单管理 | 是 |
| `/users` | UserList.vue | 用户管理 | 是 |

管理前端使用嵌套路由，`Dashboard.vue` 作为父级布局容器，包含侧边栏导航和 `<router-view>` 插槽。
