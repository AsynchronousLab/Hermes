# Hermes Android debug 构建

Hermes 后端的 Android 客户端 debug 构建，附件 `Hermes.apk` 直接下载安装。

Release 页面的说明就是这份文件在 tag 所指提交上的内容：`.cnb.yml` 通过
`git:release` 的 `descriptionFromFile` 引用它，所以改说明 = 改这个文件 +
提交 + 打 tag，说明跟着代码一起评审和版本管理，不再手改页面。

## 安装

- 最低系统版本：**Android 12（API 31）**，targetSdk 35
- 包名 `com.hermes.android.debug`
- 装完进「我的」页填后端地址、用户名、密码，连上就能用

## 校验

这里不硬编码 SHA256——流水线每次构建都会生成新的 debug keystore，产物
指纹必然变化，写死只会过期误导。两个权威来源：

- 构建日志「Test and build the debug APK」步骤末尾打印的 `sha256sum`
- CNB Release 页面附件旁显示的实际 digest

下载后可以用 `Get-FileHash Hermes.apk -Algorithm SHA256`（或
`sha256sum Hermes.apk`）核对。

## 这是 debug 包

用 Android 默认 debug keystore 签名，包名带 `.debug` 后缀，适合自己和
同事测试装。**不要**拿它上应用市场 —— 要正式发布得自己配 release 签名。

APK 里**不含任何凭据**，后端地址、用户名、密码都是安装后你自己填，密码
用 Keystore 加密后存本地。

## 这个版本包含

<!-- 发版时更新这一节并随 tag 一起提交；上一版（v0.1.0-debug）的功能线见
     git tag 历史。 -->

- 群聊：room transcript、composer、`groups.*` 接口；`groups.send` 结果未知（超时/断连）时保留气泡，可点按用同一 `event_id` 幂等重试，不会重复投递
- 群聊可靠性：能力探测失败不再永久判"不支持"、房间状态随轮询刷新、群列表跟随分页游标、拒绝/失败后草稿自动回填
- 聊天可靠性：断线恢复后重试开会话、绑定前事件不丢、附件部分上传失败可整任务重试、turn.error 清理流式气泡、新会话不再沿用旧 profile
- 切换账号/后端后各页面缓存自动失效
- Markdown 链接可点击打开；语音识别语言参数与包可见性声明修正
- IM 风格统一会话列表、消息跳转按钮、逐条回复 fork、会话删除
- 重连恢复（cookie 失效自动重新登录、补历史）
- 消息按 `actor.kind` 分类，不再依赖硬编码 profile
- 后端密码加密保存
