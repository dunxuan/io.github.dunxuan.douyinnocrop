# 抖音不裁剪 (DouYin NoCrop)

[![release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/io.github.dunxuan.douyinnocrop)](https://github.com/Xposed-Modules-Repo/io.github.dunxuan.douyinnocrop/releases)
[![module](https://img.shields.io/badge/LSPosed-模块仓库-blue)](https://modules.lsposed.org/module/io.github.dunxuan.douyinnocrop)
[![api](https://img.shields.io/badge/Xposed%20API-102-green)](https://github.com/libxposed/api)

一个 LSPosed 模块:让抖音视频按**原始宽高比完整显示**,不再被左右裁剪。

A modern LSPosed module (Xposed API **102**) that makes Douyin render videos at their
original aspect ratio — no more left/right cropping.

## 下载 / Download

- **LSPosed 模块仓库**:<https://modules.lsposed.org/module/io.github.dunxuan.douyinnocrop>
- **安装包 Releases**(Xposed-Modules-Repo 镜像):<https://github.com/Xposed-Modules-Repo/io.github.dunxuan.douyinnocrop/releases>
- **源码**:<https://github.com/dunxuan/io.github.dunxuan.douyinnocrop>

## 版本 / Version

当前版本 **v1.1.0**(versionCode 8,2026-09-28 发布)。相对 v1.0 的要点:

- 适配范围严格限定三处全屏界面:推荐主 Feed、搜索打开的视频详情播放器、清屏全屏
- 评论区五重门控(视图树 / Activity / params Fragment / 可见评论 Fragment 子树 / 全屏级独立窗口),评论动图与图片完全保持抖音原生行为
- 图文详情浏览(捏合缩放层)、搜索列表卡片不触碰
- 容器尺寸持久监听:进出全屏、面板开合自动重算/还原
- 静态图文改 Fresco FIT_CENTER,不破坏捏合缩放

## 效果对比

![修改前 cover 填满裁左右 · 修改后 内接适配零裁剪](https://raw.githubusercontent.com/dunxuan/io.github.dunxuan.douyinnocrop/master/assets/before-after.svg)

*左:抖音原生 cover 放大填满 → 视频比屏幕宽时左右被裁;右:本模块把渲染 View 收成视频宽高比的内接矩形 → 完整显示,多余部分留背景。*

## 原理 / How it works

抖音播放引擎按 cover(放大填满)模式把视频渲染进与容器同尺寸的 surface,当视频比容器
更"宽"时,左右两侧内容会被裁掉。

本模块 hook 抖音换视频的时机(`VideoItemParams.getAweme()`),读取内容宽高比,把视频
渲染 View 的 `LayoutParams` 收缩为"内容宽高比在容器内的最大内接矩形"(保留原有居中
gravity)。这样 surface 宽高比 == 视频宽高比,引擎的 cover 渲染在数学上等价于 fit,
裁剪自然消失,容器多余部分显示背景。容器尺寸变化时(进出全屏、面板开合)由持久
layout 监听驱动重算/还原,对抗抖音 `FeedAllScreenHelper` 等对布局的覆写,幂等写入。

Douyin renders video frames with a "cover" (fill-and-crop) strategy. This module shrinks
the render view to the largest rectangle matching the video's aspect ratio (centered),
so the surface ratio equals the video ratio and nothing gets cropped.

## 功能 / Features

- 视频/动图按原始比例完整显示,左右不再裁剪
- 场所白名单:仅处理主 Feed(`*MainActivity`)、详情播放器(`*DetailActivity`)、清屏
  三处全屏界面;搜索列表、广告、直播等未知上下文自动还原
- 评论区五重门控完全不碰,评论动图/图片保持抖音原生行为
- 宽高比四级回退提取(`width/height` → `t/s` → `aspectRatio` → `imageInfos`),
  每次从 Aweme 对象独立读取、无全局状态,杜绝跨内容陈旧比例污染
- 状态按 View 弱表记账,还原前比对是否仍是我们写入的值;只改 View 布局参数,
  不碰播放器内核
- 全部 Hook 使用 `PROTECTIVE` 异常模式:钩子出错自动降级,不会导致抖音闪退

## 要求 / Requirements

- **LSPosed**(支持 Modern Xposed API 102 的版本)
- **作用域**:`com.ss.android.ugc.aweme`(抖音,安装后在 LSPosed 中勾选)
- Android 9+ (minSdk 28)

## 安装 / Install

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/io.github.dunxuan.douyinnocrop/releases) 下载最新 APK 并安装
2. LSPosed → 模块 → 启用 **抖音不裁剪**
3. 作用域勾选 **抖音**
4. 强行停止并重启抖音

验证日志:`adb logcat -s DouYinNoCrop:D`

## 构建 / Build

```powershell
.\gradlew.bat assembleRelease
# 产物: app\build\outputs\apk\release\app-release.apk
```

- AGP 9.2.1 / Gradle 9.4.1 / compileSdk 37 / minSdk 28 / Java 17
- 依赖仅一行:`compileOnly("io.github.libxposed:api:102.0.0")`(Maven Central)

## 兼容性说明 / Compatibility

Hook 锚点均为未混淆的接口方法与 public 字段(在抖音 40.6.0 上实测):

| 锚点 | 用途 |
|---|---|
| `VideoItemParams#getAweme()` | 换视频时取内容宽高比(绑定即收缩) |
| `VideoItemParams#mAweme` / `#mBaseFeedPlayerView` | 取 Aweme 与播放器 View |
| `Aweme#getVideo()` / `#imageInfos` | 视频/图文宽高比回退提取 |
| `BaseFeedPlayerView#getVideoView()` | 拿真正渲染 View(覆盖 Texture/Surface 两条路径) |
| `KeepSurfaceTextureView#onAttachedToWindow()` | attach 时序补偿(TextureView 路径) |
| `CleanModeViewModel#sA(...)` | 清屏状态进出,驱动全量重算/还原 |

抖音大版本更新后如失效,可用 droidasc 等 dex 分析工具重新验证上述锚点,
只需更新 `NoCropModule.java` 中的类名/方法名常量。

## License

MIT
