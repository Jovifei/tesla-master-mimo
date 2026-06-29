# Tesla MateLink MIMO

> 🚗 多平台 Tesla 车辆控制客户端 - MIMO 版本

## 项目简介

这是一个 **全栈跨平台** Tesla MateLink 项目，提供 Android、iOS 和 Web 三个客户端实现，用于远程控制和监控 Tesla 车辆。

本仓库专注于 **MIMO (Multiple Input Multiple Output)** 架构版本，提供更好的多设备协同体验。

## 项目结构

```
app_mimo/
├── android/          # Android 客户端 (Kotlin + Jetpack Compose)
├── ios/              # iOS 客户端 (Swift)
├── shared/           # 共享类型定义
└── web_matelink/     # Web 客户端 (React 19 + TypeScript + Vite)
```

## 技术栈

### Android
- **Kotlin** 1.9+ with **Jetpack Compose**
- **Hilt** 依赖注入
- **Room** 本地数据库
- **Retrofit** + **OkHttp** 网络通信
- **Moshi** JSON 解析
- **WorkManager** 后台任务
- **AndroidX DataStore** 数据存储
- **MPAndroidChart** 数据可视化
- **AMap** 高德地图集成

### iOS
- **Swift** with iOS 16.0+ deployment target
- **AMap3DMap** 高德地图 SDK
- **CocoaPods** 依赖管理

### Web
- **React 19** + **TypeScript 6**
- **Vite 8** 构建工具
- **Tailwind CSS 4.3**
- **React Router DOM 7**
- **Zustand** 状态管理
- **Recharts** 图表
- **Leaflet** + **React Leaflet** 地图
- **Oxlint** 代码检查

## 功能特性

✅ 车辆状态监控  
✅ 电池健康数据分析  
✅ 胎压监测  
✅ 能耗统计  
✅ 地图定位与导航  
✅ 远程控制  
✅ 多平台数据同步  

## 构建指南

### Android
```bash
cd android
./gradlew assembleDebug
# or open in Android Studio
```

### iOS
```bash
cd ios
pod install
open MateLink.xcworkspace
```

### Web
```bash
cd web_matelink
npm install
npm run dev
# npm run build for production
```

##  Requirements

- Android: compileSdk 35, minSdk 26, Java 17
- iOS: 16.0+
- Node.js: 18+ for Web version

## 相关项目

该项目是 [tesla-master](https://github.com/Jovifei/tesla-master) 项目的 MIMO 版本分离。

- [tesla-master-mimo](https://github.com/Jovifei/tesla-master-mimo) - 当前仓库 (MIMO 版本)
- [tesla-master-glm](https://github.com/Jovifei/tesla-master-glm) - GLM 版本

## 许可证

MIT License

## 作者

JoviF
