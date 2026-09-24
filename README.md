# 菲林档案

给胶片摄影爱好者的**本地归档 App**。把冲洗回来的底片拼成长条，像在观片台上一样一段段看过去，顺手记下它是在哪儿拍的。

纯个人档案工具 —— **没有社交，不做滤镜调色，不联网**。

<p align="center">
  <img src="docs/01-film-dark.jpg" width="30%">
  <img src="docs/02-film-paper.jpg" width="30%">
  <img src="docs/03-roll.jpg" width="30%">
</p>
<p align="center">
  <img src="docs/04-gear.jpg" width="30%">
  <img src="docs/06-presets.jpg" width="30%">
  <img src="docs/07-illus.jpg" width="30%">
</p>

---

## 功能

**底片** — 按拍摄时间或按胶卷分类浏览，支持自定义文件夹把多卷归到一起。导入的照片拼接成一整张长条底片，可以横向拖动；长条能切分成多个片段，每段独立标记拍摄地点。片边常驻胶卷型号，每格画面下方是底片编号（`1 / 1A / 2 / 2A…`）。

**摄影日历** — 月历索引胶卷档案，全年柱状图统计每月拍摄量。

**相机仓库** — 登记机身与镜头。三条路径：直接拍照后**点一下主体抠掉背景**（端上跑的 MediaPipe 模型，不联网也不上传）、从相册选一张、或者**先挑一个线稿占位**，之后随时换成真图。线稿有单反 / 旁轴 / 双反 / 傻瓜机 / 镜头五种。

<p align="center"><img src="docs/05-cutout.jpg" width="34%"></p>

**胶卷仓库** — 库存计数，按过期时间由近到远排序，临近过期优先展示。新增时可以从 **29 款热门胶卷预设**里点选，型号、规格、ISO 一次填好；同型号同规格再次入库会累加数量而不是新增一条。

**导出** — 整卷总览大图，或单张照片加胶片风格水印边框（含拍摄地点 / 拍摄时间 / 相机型号），直接写进系统相册。

**两套主题** — 暗房（深色底 + 琥珀橙）和米色（接触印相纸的浅色底）。底片本身在两套主题下都是深色，因为那就是底片的颜色。

### 数据字段

导入来源（扫描仪 / 相机翻拍，并可另填具体型号）、冲洗店、胶卷型号、相机型号、冲洗日期、胶卷规格（135 / 120）。**胶卷型号与相机型号都是自由文本，预设只作快捷方式，不受任何白名单限制。**

---

## 技术架构

界面是 **HTML/CSS/JS 跑在 WebView 里**，原生只负责界面碰不到的部分。

```
prototype/film-archive-app.html     ← 全部界面代码的唯一来源
        │  build_assets.py（剥离展示外壳、内嵌字体、注入 app 模式 CSS）
        ▼
app/src/main/assets/index.html      ← 生成物，不要直接改
        │
        ▼
   WebView（https://appassets.androidplatform.net）
        │  @JavascriptInterface
        ▼
   Bridge / DocStore / PhotoStore / Importer / Capture / Exporter / CutoutEngine
```

为什么这么做：这套设计（长条底片、齿孔、片边代号、分段）本质上是**排版问题**，用 CSS 表达比用原生 View 表达短一个数量级。代价是桥接层要自己写，但换来的是一份代码同时跑在浏览器和手机上 —— `prototype/film-archive-app.html` 直接用 Chrome 打开就是**功能完整**的原型（有持久化、能导入照片、能导出），不是空壳演示。

几个值得一提的设计决定：

