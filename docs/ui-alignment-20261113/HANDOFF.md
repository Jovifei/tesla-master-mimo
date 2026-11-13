# Web UI 对齐阶段 · 固定接收

本文件仅追加交接说明，不改动经过测试的Web产品源码。

- 分支：codex/ui-product-web-20261109
- 测试源码：4b622f98eb5a5c7a6d715155146add9ea6fb296c
- Tree：917e63b0abdb66a8d05f0c8d9d90f8793f908804
- Parent：fb53e77b79d9e3401899bfd5b37f53beb9be857a
- web_matelink目录树：b750814cbcc3530be27fdfd9743e66683f4f6e2d
- Draft PR：#51（owned固定审查基线，不合并main）
- CI：run43375124759，verify job131719748751，实际检出上述源码。
- TypeScript/Vite实际产品构建、59项Node测试、lint零错误通过。仍有14条lint warning与既有bundle大小提示，不宣称零警告或性能认证。
- 25项真实build浏览器检查全过，320/393/1440宽度；C/A持久化、横/纵日期手势、七日面板及驻车、充电格零/缺测、减少动画、登录/退出确认、手机导航展开与关闭均覆盖。
- artifact14013758153，ZIP SHA256 20b146b26cb449c76e7156fc8c35667960fc247c95569fc511bb370d6e937907。

[完整三端交接与原本地Codex Prompt](https://github.com/Jovifei/tesla-master-mimo/blob/0f0c532a7ff77b12f296f36b7a8c4ae14a761ac9/docs/ui-alignment-20261113/HANDOFF.md)

该文档含计划对照、准确CI作业、验收/安装/发布/回滚矩阵与充电缺口核查。Android源码95bda618c7d9d09ff50985c9befe8558fb24a0e7；Mini源码6debf2527d6ca7c39d7257909eed4f06d8e481ba；三个工程分别接收，不能互相覆盖。

使用真实React/HTML入口，不是单文件演示页。默认中性白、可换奶白，手机紧凑导航、桌面充电日历+记录双栏；完整参数表折叠保留。旧请求、令牌刷新和退出登录具备会话代际/取消保护。原能耗、费用、地点含义不改。

截图来自实际构建+合成API，非真实车辆或Android/Mini真机。测试没有外部流量、真实登录或车控。未部署线上、未合并main、未改Nginx/生产数据库/历史。10月27日—11月12日充电缺口需要另行核验当前同源只读证据，UI与CI通过不是自然数据恢复证明。本地只独立验证、构建及合并回传，不承担业务实现。
