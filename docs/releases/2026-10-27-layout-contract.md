# build73 布局约束复核（2026-10-27）

比较基线是已审候选导入提交e53a2d01，保留其原布局和Owner既有改动；不是拿更早PR页面作为设计目标。

- 对所有修改的Screen/组件逐文件比较既有Row/Column结构、padding/height/width/size/weight、fontSize/fontWeight、间距及既有颜色参数；同文件仅移动或数据取值改写不计作新增设计。最后净差候选为0。
- 复核中撤回了DriveDetail/DriveCurves的新增错误面板。原页面结构保留；详情沿原Snackbar、曲线通过Toast反馈失败，不新增参数区或布局间距。
- 本轮屏幕修改仅用于取值、formatter、nullable/已采样与估算标识、路由字段、真实时间轴/断档及真实路线数据；没有改miniprogram。
- 数字/未知占位/覆盖文案、真实曲线形状及合法数据可用性会随正确输入变化。源码参数复核不等于所有语言/字号的设备像素验收，实际手机/提供方验收另列。
