"""Write release notes using the APK that is about to be published."""
import glob
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path


def parse_badging_version(badging: str) -> str | None:
    """versionName from an `aapt2 dump badging` report, if present."""
    match = re.search(r"^package:.*versionName='([^']+)'", badging, re.M)
    return match.group(1) if match else None


def apk_version(apk: Path) -> str | None:
    """The APK's own versionName, read via aapt2 from the local SDK.

    The notes must describe the artifact that ships, not the tag that triggered
    the build: the two drifted apart whenever the build carried a fixed
    versionName. Falls back to None when no aapt2 is available.
    """
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") \
        or "/usr/local/lib/android/sdk"
    for aapt2 in sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2*"))):
        try:
            badging = subprocess.check_output(
                [aapt2, "dump", "badging", str(apk)], text=True, stderr=subprocess.DEVNULL)
        except (OSError, subprocess.CalledProcessError):
            continue
        version = parse_badging_version(badging)
        if version:
            return version
    return None


def main():
    apk, output = map(Path, sys.argv[1:3])
    changes = release_changes(output.read_text(encoding="utf-8"))
    version = apk_version(apk) or sys.argv[3].removeprefix("v")
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

对应 commit `{commit}`。

{changes}

"""
    output.write_text(notes, encoding="utf-8")


def release_changes(notes: str) -> str:
    """Keep the reviewed changelog from the tagged source in both release pages."""
    _, marker, changes = notes.partition("## 这个版本包含")
    if not marker or not changes.strip():
        raise ValueError("RELEASE_NOTES.md must contain a nonempty changelog")
    return changes.strip()


if __name__ == "__main__":
    main()
