# M3.1 依赖审计与处置记录

日期：2026-09-15
锁文件 SHA-256：`1A118CDEDED3B02B563A3011956E48D009AD86FC814B05E9B4A2EAFBD835693C`

## 审计结果

在 `miniprogram` 目录在线执行：

- `npm audit --omit=dev --json`：退出码 1，生产解析结果 `critical=3, high=0, moderate=8, low=0, total=11`。
- `npm audit --json`：退出码 1，完整树 `critical=5, high=17, moderate=25, low=0, total=47`。
- 原始 JSON 保留在 `E:\temp\matelink-m3-evidence-20260915\npm-audit-prod-final2.json` 与 `npm-audit-full-final.json`，没有把其内容写入应用包。

生产树中可见的直接/传递路径包括：`@tarojs/components` 与 `@tarojs/taro` 的 critical advisory，`swiper` 的 prototype pollution，`webpack`/`webpack-dev-server`/`esbuild`/`express`/`qs`/`sockjs`/`uuid` 的 moderate advisory。已将直接 `@babel/core` 升至 `7.29.7` 并重新验证，原 low advisory 已消除；`npm explain` 已确认 Taro 组件链直接引入 `swiper`，完整路径仍以审计 JSON 为准。

## 处置决定

本轮没有使用 `--force`、修改 audit level、删除锁文件或盲目升级 Taro 主版本。小程序源码只运行打包后的微信业务代码；Webpack、dev server 和部分 Taro 依赖主要属于构建/开发链，但当前锁树仍未得到上游兼容修复证明，因此不能把生产树 audit 变绿，也不能把 `READY_FOR_RELEASE` 标 PASS。

后续处置必须逐项核对 Taro 官方兼容版本、上游 advisory 的受影响代码路径和构建产物可达性；每次升级都要在干净目录重新 `npm ci`、typecheck、Vitest、微信构建、敏感信息扫描，并复跑安卓微信和 iOS 微信平台门禁。当前依赖审计状态是 `BLOCKED_FOR_RELEASE`，不阻止已完成的离线 M3.1 源码验证。

曾在隔离锁树尝试将 `webpack` 升至 `5.111.0`，npm 因 `@tarojs/taro-loader@4.2.1` 和 `@tarojs/webpack5-runner@4.2.1` 固定 peer `webpack@5.91.0` 返回 `ERESOLVE`。没有使用 `--force` 或 `--legacy-peer-deps`，也没有把不兼容的锁树写回项目；该结果是当前 Taro 基线无法安全消除 webpack advisory 的证据。
