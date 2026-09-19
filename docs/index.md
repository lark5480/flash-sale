---
layout: home

hero:
  name: Flash Sale
  text: 秒杀系统
  tagline: Spring Cloud 微服务 · Redis Lua 原子扣库存 · RocketMQ 异步削峰 · Sentinel 熔断降级 · Prometheus/Grafana 监控
  actions:
    - theme: brand
      text: 快速开始
      link: /deployment/local
    - theme: alt
      text: 架构总览
      link: /architecture/overview
    - theme: alt
      text: GitHub
      link: https://github.com/lark5480/flash-sale

features:
  - title: 架构
    details: 系统总览、秒杀下单核心链路、数据设计、安全认证、可观测性、定时任务与消费者隔离。
    link: /architecture/overview
  - title: 部署
    details: 中间件容器化 + 宿主机后端的本地开发部署、常见问题排查、全量容器化（已知不完整）。
    link: /deployment/local
  - title: 开发
    details: 模块依赖与包结构、API 接口文档、统一返回与枚举、Redis Lua、RocketMQ、前端、代码规范、测试。
    link: /development/structure
  - title: 知识笔记
    details: 基于实际代码整理的缓存、消息、分布式锁、限流、认证、压测等核心知识点与面试 Q&A。
    link: /notes/interview-qa
---
