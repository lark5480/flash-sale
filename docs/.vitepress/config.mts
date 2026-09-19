import { defineConfig } from 'vitepress'

// 文档站配置：VitePress 以 docs/ 为 srcDir（脚本 `vitepress dev docs`）。
// base 用于 GitHub Pages 项目站（/<repo>/）；若部署到根域名改为 '/'。
export default defineConfig({
  lang: 'zh-CN',
  base: '/flash-sale/',
  title: 'Flash Sale 秒杀系统',
  description:
    '基于 Spring Cloud 微服务架构的高并发秒杀系统 — Redis Lua 原子扣库存 + RocketMQ 异步削峰 + Sentinel 熔断降级 + Prometheus/Grafana 监控',
  lastUpdated: true,
  cleanUrls: true,

  srcExclude: ['**/README.md'],

  // 本地服务地址（localhost:xxxx）是有意的说明性链接，非站点内死链。
  ignoreDeadLinks: [/^https?:\/\/localhost/],

  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '架构', link: '/architecture/overview' },
      { text: '部署', link: '/deployment/local' },
      { text: '开发', link: '/development/structure' },
      { text: '知识笔记', link: '/notes/interview-qa' },
    ],

    sidebar: {
      '/architecture/': [
        {
          text: '架构',
          items: [
            { text: '系统架构总览', link: '/architecture/overview' },
            { text: '秒杀下单核心链路', link: '/architecture/flash-sale-flow' },
            { text: '数据设计（DB + Redis Key）', link: '/architecture/data-design' },
            { text: '安全与认证', link: '/architecture/security' },
            { text: '可观测性（监控栈）', link: '/architecture/observability' },
            { text: '定时任务与消费者隔离', link: '/architecture/scheduling-and-isolation' },
          ],
        },
      ],
      '/deployment/': [
        {
          text: '部署',
          items: [
            { text: '本地开发部署', link: '/deployment/local' },
            { text: '常见问题排查', link: '/deployment/troubleshooting' },
            { text: '全量容器化部署（已知不完整）', link: '/deployment/full-container' },
          ],
        },
      ],
      '/development/': [
        {
          text: '开发',
          items: [
            { text: '模块依赖与包结构', link: '/development/structure' },
            { text: 'API 接口文档', link: '/development/api-reference' },
            { text: '统一返回与枚举', link: '/development/response-and-enums' },
            { text: 'Redis Lua 脚本', link: '/development/redis-lua' },
            { text: 'RocketMQ 消息机制', link: '/development/rocketmq' },
            { text: '前端开发', link: '/development/frontend' },
            { text: '代码规范', link: '/development/code-standards' },
            { text: '测试', link: '/development/testing' },
          ],
        },
      ],
      '/notes/': [
        {
          text: '知识笔记',
          items: [
            { text: '知识笔记与面试 Q&A', link: '/notes/interview-qa' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/lark5480/flash-sale' },
    ],

    search: { provider: 'local' },
    outline: { label: '本页目录', level: [2, 3] },
    editLink: {
      pattern: 'https://github.com/lark5480/flash-sale/edit/master/docs/:path',
      text: '在 GitHub 上编辑此页',
    },
    footer: {
      message: '基于 MIT 许可发布',
      copyright: 'Flash Sale 秒杀系统',
    },
  },
})
