# Hermes 安卓客户端

小米 HyperOS 2.0（Android 12+）上的 Hermes Agent 原生客户端，
直接对话你自己部署的 Hermes 网关。

## 功能

| 模块 | 说明 |
|---|---|
| 会话列表 | `session.list`，搜索、按来源标记（桌面/定时/飞书/QQ…）、统计条、`sessions.changed` 事件自动刷新 |
| 对话 | 流式输出（`message.delta`）、Markdown 渲染（代码块带语言标签+复制、表格、标题、列表、引用）、token 与上下文用量条 |
| 执行摘要 | 推理 + 工具调用合并为一行可折叠摘要（`思考 … 工具调用 ×N · 3s`），展开看每次工具的参数/输出/耗时 |
| Fork | `session.branch` 一键分支，保留历史并切到新会话 |
| 新建会话 | `session.create` |
| 模型切换 | `model.options` 拉供应商→模型列表，`/model … --provider …` 切换 |
| 推理等级 | 关闭 / 极简 / 低 / 中 / 高 / 超高 / 最大 / 极致，与桌面端取值完全一致 |
| 附件 | 图片 / PDF / 任意文件，走 `image.attach_bytes`、`pdf.attach`、`file.attach`，20MB 上限 |
| 语音 | 系统语音识别输入 + TTS 朗读单条回复（可开自动朗读） |
| 授权交互 | `approval.request` / `clarify.request` 弹出授权卡片，可允许一次或拒绝 |
| 技能 | `GET /api/skills`，支持查看详情与启停 |
| 工具 | `tools.list` 列出工具集与其中的工具，可展开、可启停 |
| 产物 | `GET /api/files` 浏览后端文件系统 |
| 定时任务 | `GET /api/cron/jobs` |
| 联系人 / 拉群 | 网关上的 bot 与通道列表、已授权/待授权联系人；会话内可用 `/handoff <平台>` 把会话转到群通道 |
| 配置与验证 | 填写后端地址 → 健康检查 → 登录 → 票据 → WebSocket 握手 → `gateway.ping`，逐段展示结果 |

## 设计原则

- 最小设计，无权限申请，无广告；
- 功能克制，仅做Hermes聊天界面，没有设置等功能；
- 边界清晰，仅对Hermes Dashboard做二次开发；


## 首次使用

1. 安装 APK
2. 进「我的」页填写后端地址、用户名、密码
3. 点「测试连接」，看到「连接成功」后即可进「消息」页开始对话

## 构建

工具链已下载到 `.toolchain/`（不纳入版本库）：

```
.toolchain/jdk/jdk-17.0.2
.toolchain/gradle/gradle-8.13
.toolchain/sdk        (platforms/android-35, build-tools/35.0.0, platform-tools)
```

```powershell
$env:JAVA_HOME = "E:\hermes-app\.toolchain\jdk\jdk-17.0.2"
$env:ANDROID_HOME = "E:\hermes-app\.toolchain\sdk"
.\gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`


## 目录

```
app/src/main/java/com/hermes/android/
├─ core/
│  ├─ model/Contracts.kt     数据合约
│  ├─ net/HermesAuth.kt      登录、票据、Cookie
│  ├─ net/JsonRpcClient.kt   WebSocket JSON-RPC + 流式事件
│  ├─ net/HermesRest.kt      REST
│  └─ HermesRepository.kt    统一门面
├─ store/SettingsStore.kt    DataStore 配置
└─ ui/                       Compose 界面（会话/聊天/更多/设置）
```
