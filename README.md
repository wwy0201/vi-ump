# vi-ump

> **U**nified **M**edia **P**rovider —— 配置驱动的**视频 / 音频 / 图片**聚合客户端
> 技术栈：Kotlin Multiplatform + Compose Multiplatform
> 多端目标：**Android** / Windows·macOS·Linux 桌面 / iOS / Web(Wasm)

> [!WARNING]
> **本项目仅供个人学习与技术研究使用。**
> 仓库不包含、也不提供任何内容源，所有配置与数据均由使用者自行导入，相关合规与授权责任由使用者自负。
> 使用本项目（含由此产生的任何直接或间接后果）造成的损失或纠纷，作者不承担任何责任。

---

## 一句话定义

**App 是一台"解释器"，配置是一段"程序"。**

vi-ump 自身不包含任何站点逻辑、不含任何硬编码的栏目/接口/解析代码。
用户导入一份 JSON 配置，App 在运行时把它解释成：**要请求哪些接口 → 怎么抽取字段 → 页面怎么排 → 视频/音频怎么播、图片怎么看**。

App 只负责定义"我需要哪些数据"（数据契约），配置负责定义"数据从哪来、长什么样、怎么呈现"。

## 核心设计边界（先明确不做什么）

| 做 | 不做 |
|---|---|
| 配置驱动数据来源、字段映射、页面结构、主题 | 配置驱动任意像素级布局（不做通用 UI DSL） |
| 受控布局库（Layout Palette）+ slot 参数 | 允许配置注入自定义 JS / 执行任意脚本 |
| 抽取规则：JSONPath / 正则 / 分隔符 / 模板 / CSS | 允许配置访问本地文件、content:// 、任意 scheme |
| 多源并存（各自独立切换）+ **当前源内搜索** | 内置任何第三方站点配置（配置由用户提供）；**跨源聚合搜索**（用户 2026-11-16 决定不做，`flags.multiSourceSearch` 已删） |
| 一套 commonMain 代码复用至多端 | 为某个端单独分叉业务逻辑 |

> 取舍理由见 `docs/01-架构总览.md` 第 3 节。
> 一句话：**配置驱动"数据与结构"，不驱动"任意像素"**。

---

## 文档索引

| 文档 | 内容 | 读者 |
|---|---|---|
| [docs/01-架构总览.md](docs/01-架构总览.md) | 设计原则、三层编译模型、模块拆分与依赖关系、数据流 | 所有人 |
| [docs/02-功能划分.md](docs/02-功能划分.md) | 功能清单 F1–F10、页面清单（含视频/音频/图片三分支）、验收标准、优先级 | 产品 / 开发 |
| [docs/03-配置Schema规范.md](docs/03-配置Schema规范.md) | **配置文件的完整语法与语义规范**（核心资产） | 配置编写者 / 开发 |
| [docs/04-实现原理.md](docs/04-实现原理.md) | 各模块关键实现原理、关键类设计、伪代码 | 开发 |
| [docs/05-开发路线图.md](docs/05-开发路线图.md) | 里程碑拆分、依赖顺序、风险点 | 开发 |
| [docs/06-多端适配方案.md](docs/06-多端适配方案.md) | 多端矩阵、共享边界、expect/actual 清单、各端打包流程 | 开发 / 发布 |

示例配置（4 份，均通过 `tools/validate_config.py` 与 `core:config` 的 Kotlin 校验器双重检查）：`bing-wallpaper.json`（每日壁纸数组，`tracks` 走 `array` 形态）、`bilibili.json`（多频道 + 详情 + 直链）、`youtube.json`（RSS 文本分条 `listSplit` + 响应外壳剥离 + 地址模板拼接）、`bilibili.json`（多频道 `channels[]` + 分页 + 字段 `rewrite` + 详情/直链）、`itunes-audio.json`（两级音乐接口，30 秒试听 m4a 走音频播放页）。

> **`samples/` 中的源配置仅供技术测试使用**：用来验证配置引擎的解析、抽取与播放链路能否跑通，不是产品功能的一部分，也不代表对任何第三方站点的背书或授权。这些配置**不随任何安装包发布**，仓库只保留这 4 份；其余源文件只存在于开发者本地，不进版本控制。使用这些配置访问第三方接口所产生的合规、授权与责任问题由使用者自行承担。