- **页面挂在真实 https 源上**（`WebViewAssetLoader`），不是 `file://`。这样照片能用普通 `<img>` 原生加载、WebView 自己缓存，而且 canvas 合成导出时不会被跨源污染（`file://` 加载的图画进 canvas 会 taint，`toDataURL()` 直接抛异常）。
- **自写的 MIME 表**。`androidx.webkit` 自带的 `AssetsPathHandler` 靠 `URLConnection.guessContentTypeFromName` 猜类型，那张表里没有 `.woff2`，会把字体当 `text/plain` 发 —— 而字体加载失败是**静默**的，页面只是"看起来有点不一样"。
- **持久化走原生文件**，`doc.json` 防抖 + 原子写（`tmp` → `fsync` → `rename`），`onPause` 同步 flush。`localStorage` 只作崩溃恢复镜像。
- **MediaPipe 带进来的权限被摘掉了**。`tasks-vision` → `tasks-core` → `datatransport` 会在清单合并时带入 `INTERNET`、`ACCESS_NETWORK_STATE` 和一个 AlarmManager 定时上传调度器。清单里用 `tools:node="remove"` 全部移除 —— 一个宣称"不经过任何服务器"的 App 不能带这些。

---

## 构建

需要 Windows + PowerShell。工具链是**便携的**，装在项目目录里，不写系统目录、不需要管理员权限。

```powershell
# 1. 装工具链（一次性，约 1.5 GB）
#    JDK 17 + Android SDK (build-tools 34.0.0 / platform 34) + Gradle 8.9 + adb
.\setup_toolchain.ps1

# 2. 构建
.\build_apk.ps1
# 产物：out\filmbox.apk
```

`build_apk.ps1` 会先跑 `build_assets.py`（从原型生成 `index.html`，并拉取 Space Mono 字体和 MediaPipe 模型），再走 Gradle 打包签名。

签名密钥 `demo.keystore` 缺失时会自动生成一把。**别删** —— 换了钥匙就只能卸载重装，不能覆盖升级。

### 依赖

版本是钉死的，不是随手写的：

- **AGP 8.7.3 + Gradle 8.9** —— 8.7.3 是最后一个只要求 build-tools 34.0.0 的版本。8.13+ 要 35、9.x 要 36，而 `sdkmanager` 没有镜像支持，只能去 `dl.google.com`。
- **`androidx.core` 钉在 1.13.1** —— 1.15+ 要求 compileSdk 35。
- 依赖全部走**阿里云镜像**，构建过程不碰 Google 的网络。

---

## 已知限制

- **minSdk 29**（Android 10）。写相册用的 MediaStore `RELATIVE_PATH` + `IS_PENDING` 从 29 起不需要任何权限；24–28 要 `WRITE_EXTERNAL_STORAGE` 运行时授权，那条路径没有验证过，与其塞一段没测过的权限流程，不如把下限提上来。
- **只打包 arm64-v8a**。MediaPipe 的原生库四个 ABI 加起来约 48 MB，只留 arm64 是必需不是优化。APK 因此约 25 MB。
- **抠图用的是 `InteractiveSegmenterLegacy`**。新的 `InteractiveSegmenter` 要求 `.task` 打包格式（内部要 `interactive_segmentation_encoder.int8.tflite` 等一堆文件），而 `magic_touch` 只发布了裸 `.tflite`，官方模型库里没有对应的 `.task` 版本。裸模型只能配 legacy API。
- 抠图对**背景干净的照片**效果最好（纯色桌面、白纸）。背景杂乱时会有残留。
- 中文界面。没有英文版。

---

## 数据与隐私

所有数据存在 App 私有目录里：

```
<filesDir>/doc.json                        全部元数据
          /photos/<rollId>/<pid>.jpg       展示副本（长边 ≤ 2048）
          /thumbs/<rollId>/<pid>.jpg       缩略图（长边 ≤ 360）
          /originals/<rollId>/<pid>.<ext>  原片（仅导入时勾选"保留原片"）
          /gear/<gearId>.png               抠好的设备图（带 alpha）
```

**没有网络请求。** 抠图模型打进 APK 端上推理，导出直接写系统相册。清单里没有 `INTERNET` 权限 —— 可以用 `adb shell dumpsys package com.filmbox.archive | grep -i permission` 验证。

---

## 目录

```
prototype/film-archive-app.html   界面全部代码（改这里）
build_assets.py                   原型 → app assets 的生成器
build_apk.ps1                     构建入口
setup_toolchain.ps1               工具链安装
app/src/main/java/…/              原生层（桥、存储、导入、抠图、导出）
docs/                             README 截图
```
