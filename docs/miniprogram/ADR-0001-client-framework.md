# ADR-0001：微信小程序客户端采用 Taro React TypeScript

状态：M1 实施基线（不代表平台发布通过）

日期：2026-09-11

## 决策

新增 `miniprogram/`，采用 Taro 4.2.1、React 18.3.1、TypeScript 5.7.3 和 Webpack 5，目标平台为微信小程序。

## 依据

- 现有 Web 客户端和部分共享类型已经使用 React/TypeScript，可复用经过审查的业务语义和展示模型。
- Taro 4.2.1 模板明确支持 React 18；Webpack 5 runner 在本地成功生成微信小程序开发态产物。
- 纯函数可以在 Node/Vitest 中独立验证，降低对微信开发者工具和 AppID 的依赖。

## 边界与替代方案

- Compose、Room、WorkManager、浏览器 DOM、Leaflet 和 Recharts 不直接搬入小程序；页面、存储、地图和图表使用微信平台适配层重写。
- 如果 M0 真机验证发现 Taro 的 OAuth 承载、地图或 Canvas 兼容性不满足要求，可评估原生 TypeScript/WXML/WXSS；在证据出现前不切换框架。
- `project.config.json` 保留空 AppID；不得把 `touristappid` 或个人测试配置当成发布身份。

## 结果

- `npm run typecheck`：PASS。
- `npm test`：3 个测试文件、6 个纯逻辑测试 PASS。
- `npm run build:weapp`：PASS，生成开发态 `dist/`；未做微信后台、安卓微信、苹果微信或发布态域名验收。
