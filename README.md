# DouyinTV 2.0 — GeckoView 版 / GeckoView edition

在 Android TV / 投影（极米等）上看抖音网页版的客户端。
**2.0 把内核从系统 WebView 换成了 GeckoView（Firefox 引擎随 APK 自带）**，所以老设备（如极米 Z8X：Android 8.0 + Chromium 62）也能跑，不用升级系统任何东西。

A Douyin (抖音) client for Android TV / projectors. v2.0 bundles its own browser engine (GeckoView), so the device's ancient System WebView no longer matters.

## 要求 / Requirements
- Android 8.0 (API 26) 或以上 — GeckoView 的最低要求
- 建议 ≥ 1.5 GB 内存
- 第一次打开需要登录（手机扫码）；可能出现滑块验证，用光标模式或 USB 鼠标拖一次即可，之后 cookie 持久化

## 遥控器 / Remote

| 键 | 浏览模式 | 光标模式 |
|---|---|---|
| ↑ / ↓ | 上一个 / 下一个视频 | 移动光标 |
| ← / → | 快退 / 快进 5 秒 | 移动光标 |
| 确认 | 播放 / 暂停 | 点击；**长按** = 开始拖动，再按一下 = 放开 |
| 菜单 | 进入光标模式 | 退出光标模式 |
| 返回 | 网页后退 / 退到后台 | 退出光标模式 |

光标模式就是给没有鼠标的遥控器用的：点登录按钮、拖滑块验证码、关弹窗。

## 编译 / Build

**方式 A：GitHub Actions（推荐，不用装 Android Studio）**
1. Fork / push 这个仓库到你自己的 GitHub
2. 每次 push 到 `main`，Actions 自动编译并发到 Releases
3. 下载 `DouyinTV-x.y.z-arm64-v8a.apk`；装不上再试 `armeabi-v7a`

**方式 B：本地**
```bash
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a}-release.apk
```
需要 JDK 17 和 Android SDK（compileSdk 36）。首次编译会从 maven.mozilla.org 下载 ~100 MB 的 GeckoView。

## 安装 / Install
```bash
adb connect <投影IP>:5555
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```
或者 U 盘拷过去用文件管理器装。

## 结构 / Layout
- `app/src/main/java/com/douyin/tv/MainActivity.kt` — GeckoView 会话、遥控器按键、光标模式
- `app/src/main/java/com/douyin/tv/CursorOverlay.kt` — 屏幕光标绘制
- `app/src/main/assets/douyintv/` — 内置扩展：`content.js`（接收按键命令、切视频、自动连播、离屏视频卸载）、`tv.css`（隐藏侧栏/评论/下载引导，全屏播放）
- `app/src/main/assets/geckoview-config.yaml` — 低内存 Gecko 偏好（单进程、小缓存）

## 已知问题 / Known issues
- 抖音网页改版可能让 `tv.css` 的选择器失效（只影响美观）或让 `content.js` 的切视频快捷键失效（有滚动兜底）
- 1080p 在很弱的 SoC 上可能掉帧；抖音网页端清晰度菜单在光标模式下可以手动调
- 仅供学习交流使用。抖音及相关商标归北京字节跳动科技有限公司所有。