> **样本不随包发布（2026-11-16，M5.10）**：APK / 桌面 jar 里都不自带任何源，用户通过设置页「导入 / 粘贴 / URL」把自己的配置放进 `filesDir/sources`（桌面 `~/.vi-ump/sources`）。
> `samples/` 留在仓库里的身份只剩两个：文档示例 + 回归样本（`SampleConfigTest` 把它们逐个编译一遍）。

接口响应快照放在 `samples/fixtures/`，单测直接回放它们——**这类夹具只在这一处维护**，不要在测试里另写假 JSON。依赖本地源文件的用例在找不到对应样本时会自动跳过，不影响干净克隆下的测试。

---

## 工程结构（当前已生成）

```
vi-ump/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml          # 版本目录（统一管理依赖版本）
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── android/                    # Android 手机应用壳（Compose + Media3）
│   ├── tv/                         # Android 4.4.2 TV 独立壳（原生 View + ExoPlayer2，minSdk 19）
│   ├── desktop/                    # Compose Desktop 壳（jpackage 打包，P1）
│   └── （iosApp / web 位置预留，见 docs/06）
├── core/
│   ├── model/                      # 域模型 + 配置模型（仅依赖 kotlinx.serialization）
│   ├── common/                     # Result/StageResult、日志、DI、调度器
│   ├── query/                      # ★ 抽取引擎（JSONPath/正则/分隔符/模板）+ 字段变换
│   ├── config/                     # ★ 配置装载、校验、编译为 CompiledSchema
│   ├── source/                     # HTTP 引擎 + 数据管线执行 + 源注册表
│   ├── storage/                    # SQLDelight：历史/收藏/配置/缓存
│   ├── player/                     # 播放器门面（expect，视频/音频共用）+ 地址解析策略
│   └── ui/                         # 设计系统：主题、卡片、状态页、图片查看器
├── render/                         # ★ 渲染引擎：PageNode 树 → Compose UI + 布局库
├── feature/
│   └── main/                       # 页面级状态 + 导航（home / detail / player / search / settings，未上 ViewModel）
├── samples/                        # 站点配置样本（唯一测试数据来源，均通过静态校验；**不随包发布**）
│   └── fixtures/                   # 外部接口响应快照（域名与标识已占位化）
├── tools/
│   └── validate_config.py          # 配置校验器（无 Gradle 环境时先验配置，也是 Kotlin 校验器的参考实现）
└── docs/
```

★ 三个技术核心：**抽取引擎、配置编译器、渲染引擎**。

---

## 多端策略（关键约束）

**一份配置、一套 Compose UI，跑在多端；平台差异收敛到 6 个 `expect/actual`。**

```
纯共享（100% commonMain）
  core:model  core:query  core:config  core:common
  core:ui     render      feature:main
        ▲
        │ 6 个 expect/actual
        │  PlayerFacade  HtmlParser  KeyValueStore
        │  DatabaseDriver  FileGateway  PlatformInfo
        ▼
平台实现
  androidMain   jvmMain(桌面)   iosMain   wasmJsMain
```

| 端 | 状态 | 打包 |
|---|---|---|
| Android 手机/平板 | **开发中（P0）** | APK / AAB（minSdk 24） |
| Android TV / 盒子（4.4.2 / API 19） | **可测试（P1）** | 独立 APK `:app:tv`（原生 View，minSdk 19） |
| Windows/macOS/Linux | 骨架已建（P1） | MSI / DMG / DEB |
| iOS | 待启用（P2，需 macOS） | IPA |
| Web(Wasm) | 待评估（P3） | 静态站点 |

当前所有库模块已启用 `androidTarget + jvm`，Windows 开发机上 iOS target 由
`kotlin.native.ignoreDisabledTargets=true` 自动跳过。详见 `docs/06`。

---

## 数据流（30 秒看懂）

```
用户导入 config.json
        │
        ▼
[core:config]  解析 → 校验 → 编译            ← CompiledSchema（预编译好路径、模板、映射表）
        │
        ▼
[core:source]  模板渲染 URL → HTTP 请求
        │
        ├──► [core:query] 按 Extractor 规则抽取 → Transformer 变换 → 域模型 MediaSummary/MediaDetail
        │
        ▼
[render]   PageNode 树 + 域模型 → 布局库 → Compose UI
        │
        ▼
[core:player]  选集 → PlayResolver（直链/解析接口）→ ExoPlayer / VLCJ / AVPlayer / <video> / <audio>
        │
        └──► 图片不播放：[core:ui] ImageViewer（缩放 / 翻页 / 预加载）
```
