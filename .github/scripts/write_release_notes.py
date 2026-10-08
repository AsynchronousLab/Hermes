"""Write release notes using the APK that is about to be published."""
import hashlib
import subprocess
import sys
from pathlib import Path


def main():
    apk, output = map(Path, sys.argv[1:3])
    version = sys.argv[3].removeprefix("v")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    size = apk.stat().st_size
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest().upper()
    notes = f"""# Hermes Android {version}

Hermes 后端的 Android 客户端 debug 构建，附件 `Hermes.apk` 直接下载安装。

## 安装

- 最低系统版本：**Android 12（API 31）**，targetSdk 35
- 包名 `com.hermes.android.debug`，versionName `{version}`
- 装完进「更多」→「后端设置」，填写后端地址、用户名和密码，测试连接成功后即可使用

## 校验

```
文件大小  {size} 字节（{size / (1024 * 1024):.2f} MiB）
SHA256    {digest}
```

下载后可以用 `Get-FileHash Hermes.apk -Algorithm SHA256` 核对。
GitHub 和 CNB 提供的是同一份 APK，校验值相同。

## 这是 debug 包

用 Android 默认 debug keystore 签名，包名带 `.debug` 后缀，适合自己和同事测试安装。
**不要**拿它上应用市场——正式发布需要自行配置 release 签名。

APK 里**不含预置的后端登录凭据**。后端地址、用户名和密码均在安装后填写，
密码使用 Android Keystore 中的密钥加密后保存在本地。

## 这个版本包含

对应 commit `{commit}`，主要功能：

- 群聊：room transcript、composer、`groups.*` 接口
- IM 风格统一会话列表、消息跳转按钮
- 逐条回复 fork
- 重连恢复：Cookie 失效自动重新登录并补齐历史
- 会话删除
- 消息按 `actor.kind` 分类，不再依赖硬编码 profile
- 后端密码加密保存

"""
    output.write_text(notes, encoding="utf-8")


if __name__ == "__main__":
    main()
