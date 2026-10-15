# 俄罗斯方块（Android / Kotlin）

纯原生实现：一个 Activity + 一个自绘 View，无任何第三方库依赖。

## 运行

1. Android Studio（Ladybug 2024.2 或更新）→ **Open** → 选择本目录
2. 等 Gradle 同步完成（首次会下载 Gradle 8.9 和 Android SDK 35）
3. 连接手机或启动模拟器，点 ▶ Run

命令行：`./gradlew assembleDebug`，APK 在 `app/build/outputs/apk/debug/`。
单元测试：`./gradlew test`。

**不想本地编译**：每次推送到 main，GitHub Actions 会自动跑测试并打包。
到仓库的 Actions 页 → 点最新一次运行 → 底部 Artifacts 里下载 `tetris-debug-apk`，
解压后把 APK 装到手机上（需允许"安装未知来源应用"）。

## 玩法

| 操作 | 按键 | 棋盘手势 | 实体键盘 |
|---|---|---|---|
| 左/右移 | ◀ ▶（长按连发） | 左右拖动 | ← → / A D |
| 旋转 | 旋转 | 点按 | ↑ / W / X（Z 逆时针） |
| 软降 | ▼（长按连发） | — | ↓ / S |
| 落底 | 落底 | 下滑 | 空格 |
| 暂存 | 暂存 | 上滑 | C |
| 暂停 | 暂停 / 继续 | 暂停时点按继续 | P |

## 规则

- 10×20 棋盘，7 种方块，7-bag 随机（每 7 个必含全部七种）
- 落点幽灵预览、暂存（每块只能暂存一次）、预览后续 3 块
- 落地后 0.5 秒锁定延迟，期间移动/旋转可重置（最多 15 次）
- 旋转带简易踢墙
- 计分：消 1/2/3/4 行 = 100/300/500/800 × 等级；软降每格 +1，落底每格 +2
- 每消 10 行升一级，下落间隔 = 1000ms × 0.85^(等级-1)，最快 50ms
- 最高分保存在本地；切到后台自动暂停

## 结构

```
app/src/main/java/com/an/tetris/
  TetrisGame.kt    游戏引擎（纯 Kotlin，不依赖 Android）
  TetrisView.kt    绘制、触控、按键、游戏循环
  MainActivity.kt  入口，处理全面屏 insets
app/src/test/.../TetrisGameTest.kt  引擎单元测试
```

minSdk 24（Android 7.0），targetSdk 35。
