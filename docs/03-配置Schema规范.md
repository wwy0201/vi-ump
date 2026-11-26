# 03 · 配置 Schema 规范 v1.0

> **这份文档是整个项目的核心资产。**
> `core:query`（抽取引擎）、`core:config`（编译器）、`render`（渲染引擎）全部围绕本文的语义实现。
> 改本文 = 改契约，必须同步三个模块。

---

## 0. 设计原则

| 原则 | 含义 |
|---|---|
| **声明式，无脚本** | 只描述"是什么"，不描述"怎么算"。不做 JS 引擎，所有变换限定在有限原语内 |
| **声明即校验** | 每一条规则在加载期就能判定合法性，错误定位到字段路径 |
| **兜底优先** | 任何字段失败不能导致整页崩溃：字段级 `default`、条目级跳过、页面级错误态 |
| **可逆** | 导出的配置能回环导入并通过校验（不存在"只在内存里才成立"的语义） |
| **向前兼容** | `schemaVersion` + 迁移链，旧配置永远能升级，不静默失效 |

---

## 1. 顶层结构

```jsonc
{
  "schemaVersion": "1.0",        // 必填，版本号，用于迁移
  "meta":     { ... },           // 必填，源的元信息
  "runtime":  { ... },           // 可选，网络/编码/全局变量
  "resources":{ ... },           // 必填，数据获取规则（"从哪来"）
  "pages":    [ ... ],           // 必填，页面结构（"怎么排"）
  "playback": { ... },           // 可选，播放策略
  "theme":    { ... },           // 可选，主题
  "flags":    { ... }            // 可选，功能开关
}
```

---

## 2. `meta` — 源元信息

```jsonc
"meta": {
  "id": "demo-site",                 // 必填，全局唯一，建议 kebab-case
  "name": "演示源",                   // 必填，UI 展示名
  "author": "vi-ump",                // 可选
  "version": "1.0.0",                // 可选，源自身版本
  "description": "配置规范演示",        // 可选
  "icon": "https://cdn.example.com/icon.png", // 可选
  "color": "#3DDC97"                 // 可选，源标识色（见 §2.1）
}
```

> `meta.id` 是历史/收藏记录的主键前缀（`history` 表里 `sourceId + mediaId` 联合定位），**导入时若冲突必须提示用户覆盖或改 ID**。

### 2.1 `meta.color` — 源标识色

首页顶栏的当前源名、源下拉的选中项、设置页该源那一行，都用这个颜色；**未选中的源一律灰色**。它回答的只有一件事："我现在用的是哪个源"。

| 情况 | 结果 |
|---|---|
| 写了 `meta.color`（`#RRGGBB` / `#AARRGGBB`） | 原样使用 |
| 没写，但显式配过 `theme.primary`（不是默认 `#FF6B35`） | 用 `theme.primary` |
| 两者都没有 | 客户端**自动分配**：按 `meta.id` 落到某个色相，再以黄金角逐步试探，保证与其它源**色相至少差 40°**（不会出现两个橙色系源） |
| 两个源显式写成同一个颜色 | **就用同一个颜色**——去重只作用于自动分配出来的色 |

颜色也参与整体主题：当前源的 `theme.primary` 若是默认值（= 作者没配），客户端会用该源的标识色兜底，所以切源时按钮/选中态的主色也跟着变；作者显式配过 `theme.primary` 就尊重作者，不覆盖。

---

## 3. `runtime` — 运行时环境与全局变量

```jsonc
"runtime": {
  "baseUrl": "https://api.example.com",   // 必填，接口根地址（可被各 request 用 {{baseUrl}} 引用）
  "imageBase": "https://cdn.example.com", // 可选，图片前缀（可空，为空则 poster 字段原样使用）
  "headers": {                            // 可选，全局请求头，会与单条 request.headers 合并（后者优先）
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
    "Referer": "https://example.com/"
  },
  "encoding": "utf-8",                    // 可选，响应解码字符集（GBK 站点填 "gbk"）
  "timeoutMs": 15000,                     // 可选，单次请求超时，默认 15000；**上限 30000**（写更大按 30000 走）
  "retry": 2,                             // 可选，网络错误重试次数（不含首次），默认 2；**上限 3**，间隔固定 3000ms
  "proxyBypass": [                        // 可选，这些域名的请求走直连、不吃系统代理，默认 []（全部走系统代理）
    "example.com", "example-cdn.com"
  ],
  "crypto": {                             // 可选，通用加解密全局默认（见 §3.3）：各挂点只写差异，key/iv 在这里共用
    "algo": "aes-128-cbc",
    "key": "f5d965df75336270",
    "iv": "97b60394abc2fbe1",
    "padding": "pkcs7",
    "output": "base64"
  },
  "imageDecrypt": {                       // 可选，CDN 上给的是**密文图**时的解码规则（见 §3.2）
    "algo": "aes-128-cbc",
    "key": "…16 字符…",
    "iv": "…16 字符…",
    "padding": "pkcs7"
  }
}
```

**安全约束**：`baseUrl`、`imageBase`、所有 `request.url` 必须是 `http://` 或 `https://`。
编译期拒绝 `file://`、`content://`、`javascript:`、相对路径。

#### 3.0.1 `timeoutMs` / `retry` — 只能往小改

| 字段 | 默认 | 允许范围 | 越界处理 | 生效位置 |
|---|---|---|---|---|
| `timeoutMs` | 15000 | 1000 – 30000 | 夹到 `1s` / `30s` | request 级 `timeout { requestTimeoutMillis / connectTimeoutMillis / socketTimeoutMillis }` |
| `retry` | 2 | 0 – 3 | 夹到 `0` / `3` | `HttpFetcher.fetch` 外层重试循环，**间隔固定 3000ms**，不做指数退避 |

两条补充口径：

- **重试不是无脑重发**：网络异常/超时（`E010`）会重试；HTTP `5xx` 会重试；**`4xx` 不重试**
  （400/401/404 是确定的拒绝，再试只是把等待翻倍）。
- **写错的配置不会把界面拖死**：这三个量在 `core:source/HttpFetcher.kt` 里夹住，
  最坏情况是"一次请求 30 秒×4 次 ≈ 两分钟"，不会出现 `timeoutMs: 0` 那种无限等待。
  实现细节见 `docs/04 §5.5`。

#### 3.1 `proxyBypass` — 让指定站点绕过系统代理

**语义**：数组元素是**域名后缀**，匹配规则为 `host == suffix` 或 `host.endsWith(".$suffix")`（大小写不敏感）。
命中的请求走 `DIRECT`，其余仍走系统代理。

**为什么这个字段必须存在**（2026-11 实测，会员视频示例源）：

| 现象 | 真因 |
|---|---|
| 桌面端切到示例视频源后 `[E010] Remote host terminated the handshake` | 国内 CDN 拒绝"经 HTTP 代理隧道进来的 TLS 握手" |
| 同一 URL 用 PowerShell / JDK 自带 HttpClient 走同一代理 → `200` | 说明不是 TLS 版本、不是 UA，问题就在 OkHttp 经隧道这条链路 |
| `http.nonProxyHosts` 写了也不生效 | 在 `java.net.useSystemProxies=true` 下该属性被忽略 |

**边界**：它是**站点差异**，所以属于配置，不写死在代码里。
桌面端由壳层 `ProxySupport` 读取本字段装 `ProxySelector`；Android 端不读（不用系统代理，直连即可）。
只支持域名后缀，**不支持通配表达式或正则**——避免配置写出复杂的匹配规则（那是运维脚本的活）。

#### 3.2 `imageDecrypt` —— CDN 上是密文图时怎么办（**v1 已落地**，A 档：固定 key/iv + AES-128-CBC）

有一类站点（`samples/` 里的短剧示例配置是实测样本）把**封面/海报以 AES 密文存放**，
页面里用一段固定协议的 worker 解密后再塞进 `<img>`。特征很明确：

| 特征 | 实测值（示例短剧源） |
|---|---|
| `Content-Type` | `binary/octet-stream`（个别镜像域伪装成 `image/jpg`，但字节还是密的） |
| 首字节 | 不是任何图片魔数，`FF D8` 也不出现 |
| 字节数 | 恰好是 16 的倍数（CBC 块对齐） |

`poster` 字段配得再对，只要 App 拿到的是密文，解码器必然失败——表现是
"**列表能出、标题能出、图全是灰块**"，而且**不报错**（图片解码失败是静默的）。
所以这类源需要的不是改字段，而是一条**解码规则**：

```jsonc
"runtime": {
  "imageDecrypt": {
    "algo": "aes-128-cbc",       // 算法白名单与 §3.3 完全相同（写不在表里的报 E003）
    "key": "f5d965df75336270",   // 默认 16 个字符，按 **UTF-8 取 16 字节**（不是 hex 解码）；aes-256 时 32 字节
    "iv":  "97b60394abc2fbe1",   // 同上；ECB 不需要 iv，GCM 是 12 字节
    "padding": "pkcs7",          // pkcs7（标准）| none（不解填充）| zero（解密后去尾部 0 字节）
    "output": "raw"              // 图片通路默认 raw（拿到多少字节就是多少），一般不用写
  }
}
```

> 图片通路与 §3.3 的通用通路**共用同一套引擎**（`core/query/…/CryptoCodec.kt`）：`imageDecrypt` 只是
> "同一组参数 + `output=raw`"的语法糖。§3.3 的算法白名单 / padding / keyFormat 规则对这里同样生效。

**App 侧执行顺序（固定，不可配置）**：

1. 按 `poster` / `imageUrl` / `thumb` 抓到字节；
2. **先嗅明文**（判断"这坨字节本来是不是图"）：`RIFF….WEBP` / `\x89PNG` / `GIF8` → 直接当图片用，跳过解密。
   站点自己的 worker 也这么做（有些转码 CDN 存的是明文 WebP）。**JPEG 的 2 字节魔数在这里不参与嗅探**，
   太短了，随机密文有概率撞上，会把密文当图送去解码；
3. 否则按 `imageDecrypt` 解密 → 交给图片解码器（mime 交给解码器自己按字节判，不看 URL 后缀）；
   **解密产物用另一套口径校验**：`\x89PNG` / `GIF8` / `RIFF….WEBP` / **`FF D8 FF`（JPEG）**。
   两套口径分开是因为它们回答的问题不同——输入侧是"猜这是不是图"（2 字节不可信），
   产物侧是"确认刚才解对了"（已确知有密文、也确知 key 口径，3 字节够强）；
4. 解密失败（key 轮换 / 不是密文 / 算法不符）→ **留空位**，不阻断列表与详情（与 §6.3 的"字段失败不炸整页"同一思路）。
   `W004`（"声明了 `imageDecrypt` 但解密失败"）**尚未实现**——当前失败是静默占位，没有任何提示；
5. 缓存的是**解密后的字节**：键 = 规则指纹 + 密文 URL（站点换 key 之后旧缓存不会再命中），
   客户端只保留 24 条内存 LRU（约"一屏半"），不做磁盘缓存。

**客户端实现（2026-11-16 落地，Android + Desktop 共用同一套 commonMain 代码）**：

| 环节 | 位置 |
|---|---|
| 契约（`ImageDecryptSpec`）与校验 | `core/model/…/ConfigModels.kt`、`core/config/…/ConfigValidator.kt`（`E002/E003`）、`tools/validate_config.py` |
| 取字节 | `core/source/…/HttpFetcher.kt` 的 `fetchBytes()`（与接口请求共用超时/重试/默认头） |
| 解密与判定 | `render/…/ImageDecrypt.kt`（`looksLikePlainImage` / `looksLikeDecodedImage` / `decryptImage` / `trimJpegTail`），加解密本身复用 `core:query` 的 `CryptoCodec` |
| 平台密码库 | `core/query/src/androidMain` 与 `core/query/src/jvmMain` 的 `CryptoBlock.kt`（`javax.crypto`，不引入第三方依赖，§16.1） |
| 注入点 | `feature/main/…/AppRoot.kt`：`LaunchedEffect(session)` 读 `runtime.imageDecrypt` + `runtime.headers` 装进 `UmpImageDecrypt` |
| 统一入口 | `render/…/UmpAsyncImage`（render 内 9 处图片调用全部走它；源没声明规则时行为与 `AsyncImage` 完全一致） |

**为什么不用 Coil 的 `Fetcher`（这条路已两次实测判死，别再试）**：
`ImageLoader.components { add(Factory()) }` 与 `ImageRequest.fetcherFactory(Factory, String::class)`
两种注册方式下，密文 URL 都依然先被 Coil 原生 `NetworkFetcher` 接走——日志里自定义工厂的 `create`
一次都没被调用，等于解密代码永远不执行。最终路线是**在字节进 Coil 之前就解好**：
自己取字节 → 解密 → 把**明文**通过 `ImageRequest.data(ByteArray)` 交给 Coil 的内置解码通路。

**踩坑记录（实现时踩到，写下来免得后人再踩）**：解密产物绝大多数是 JPEG，若复用输入侧的嗅探口径
（不认 JPEG），刚解出来的好图会被判成失败、丢掉，界面表现为"下载成功但永远空块"。

**为什么放 `runtime` 而不是 `playback`**：它作用于所有图片通路（列表卡片、详情海报、图集 `thumb`），与播放策略无关。
`imageDecrypt` 存在与否**不影响视频/音频条目**（§10 的 `playback` 对 `kind=image` 本来就是忽略的）。

**实测证据（2026-11，示例短剧源）**：

| 观测 | 结果 |
|---|---|
| `示例图片域` / `示例图片域二` / `示例图片域三` 同一张图 | 三份**完全相同**的密文字节 —— 换域名拿不到明文 |
| `备用图片域/<path>.webp`（站点 WebP 转码域） | 也是密文，字节不同 —— 换格式也拿不到明文 |
| 带/不带 `?auth_key=`、`Accept: image/webp`、`x-cdn-auth` 头 | 字节都不变 —— 不是靠请求头"解锁" |
| AES-128-CBC + PKCS7（key/iv 取 UTF-8 字节）解 `2026092411162272798.jpg` | 174816 → **174813** 字节、`FF D8 FF E0 … FF D9`、1086×1448 的 JPEG，可正常解码 |
| 站点 worker 的实际做法 | WebCrypto 只有 PKCS7，所以它用 NoPadding + 自己补块、再按密文长度截断；尾部会多 1–15 个填充字节，浏览器解码无感 |

**能力边界（写清楚免得配置作者误解）**：

- 覆盖"**配置里能写死的参数**"这一档：算法白名单见 §3.3，`key`/`iv`/`padding`/`output` 全部可配。
  站点用 RSA、国密 SM4、每图一 key、或 key 由接口动态下发时，配置表达不了（v1 明确不做），
  按 §13 的 `W003` 处理（可解释地降级）——不允许为了一个源往配置里塞脚本（§0 原则一）。
- key/iv **会轮换**：站点把它写在带版本号的静态文件里（`crypto-image-protocol.js?v=…`）。轮换后旧配置只会让
  "图变灰块"，不会崩；重新抄一遍 key 即可。
- 密文与直链签名是两件事：`?auth_key=` 过期只会让**请求** 403，与能不能解密无关。

---

### 3.3 `crypto` / `decrypt` / `encrypt` — 通用加解密

§3.2 解决的是"**图片**是密文"。这一节解决另一大半：站点把**接口参数**加密、把**响应体**整段或某个字段加密、
或者 `poster` 一类的**单个字段**是密文串。三者用的是同一套参数与同一个引擎，只是挂点不同。

#### 3.3.1 四个挂点

| 挂点 | 作用 | 生效时机 |
|---|---|---|
| `runtime.crypto` | **默认参数**：`algo/key/iv/padding/output/keyFormat/ivFormat` 的缺省值 | 编译期被各挂点继承 |
| `resources.*.decrypt` | **响应体解密**：`from` 为空 = 整段 body 当密文；`from` 非空 = 取该 JSONPath 的值解密后**顶替整段 body** | `strip` 之后、解析之前 |
| `fields.*.decrypt` | **单字段解密**：字段抽出来的值当密文解 | `trim`/`prefix`/正则等变换**之前** |
| `request.encrypt` | **请求参数加密**：`mode=params` 加密指定参数（URL query 同名项 / JSON 顶层字符串字段），`mode=whole` 整段 body 换密文 | 发请求之前 |

四者都是**平铺对象**，只写自己关心的字段，其余从 `runtime.crypto` 继承：

```jsonc
"runtime": {
  "crypto": { "algo": "aes-128-cbc", "key": "…", "iv": "…", "output": "base64" }  // 只需要写一次
},
"resources": {
  "feeds": {
    "main": {
      "request": {
        "method": "POST",
        "url": "{{baseUrl}}/api/list?page={{page}}",
        "bodyType": "json",
        "body": "{\"data\":\"1\",\"appId\":\"demo\"}",
        "encrypt": { "mode": "params", "params": ["data"] }        // data 加密，appId 原样
      },
      "decrypt": {},                                               // 整段响应是密文
      "root": "$.data",
      "list": "$.items[*]",
      "fields": {
        "id": "$.id",
        "title": "$.title",
        "poster": { "from": "$.cover", "prefix": "{{imageBase}}" }
      }
    }
  },
  "details": {
    "main": {
      "request": { "url": "{{baseUrl}}/api/detail?id={{id}}" },
      "decrypt": { "from": "$.data" },                             // 外壳明文，data 是密文
      "root": "$.data",
      "fields": {
        "id": "$.id",
        "title": "$.title",
        "poster": { "from": "$.cover", "prefix": "{{imageBase}}", "decrypt": {} },
        "description": "$.desc"
      }
    }
  }
}
```

#### 3.3.2 字段表

`CryptoSpec`（`runtime.crypto`）与 `DecryptSpec` / `EncryptSpec` 的同名字段口径完全一致：

| 字段 | 默认 | 取值 | 说明 |
|---|---|---|---|
| `algo` | `aes-128-cbc` | 见下方白名单 | `-` 分隔：`算法-密钥位数-模式`；DES/3DES 无位数段 |
| `key` | — | 按 `keyFormat` 解码后的字节 | 长度必须等于算法要求，否则报 `E002` |
| `iv` | — | 按 `ivFormat` 解码后的字节 | ECB 不需要（长度 0）；GCM 12；AES 16；DES 8 |
| `padding` | `pkcs7` | `pkcs7` \| `none` \| `zero` | `zero` = 解密后去掉尾部 `0x00`；`none` = 原样 |
| `output` | `base64` | `base64` \| `base64url` \| `hex` \| `raw` | 密文在**线上**的编码；`raw` 只对响应解密/请求整段体有意义 |
| `keyFormat` | `text` | `text` \| `hex` \| `base64` | `text` = UTF-8 原样（示例短剧源那种 16 个可见字符的 key） |
| `ivFormat` | `text` | 同上 | 与 `keyFormat` 独立，站点常见"key 是 text、iv 是 hex" |

**算法白名单**（不在表里 → `E003`）：

| 家族 | 可用 `algo` | key 字节 | iv 字节 |
|---|---|---|---|
| AES-CBC | `aes-128-cbc` / `aes-192-cbc` / `aes-256-cbc` | 16 / 24 / 32 | 16 |
| AES-ECB | `aes-128-ecb` / `aes-192-ecb` / `aes-256-ecb` | 16 / 24 / 32 | 0 |
| AES-CTR | `aes-128-ctr` / `aes-192-ctr` / `aes-256-ctr` | 16 / 24 / 32 | 16 |
| AES-GCM | `aes-128-gcm` / `aes-192-gcm` / `aes-256-gcm` | 16 / 24 / 32 | 12（tag 128 位，固定在密文尾部） |
| DES | `des-cbc` / `des-ecb` | 8 | 8 / 0 |
| 3DES | `des3-cbc` / `des3-ecb` | 24 | 8 / 0 |

两条硬规则：

- **CTR / GCM 的 `padding` 必须是 `none`**（流式/认证模式，补块只会破坏数据），否则 `E003`；
- **`decrypt.from` 与 `encrypt.params` 的组合有语义**：`decrypt.from` 只在 `resources.*.decrypt` 上有意义（字段解密的
  `from` 会被忽略——值已经在手上），`encrypt.params` 只在 `mode=params` 上必填（空数组 → `E002`）。

#### 3.3.3 执行顺序（固定，不可配置）

**响应侧**：

```
HTTP body
  → strip（§5.4 的固定前后缀）
  → decryptBody：from==null ? 整段解密 : 路径取值 → 解密 → 顶替整段 body
  → 解析（JSON / 文本 / HTML）
  → 抽字段
  → TransformChain.apply(raw, spec, crypto)：先按字段 decrypt 解出明文，再做 trim/prefix/rewrite/正则
```

三个顺序细节值得记住：

1. **解密在 `strip` 之后**：外壳（`VAR=…;` 这类外壳）是明文包装，剥掉才能拿到里面真正的那坨密文；
2. **整段解密后 body 被整体替换**：所以 `root` / `list` 写的是**解密后**明文里的路径，不是原始信封的路径；
3. **字段解密在 `prefix` 之前**：`poster` 常见写法是"密文串 + `prefix: {{imageBase}}`"，先拼前缀会把域名和密文粘成
   一串（形如 `https://img.example.com/<密文>`），解出来就不再是合法的图片地址。

**请求侧**：

```
url 模板渲染 → encrypt（params：URL query 同名参数替换成密文并 percent 编码 / JSON 顶层字符串字段替换成密文；
             whole：整个 body 换成密文）→ 发请求
```

- `mode=params` 只认**顶层**：JSON 里嵌在数组/对象深处的同名键**不动**（v1 不做递归查找，避免误伤）；
- 加密后的 query 值一律 percent 编码（`+` `/` `=` 在 query 里必须转义，否则服务端解码后是别的字节）；
- `mode=whole` 时 `body` 模板本身作为明文，替换成密文串（`raw` 输出直接把字节当 body 发）。

#### 3.3.4 失败行为与能力边界

- **解密失败一律静默降级**，不抛异常、不阻断页面：响应体原样返回（解析自然失败 → 空列表）、
  字段留空、图片留空位。理由与 §6.3 一致——一个字段写错不该把整页炸掉。
  `W004`（"声明了加密但解密失败"）**尚未实现**，当前唯一的手动排查手段是临时把同一段密文丢进
  `tools/validate_config.py` 的同口径参数里试解。
- **不做**：RSA / SM2 / SM4 等非对称或国密算法、HMAC/AES 之外的**签名**计算、key 从接口动态获取、
  每请求随机 iv 的自动生成（`iv` 必须是配置里写死的值）。这些属于"需要跑代码"的范畴，按 §0 原则一不进配置。
- **`resources.tracks.*` 的字段级 `decrypt` 不生效**（v1 只接在 `resources.feeds/details/search/categories` 的 fields 上），
  声明了会报 `W003` 提醒。

#### 3.3.5 实现位置

| 环节 | 位置 |
|---|---|
| 引擎（算法/编码/补块） | `core/query/…/CryptoCodec.kt`（commonMain）+ `CryptoBlock.kt`（androidMain / jvmMain，`javax.crypto`） |
| base64 / hex | `CryptoCodec` 内的**纯 Kotlin** 实现（避开 `java.util.Base64` 的 API 26 限制，也让 `jvmTest` 两端一致） |
| 编译期烘焙 | `core/config/…/CompiledSchema.kt`：`CompiledDecrypt` / `CompiledEncrypt` / `CompiledField.crypto`，缺省继承与合法性检查在这里做 |
| 校验 | `core/config/…/ConfigValidator.kt` 的 `checkCrypto` / `checkDecrypt` / `checkEncrypt`；Python 参考实现 `tools/validate_config.py` |
| 接线 | `core/source/…/SourcePipeline.kt`（`decryptBody` / `cryptoByField`）+ `RequestCrypto.kt`（请求侧） |
| 字段级解密 | `core/query/…/Transforms.kt` 的 `TransformChain.apply(raw, spec, crypto)` |

**为什么放 `core:query` 而不是 `core:source`**：`TransformChain` 在 `core:query`，字段解密是它的一部分；
`core:config` 要参与烘焙，`core:source` 要参与接线——放在最底层的 `core:query` 三家都能引用，
`render` 的图片解密也顺带复用它（§3.2 的 `ImageDecryptActual.kt` 因此被删除）。

**测试**（口径保障，改任一处都要跑）：

| 模块 | 用例 |
|---|---|
| `core:query` | `CryptoCodecTest`（10 例：base64/hex 往返、AES-CBC/GCM/3DES、`zero` vs `none`、坏输入、`fingerprint` 不含 key） |
| `core:config` | `CryptoConfigTest`（4 例：三挂点继承、缺 key、`encrypt` 无 params、`from` 路径非法）；`ConfigTest` 的 `imageDecrypt` 组 |
| `core:source` | `CryptoPipelineTest`（4 例：整段解密、`from=$.data` 外壳、单字段（在 prefix 之前）、请求参数加密端到端） |

对应示例：当前仓库没有单独的 `crypto-demo.json`，通用加解密（四个挂点）由 `CryptoPipelineTest` 的端到端用例覆盖；图片解密使用 `samples/` 里的短剧示例配置的密文封面验证。

---

## 4. 变量与模板（`{{...}}` 语法）

所有 `url`、`headers` 值、`body`、`prefix`/`suffix` 支持 `{{变量}}` 插值。

### 4.1 变量作用域

| 变量 | 可用位置 | 含义 | 来源 |
|---|---|---|---|
| `{{baseUrl}}` / `{{imageBase}}` | 任意 | 全局常量 | `runtime` |
| `{{page}}` | `request.url` / `body` | 当前页码 | 分页引擎 |
| `{{pageSize}}` | 同上 | 每页条数 | 分页引擎（默认 20） |
| `{{id}}` | 详情/选集请求 | 当前媒体 ID | 导航参数 |
| `{{keyword}}` | 搜索请求 | 关键词，**已 URL 编码** | 搜索页（`SourcePipeline.search()` 注入） |
| `{{keywordRaw}}` | 搜索请求 | 关键词原值（给 body / JSON 字段用） | 同上 |
| `{{p.<name>}}` | 任意 | 页面 `params` 里声明的参数 | page 定义 |
| `{{f.<key>}}` | 任意 | 当前筛选项的值 | 筛选条状态 |
| `{{item.<field>}}` | `card` / `action` / 嵌套请求 | 当前条目已映射后的域字段 | 映射结果 |
| `{{url}}` | `playback.resolver` | 待解析的原始播放地址 | 播放器 |

### 4.2 规则

- 变量缺失 → 替换为空串（**不抛异常**，避免整条请求失败）。
- **模板只做字符串替换，不做任何编码**（`TemplateEngine.render`，正则 `\{\{\s*[A-Za-z0-9_.]+\s*\}\}`）。
  这条是 M6.3 核对过的：URL 里的变量值原样拼进去，值里有空格 / 中文 / `&` / `#` 时**调用方要自己先编码**。
  目前唯一的"自动编码"是搜索关键词——`SourcePipeline.search()` 注入 `{{keyword}}` 时先过 `encodeURLParameter()`，
  所以搜索配置里写 `q={{keyword}}` 就是对的；需要原样值时用 `{{keywordRaw}}`。
- `{{url|raw}}`（"不编码"修饰）**尚未实现**：当前正则匹配不到 `|raw`，写了会原样留下字面量 `{{url|raw}}`。
  规划中的最小改法见 `docs/04 §8.5`（`TemplateEngine` 按 `|raw` 切段）与本文 §10.2（`playback.resolver` 整条链路未接）。
- 模板里没有 `{{}}` 时零开销，编译期直接当常量。

---

## 5. `resources` — 数据获取规则

`resources` 是一个**资源名 → 规则**的字典。页面通过名字引用资源，因此：

- 一份配置可以定义任意多个栏目、任意多个详情源；
- 同一个资源可以被多个页面复用，参数不同；
- 资源名是 `pages` 与 `resources` 之间唯一的耦合点，编译期做引用完整性校验。

### 5.1 资源类型

| 键 | 类型 | 产出 | 必填 |
|---|---|---|---|
| `categories` | `CategoryResource` | `List<NavCategory>` | 可选（首页 Tab 也可直接用 `pages` 定义） |
| `feeds` | `Map<String, FeedResource>` | `PagedResult<MediaSummary>` | **必填（至少 1 个）** |
| `details` | `Map<String, DetailResource>` | `MediaDetail` | **必填（至少 1 个）** |
| `tracks` | `Map<String, TrackSpec>` | `List<TrackGroup>` | 可选（详情内联亦可） |
| `filters` | `FilterResource` | `List<FilterGroup>` | 可选 |
| `search` | `Map<String, FeedResource>` | `PagedResult<MediaSummary>` | 可选（无则禁用搜索） |

> **`tracks` 是三种媒体共用的"条目容器"**，按 `mode` 分两态：
>
> | mode | 形态 | 典型场景 |
> |---|---|---|
> | `delimited` | 多级分隔串（`$$$` / `#` / `$`） | 视频剧集、音频曲目串 |
> | `array` | JSON 数组 + 字段映射 | 图片集（相册、漫画分页） |
>
> 视频/音频/图片的差别只体现在**内容怎么排列**，不存在三套独立结构——
> 这是刻意的：配置作者只需学一次 `tracks`。

> ⚠️ `pages[].feed` 引用的是**资源的键名**（`feeds` / `search` 字典里的 key），
> 不是资源类型名。例如 `resources.search` 里定义 `"byKeyword": {...}`，
> 搜索页就要写 `"feed": "byKeyword"`，而不是 `"feed": "search"`。
> 编译期按 key 做引用完整性校验（E005）。

### 5.2 `ResourceSpec` 通用结构

所有资源（除 `categories` / `filters`）共享同一套管线描述：

```jsonc
{
  "request": {                       // 必填
    "method": "GET",                 // GET | POST，默认 GET
    "url": "{{baseUrl}}/api/list?cat={{p.category}}&page={{page}}&size={{pageSize}}",
    "headers": { "X-Token": "abc" }, // 可选，与 runtime.headers 合并
    "body": null,                    // POST 时可用，支持 {{}} 模板
    "bodyType": "json"               // json | form | raw，默认 json
  },
  "responseType": "json",            // json | text | html | auto，默认 json
  "strip": null,                     // 可选：剥掉响应体的固定前后缀（见 §5.4）
  "root": "$.data",                  // 可选，抽取起点（在 list/fields 之前先定位一次）
  "list": "$.items[*]",              // 列表资源必填；单对象资源不填
  "listSplit": null,                 // 可选：非 JSON 响应的分条正则，声明后忽略 list（见 §5.4）
  "fields": { ... },                 // 必填，域字段映射
  "paging": { ... },                 // 可选，分页规则；不填表示一次拉全
  "episodes": { ... }                // 可选，选集/多线路规格；不填表示详情页不显示选集（见 §5.5）
}
```

> **`root` 的作用**：TVBox 类接口常见 `{"code":0,"data":{"list":[...]}}` 的包装层。
> 配 `root: "$.data"` 后，后续 `list`/`fields` 的路径都相对于 `$.data` 求值，配置更短、更易复用。

### 5.3 `paging` — 分页规则

三种分页风格统一抽象：

```jsonc
// 风格 A：页码递增（最常见）
"paging": { "mode": "page", "param": "page", "startAt": 1, "hasMore": "$.has_more" }

// 风格 B：偏移量
"paging": { "mode": "offset", "param": "offset", "startAt": 0, "step": 20 }

// 风格 C：游标（两种取法，见 §5.3.1）
"paging": { "mode": "cursor", "cursorFrom": "$.next_cursor" }        // JSON 通路：路径取游标
"paging": { "mode": "cursor", "cursorPattern": "href=\"(?next=\\d+)\"", "cursorGroup": 1,
            "nextUrl": "{{cursor}}", "maxPage": 20 }                 // 文本通路：正则从 HTML 抓

// 终止条件三选一（也可组合）
"hasMore": "$.has_more"          // 布尔表达式取该路径值
"maxPage": 20                    // 硬上限
"emptyStop": true                // 返回空列表即停止
```

**终止判定优先级**：`hasMore` 为 false → 停；游标模式下游标为空 → 停；`page >= maxPage` → 停；`items.isEmpty()` 且 `emptyStop` → 停。

> 文本通路（声明了 `listSplit`，见 §5.4）没有 JSON 文档，所以 `hasMore` / `cursorFrom` 这类**路径表达式**无法求值；
> 但游标**可以**用 `cursorPattern` 正则从原始 HTML 里抓（见 §5.3.1），这正是 HTML 列表页「Next >」的做法。

**参数是怎么注入的（这一节是唯一口径，之前实现里只注入过 `page`，等于 `param/startAt/step/mode` 全部空转）**

| `mode` | 注入的变量名 | 值 | 例子 |
|---|---|---|---|
| `page` | `paging.param`（默认 `page`） | `startAt + (page-1) * step` | `startAt=1,step=1` → 第 3 页传 `3` |
| `offset` | `paging.param`（默认 `offset`） | `(page-1) * pageSize` | `pageSize=20` → 第 3 页传 `40` |
| `cursor` | 不注入 `param`；`{{cursor}}` / `{{page}}` 都可用 | 第 1 页空游标，第 N 页取第 N-1 页抓到的游标 | 见 §5.3.1 |

- `{{page}}` **永远可用**：若 `param` 就是 `page`，它拿到的是翻译后的值（`startAt=0` 的站点第一页就该是 `0`）；
  若参数名是别的（如 `offset`），`{{page}}` 仍是逻辑页码（第几页），两者可以同时用。
- `startAt` 决定"第一页的参数值"，`step` 决定每页跨度；这两个只对 `page` 模式有意义，
  `offset` 模式的跨度由 `pageSize`（管线默认 20）决定。
- 想在 URL 里换名字（`?p=3` 而不是 `?page=3`）：写 `"param": "p"`，不要自己拼。

#### 5.3.1 游标自动串联（2026-11 落地，HTML 列表页实测）

游标模式的三个字段：

| 字段 | 作用 |
|---|---|
| `cursorPattern` | 正则，从**原始响应文本**里抓下一页地址/游标；只在文本通路（HTML/XML）需要 |
| `cursorGroup` | 上面正则的取第几个捕获组，默认 1 |
| `nextUrl` | 第 2 页起的请求地址模板，必须含 `{{cursor}}`（例：`"{{cursor}}"` 表示"直接把抓到的地址当 URL"） |

`cursorFrom` 与 `cursorPattern` 二选一：前者是 JSON 路径（`$.next_cursor`），后者是正则（HTML 用）。

**串联规则（管线实例级，`SourcePipeline.cursorChain`）**

- 每个 `feed|params` 各自记一条 `页码 → 该页抓到的游标` 链，互不影响；切栏目再翻回来能接着走。
- 第 1 页：游标为空 → URL 走 `request.url` 原样。
- 第 N 页（N>1）：取第 N-1 页记录的游标 → 有 `nextUrl` 就整条覆盖 URL（`{{cursor}}` 展开），没有就按 `param` 注入。
- 游标取不到（链断了/上一页根本没有 Next）→ 本页返回空结果且 `hasMore=false`，**不会退回第一页**（否则等于无限翻第一页）。
- `maxPage` 仍是硬上限，作为"站点游标可无限跟随"的兜底。

> 为什么必须这么做：列表页的「Next >」是 `https://<站点>/?cursor=<id>` 这种**绝对游标地址**，
> 站点完全不认 `?page=`。早期配置写 `mode=page, maxPage=1` 的效果就是"永远只有第一页"，滚到底没有任何新条目。
> 实测链：`/` → `?next=4334919` → 下一屏给出 `?next=4334892`，每页 25 条；现在 `maxPage=20` 只是兜底。

`{{page}}` 变量由分页引擎注入，`<startAt>` 起步。

### 5.4 非 JSON 响应：`listSplit` 与 `strip`

"响应是标准 JSON"是绝大多数站点的前提，但两类线上站点会打破它：

| 形态 | 实例 | 需要的配置 |
|---|---|---|
| XML / Atom | 示例视频源 `feeds/videos.xml?channel_id=UC...` 返回 Atom 1.0，最多 15 条、倒序、无分页参数 | `responseType=text` + `listSplit` + 字段全用 `regex` |
| 非标准 JSON 外壳 | 会员视频示例源 `示例站点/详情接口` 返回 `VAR={...};` 这类外壳 | `strip` 剥掉前后缀，之后照常走 `root`/`list`/`jsonPath` |

**`listSplit`（文本分条）**

```jsonc
"responseType": "text",
"listSplit": "(?s)<entry>(.*?)</entry>",   // 命中一次 = 一条记录，正文作为 Payload.Text
"fields": {
  "id":    { "kind": "regex", "pattern": "<yt:videoId>(.*?)</yt:videoId>", "group": 1 },
  "title": { "kind": "regex", "pattern": "<title>(.*?)</title>", "group": 1 }
}
```

规则：

- 正则必须是**整条切分**：把条目整体包进捕获范围。只写 `"<entry>"` 会切出空片段。
- 声明 `listSplit` 后 `list` 被忽略；`responseType` 不是 `json`/`auto` 的列表资源**必须**声明它（E002），理由是纯文本上没有 JSON 文档，`list` 路径无从作用。
- 文本通路上没有 JSON 文档，`paging.hasMore` 这类路径表达式不可求值，分页只能靠 `maxPage` + 非空兜底。
- 带捕获组的 `regex` 字段必须显式 `"group": 1`；抽取器默认 `group=0`，拿到的是整段匹配（含标签）。

**`strip`（剥壳）**

```jsonc
"strip": { "prefix": "`VAR=`", "suffix": ";" }
```

执行顺序固定为 `trim → 定位 prefix（首次出现）→ 取其后 → 定位 suffix（其后首次出现）→ 取其前 → trim`。`prefix` 与 `suffix` 不能同时为空（E002）。

两个语义要说清：`prefix` / `suffix` 是**在整段响应里找锚点**，不是"必须贴着首尾"，所以既能剥 `VAR={…};` 这种外壳，也能从一整页 HTML 里把内嵌的 JSON 挖出来：

```jsonc
// 页面里 var ytInitialData = {...};</script> 后面还跟着几 MB HTML
"strip": { "prefix": "var ytInitialData = ", "suffix": ";</script>" },
"responseType": "json",
"list": "$..lockupViewModel"
```

锚点找不到就**原样保留整段响应**，由下游按"解析不出 JSON → 0 条"降级，而不是抛异常把页面打成错误页。

**5.4.1 锚点怎么选（实测踩过的三个坑）**

`listSplit` 取的是 `findAll` 的**整段匹配**，锚点必须写在匹配体内、且长到不会被同一记录里的其他字段命中：

| 坑 | 现象 | 原因与解药 |
|---|---|---|
| 锚点被二次命中 | 某频道页 14 条被切成 28 段，App 报"28 条因必填字段缺失被跳过、0 条可用" | 锚点写成 `id:"xxxx…`，而每条记录里还有 `cid:"xxxx…"`，`id:` 把 `cid:` 也命中了。解药：把结构符号带上，写 `\{id:"xxxx…",`，并在匹配体内重复一次锚点作为下一段的前瞻 |
| 字段是空串而非缺失 | 抓到 `playVid:""`、`title:""`，以为字段可用，实际每条只有首条真有值 | 空串是合法匹配。先按候选字段统计**命中率**（有值/总条数），再决定用哪个；本例真标题在 `sourceTitle`、副标题在 `subTitle` |
| 值藏在 URL 尾段 | 没有独立的主键字段，但跳转地址尾段 `/cover/<专辑 id>/<条目 id>.html` 里带着可播 id | 用 `regex` + `group` 从 URL 里取（`/cover/[0-9a-z]+/([a-z0-9]{6,})\\.html`），别新增字段类型 |

配套接口快照在 `samples/fixtures/`；验证切分是否命中，先跑 `tools/validate_config.py` 语法与引用校验，再用一条只打印"段数/可用条数"的临时脚本对快照复现。

两份可跑的样本：`samples/` 里的长视频示例配置（Atom 文本分条）、`samples/` 里的会员视频示例配置（外壳剥离 + 播放地址模板拼接），配套接口快照在 `samples/fixtures/`。

**5.4.2 纯 HTML 列表：锚点、lazyload、`group`（HTML 列表实测，2026-11）**

`samples/` 里的图集示例配置是第一个**整站 HTML、零 JSON 接口**的样本。它证明"文本通路 + regex"能撑起一个多栏目站点，
也把三件必须写进口径的事暴露出来：

**(1) 锚点要贴住"页面真实的行首"**。同一个站点不同页面的行首并不一样，一个锚点打不了三个页面：

| 页面 | 行首实际长什么样 | 可用的 `listSplit` |
|---|---|---|
| 首页 `/`、`/popular` | `<tr><td class="gl1c glcat">…</tr>` | `(?s)<tr><td class="gl1c glcat">.*?</tr>` |
| 画廊排行 `toplist.php?tl=11` | `<tr><td><p>#1</p><p>views</p></td><td class="gl1c glcat">…` | `(?s)<tr><td><p>#\d+</p>.*?</tr>` |
| 标签排行 `toplist.php?tl=31` | 一行里**并排两组** `<td class="dr">#1</td><td class="ds">score</td><td class="du"><a>name</a></td>` | 不能按 `<tr>` 切（一行两条），要按"一组三连 td"切：`(?s)<td class="dr">#\d+</td><td class="ds">[\d,]+</td><td class="du">.*?</td>` |

行内元素不会嵌套 `<tr>`，所以 `.*?</tr>` 这种懒匹配是安全的；但**只要一行里塞了多条记录，就必须把切分单位下沉到记录层**，
否则 100 条会被切成 50 条、字段还会互相串。

**(2) 缩略图是懒加载的，`src` 可能是占位 gif**。列表里 24/25 行是
`<img … src="data:image/gif;base64,R0lGOD…" data-src="https://img.example.com/…webp">`：
只抓 `src=` 会得到一堆 base64 占位图（渲染层看到的是"图能加载但全是 1px"）。两种都吃的写法是：

```jsonc
"poster": { "kind": "regex", "pattern": "(?s)class=\"thumb\".*?<img[^>]*?(?:data-src|src)=\"(https://[^\"]+)\"", "group": 1 }
```

`(?:data-src|src)` 加 `https://` 前缀的双保险：`src="data:…"` 那两个分支都过不了 `https://`，引擎回溯去试 `data-src`；
真有明文 `src` 的行（首行就是）也照常命中。

**(3) 带捕获组就必须写 `"group": 1`**。这条 §5.4 已经写过，但 HTML 样本里最容易犯：
`tags` 用的是 `type: stringList`，抽取器会取**所有命中**，一旦忘了 `group` 就变成
`["<div class=\"gt\" title=\"…\">one piece</div>", …]`——**列表页看起来正常，只是标签里混进了一堆 HTML**。

**分页口径（与站点一致，别猜）**：这类列表页的分页是 `?p=N`（**0 起**），所以 `paging` 要写
`{ "mode": "page", "param": "p", "startAt": 0, "step": 1 }`；而首页的「Next >」是**游标**（`?cursor=<id>`）、
`/popular` 的 Next 是禁用状态，两者都不吃 `?page=`，只能 `maxPage: 1`。
另外该站有匿名带宽配额，`maxPage` 不要往 200 写（样本里画像 20 页、标签 10 页）。

**详情页可以"降级"而不是报错**：标签排行条目的 id 是 `uploader/<name>`，同一个 `{{baseUrl}}/{{id}}/` 模板
既服务 `/g/<id>/<token>/` 也服务作者页；作者页没有 `#gn`，抽不到 title → `detail()` 返回 null →
详情页只剩卡片信息（不报错、不白屏）。这是**刻意保留**的行为，不是漏配。

### 5.5 `episodes` — 选集 / 多线路 / 图集

详情页的"一条记录"经常只是**一个合集**（一部剧、一张专辑、一套图）。`episodes` 声明怎么把这个合集展开成子项列表，渲染层按 `TrackGroup` 画成格子，每格带状态标。

三级结构，逐级取数：

```jsonc
"episodes": {
  "request": {                     // 可选：拿 id 表的请求；不填则从详情响应自身取
    "method": "POST",
    "url": "https://api.example.com/episodes",
    "headers": {
      "Content-Type": "text/plain",
      "Origin": "https://示例站点",
      "Referer": "https://示例站点/x/cover/{{id}}/",   // {{id}} 仍是条目 id
      "Cookie": "video_guid=<任意 GUID>"
    },
    "body": "{\"cid\":\"{{id}}\",\"vid\":\"\",…}",
    "bodyType": "json"
  },
  "strip": null,                   // 可选：与资源级 strip 同义
  "root": "$.data",                // 可选：id 表响应里的定位点
  "ids": "$.coverInfo.video_ids",   // 必填：id 数组路径
  "item": {                        // 必填：每条子项一次请求（结构同 ResourceSpec）
    "request": { "url": "https://示例站点/详情接口?vids={{episode}}&cid={{id}}…" },
    "responseType": "json",
    "strip": { "prefix": "`VAR=`", "suffix": ";" },
    "root": "$.vl.vi[0]",
    "fields": {
      "title": { "kind": "jsonPath", "from": "$.ti" },
      "duration": { "kind": "jsonPath", "from": "$.td", "type": "int" },
      "playUrl": { "kind": "template", "parts": ["{{$.ul.ui[0].url}}","{{$.fn}}","?vkey=","{{$.播放凭证字段}}"], "rewrite": [...] }
    }
  },
  "badges": [                      // 可选：标题关键词 → 状态标，顺序即优先级
    { "titleContains": "预告", "label": "预告", "state": "trailer" },
    { "titleContains": "彩蛋", "label": "花絮", "state": "extra" }
  ],
  "lockedPath": "$.播放凭证字段",         // 可选：该路径取不到 → 视为不可播
  "lockedLabel": "VIP",            // 不可播时的标签文案
  "groupName": "选集",             // 正片组的标题
  "maxItems": 48,                  // 最多取多少条
  "concurrency": 6                 // 逐条请求的并发数
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `ids` | ✅ | id 数组路径。**指到数组本身**（`$.coverInfo.video_ids`）或**指到元素**（`…video_ids[*]`）都能用，管线会把整个数组展平 |
| `item` | ✅ | 子项资源。`{{episode}}` = 当前 id，`{{id}}` = 条目 id（`专辑 id` 这类跨子项不变的参数靠它） |
| `request` | — | id 表与详情不在同一个接口时必须写（两段分别打两个接口的情形） |
| `badges` | — | 只靠标题就能判的类别；命中即给该标签 |
| `lockedPath` | — | 判"不可播"的唯一可靠手段：**路径取不到 = 不可播**（VIP / 需登录 / 已下架） |
| `concurrency` | — | 每个 id 一次请求是硬约束（见 §5.4 的死路清单），并发太低首屏会卡；默认 6，上限 16 |
| `cacheTtlMs` | — | 选集缓存有效期（毫秒），缺省 `21600000`（6 小时）。**播放地址带签名、有效期短的源必须调小**：示例短剧源的 `auth_key` 只活约 2 小时，配 6 小时等于"缓存命中即 403"，见 §5.5.4 |
| `lazyItems` | — | 图片源的懒取开关，默认 `false`。为 `true` 时详情页**不逐条请求内页**，只交出"第 N 张"的占位（`Track.pending` = id），查看器翻到哪张才解析哪张，见 §5.5.5 |

**状态标怎么算**（优先级从高到低）：

1. `badges` 命中标题 → 用该 `state`（`trailer` / `extra` …）
2. `lockedPath` 取不到 → `vip` + `lockedLabel`
3. 其余 → `free`

> **`state` 是字符串不是枚举**：站点口径会变（限免 / 会员 / 超前点播 / 用券），配置驱动的客户端不该为多一种状态改代码。渲染层对未知值做兜底配色。

**渲染结果**：管线按 `state` 拆成多个 `TrackGroup` —— 正片（`free`/`vip`）一组并按标题里的集号排序，`trailer` 与 `extra` 各自成组。理由见 §5.5.1。

**5.5.1 为什么必须拆组排序**

示例视频源 `video_ids` 的顺序**不等于集号**，而且混着三类东西。实测《示例剧集》（48 个 id）：

| id 段 | 内容 | 时长 |
|---|---|---|
| 1–5 | 正片 `_01`~`_05` | 1948 / 1734 / 1997 / 1944 / 1783 秒 |
| 6–22 | 预告片 `预告片_06`~`_22` | 80~99 秒 |
| 23–39 | 正片 `_06`~`_22` | — |
| 40 | 无标题、无 `播放凭证字段` | — |
| 41–43 | 预告片 | — |
| 44–48 | 彩蛋 1~5 | 50.7 / 40.1 / 75.7 / 71.9 / 47.2 秒，**全部无 `播放凭证字段`** |

全部塞进一个网格会出现**两个"第6集"**（一个预告、一个正片），用户无法分辨。拆成"选集 22 / 预告 20 / 花絮 5"三组后每格只在组内有意义，集号也不会重复。

**5.5.2 两条必须记住的实现约束**

| 约束 | 不遵守的后果 |
|---|---|
| 子项字段要走**完整变换链**（`trim → rewrite → prefix/suffix`），不能只调 `extractor` | CDN 返回的是 `http://<IP>/cdn.example.com/…`，靠 `rewrite` 去掉 IP 段；只取原始值会保留 IP，Media3 直接报 `P2007 Source error` |
| `ids` 求值结果要**展平一次** | `$.coverInfo.video_ids` 只回一个 `JsonArray`，按标量取会得到 0 个 id，选集静默消失 |

> **能力边界**：`lockedPath` 只能判"可播/不可播"。示例视频源对未登录请求的 VIP 集依然返回 `播放凭证字段`，所以"免费 vs 会员"没有稳定字段——目前只能显示 预告 / 花絮 / 不可播 三类，其余按免费处理。`coverInfo.downright`（免费集索引）实测会溢出（48 条数据里出现 48/57/61），不可用。

---

#### 5.5.3 缓存语义（无需配置，行为固定）

`episodes` 一旦存在，取数结果就会被缓存到平台持久化存储，**有效期 6 小时**（`EPISODES_CACHE_TTL_MS`）：

| 平台 | 位置 | 键 | 时间戳键 |
|---|---|---|---|
| Android | SharedPreferences `ump_episodes` | `源id\|条目id` | 同键 + `#ts` |
| 桌面端 | `java.util.prefs` 节点 `com/vi/ump/episodes`（8000 字符切片 `#0..#n-1`，片数 `#n`） | 同上 | 同键 + `#ts` |

- 这与"首页列表只在本次打开 App 生效"是两套语义：列表是会话内存缓存，选集是持久化缓存 + 6 小时 TTL。
- 过期判定：`now - ts > 6h`，或**根本没有 `#ts`**（老版本写入的）→ 当 miss 处理并删除整条。
  所以升级/改配置后最坏情况只是"第一次进详情重拉一次"（1~2 秒），不会长期被脏数据钉住。
- `maxItems` 直接决定缓存体积（48 条约 3~5KB），不设上限要留意 `java.util.prefs` 的 8K 单值限制（实现已切片规避）。
- 格式级变更（字段结构变了）仍要升 `EpisodeCacheCodec` 的 `v`：TTL 只解决"数据会旧"，解决不了"解不出来"。
- 有效期**可以按源收紧**：`episodes.cacheTtlMs` 缺省 6 小时，播放地址带签名且更短命的源必须调小（见 §5.5.4 (3)）。

#### 5.5.4 对象数组的 `ids`、一次性 POST、"签名只有 2 小时"（示例短剧源实测，2026-11）

`samples/` 里的短剧示例配置是第二份把选集走 `episodes` 通路的示例配置（第一份是同类的长视频示例配置）。
它把三个**此前只对示例视频源成立**的隐含前提摊开了，写在这里供后续源照抄：

**(1) `ids` 的元素是对象时，用 `[*].字段` 把标量取出来**

示例短剧源详情 `/api/videos/{id}` 一次给出整季：

```json
{"data":{"id":13624,"title":"缺了一条腿，却被彻底填满",
         "episodes":[{"ep_num":1,"title":"第1集","duration":116,"status":1}, …]}}
```

`episodes` 是**对象数组**，而 `buildEpisodeGroups` 的 `ids` 求值只认标量（`(it as? JsonPrimitive)?.content`）。
写 `"ids": "$.episodes[*]"` 会得到 **0 个 id → 选集静默消失**（页面不报错、也没有格子）：

```jsonc
"ids": "$.episodes[*].ep_num"   // ✅ 展平数组后逐元素取字段 → ["1","2","3",…]
// "ids": "$.episodes[*]"       // ❌ 元素是对象，全被丢弃
```

`[*]` 后接 `.字段` 是 JSONPath 子集的既有能力（§8.1），**不需要新增 `idField` 之类的字段**；
但这句话必须写在这里，否则每个"详情一次给全季"的源都会再踩一次"选集不见了，而且哪里都不报错"。

> 校验侧有个盲区：`episodes` 整块**没有 L3 校验**（`ConfigValidator` 与 `tools/validate_config.py` 都不检查它）。
> 所以 `ids` 写成对象路径不会被导入期拦下，只会在运行期得到 0 组。改完 `episodes` 请先按 §5.4.1 打一次"组数/条数"。

**(2) 每集一次 POST，body 只能是 `{}`**

```jsonc
"episodes": {
  "ids": "$.episodes[*].ep_num",
  "item": {
    "request": {
      "method": "POST",                                        // GET 这个路由是 404
      "url": "{{baseUrl}}/api/videos/{{id}}/episodes/{{episode}}/playback",
      "headers": { "Referer": "示例站点/video/{{id}}/", "Origin": "https://示例站点" },
      "body": "{}", "bodyType": "json"
    },
    "root": "$.data",
    "fields": {
      "title":   { "kind": "template", "parts": ["{{$.ep}}"], "prefix": "第", "suffix": "集" },
      "playUrl": { "kind": "jsonPath", "from": "$.streams.main" }
    }
  },
  "lockedPath": "$.streams.main",       // 取不到 = 不可播（下架/地区限制）
  "cacheTtlMs": 5400000                 // 90 分钟 < 签名有效期，见 (3)
}
```

三个实测坑：

| 坑 | 现象 | 解药 |
|---|---|---|
| body 里多带字段 | 一律 `{"status":0,"msg":"播放参数无效"}`，`data` 是空数组 | 只发 `{}`；集号只能走 URL，塞进 body 必然失败 |
| 想从响应里拼集名 | 该接口不返回集名，只回一个整数 `ep` | 用 `kind=template` + `parts:["{{$.ep}}"]` 配 `prefix/suffix` 生成"第N集" |
| 想用 `{{url}}` / `{{imageBase}}` 这类变量 | 子项字段的 `{{$.path}}` 只能来自当前子项 payload | 字段抽取带请求变量作用域（`{{baseUrl}}` / `{{id}}` / 页面 `params` 都可用），基址可以直接拼在 template 里；`prefix/suffix` 不走模板渲染 |

**(3) 签名地址的有效期比默认缓存短**

`streams.main` 是带 `auth_key=<ts>-…` 的 m3u8，`ts` 实测为"当前时间 + 约 2 小时"；而选集缓存默认 6 小时。
于是"上午进过详情、下午再进"命中缓存后点某集，播放器拿到的是过期地址 → `403/410` → `P2007`，
用户看到的是"播放器坏了"，而不是"缓存旧了"。

这就是 `episodes.cacheTtlMs` 存在的理由：**TTL 必须小于签发有效期**。
不配时保持 6 小时，老源行为完全不变（§16.3 第 1 条：可选字段 + 缺省即通用）。

**(4) 播放器侧不需要额外鉴权头（本源）**

播放响应里带了 `cdn: {authKey:"x-cdn-auth", authValue:"…", app:"150"}`，但实测 m3u8、`crypt.key`、`.ts`
三段**不带这两个头也全是 200**，且 m3u8 内部是标准 `EXT-X-KEY:METHOD=AES-128`（key 在另一个域名、签名自带）。
所以本源 `playback.strategy=direct`、`headers` 只留 `Referer/Origin/UA` 就够。
哪天站点把 `cdn.otherEnabled` 打开（分片也要鉴权），就落到 §10.1。

> **别把"视频不需要鉴权头"推广到图片**：同一站点的封面是 AES 密文，要靠 `runtime.imageDecrypt` 才出图（§3.2）。
> 这两件事互不相干——播放链路是"签名地址 + 明文 HLS"，图片链路是"无签名也可下载 + 密文载荷"。

#### 5.5.5 图片源的懒取：`lazyItems`（2026-11 落地，图集实测）

图集站点有个和视频源完全不同的成本结构：**每张图一个内页**。一本 60 页的图集，
按普通 `episodes` 写法进详情页就要打 60 次内页请求（`concurrency=6`，约 10 轮），
既慢又容易被站点当爬虫限流。

```jsonc
"episodes": {
  "request": { "method": "GET", "url": "{{baseUrl}}/{{id}}/?p={{p}}" },   // 只取 id 表
  "paging": { "mode": "page", "param": "p", "startAt": 0, "maxPage": 60, "emptyStop": true,
              "totalPattern": "Showing\\s+[\\d,]+\\s*-\\s*[\\d,]+\\s+of\\s+(\\d+)" }, // 见 §5.5.6
  "idsSplit": "href=\"https://gallery.example.org/(s/[0-9a-f]+/\\d+-\\d+)\"",  // 抓 "第 N 张" 的 id
  "item": { … },                                                          // 单张内页怎么取直链
  "maxItems": 1000,                                                       // 安全阀；省略也对，总数已知时自动放宽
  "concurrency": 6,
  "cacheTtlMs": 3600000,
  "lazyItems": true                                                       // ← 只加这一行
}
```

**打开后的行为（管线侧）**

- `buildEpisodeGroups` 走懒分支：**不请求 `item`**，直接按 id 表交出 `Track(index, title="第 N 张", url="", pending=id)`。
  详情页拿到的是 60 条"占位"，实测日志 `[detail] … tracks=60 lazy=60`。
- 新增公开入口 `resolvePendingTrack(detailKey, itemId, index, pendingId)`：按需解析**一张**内页，
  复用同一套 `fetchTrack`（trim/rewrite/prefix 全在），失败包 `runCatching` 返回 `null`，不抛给 UI。
- 缓存格式升到 `EpisodeCacheCodec` v2，多一个 `p`（pending）字段；老缓存 v1 解不出来会被当 miss 丢掉重拉。
- 校验：`lazyItems=true` 但 `episodes.item` 缺失 → `E002`（没有 `item` 就没有"怎么解析一张"，懒取无从谈起）。

**渲染侧配套（固定口径，不属于配置）**

- 查看器只保留"当前张 + 之后 N 张"在取址/解码（N = 用户设置「预加载张数」`image.preload`，默认 3、范围 0~8；
  例：N=3 时看第 1 张只取 2/3/4，看第 15 张只取 16/17/18），窗口外完全不渲染。**不往前预留**：
  回看靠已经取到的直链 + 图片缓存，翻回去不重新发取址请求（见 `docs/04 §8.7`）。
- 进度按 `image.progress.<源id>.<条目id>` 存"看到第几张"，返回再进直接落到那一张。

> 实测收益（60 页图集）：进详情从"60 次内页请求"降到"3 次 id 表请求"；进查看器按预加载窗口顺序解析。
> 455 页图集则是"0 次内页请求 + 23 次 id 表请求"，`tracks=455 lazy=455`。

#### 5.5.6 按"总数"抓全 id 表：`totalPattern` / `totalGroup` / `perPage`（2026-11 落地）

`paging.maxPage` 是**猜**出来的上限：写 3 就只翻 3 页（60 条），写 60 就每本都翻 60 页（15 页的图集白打 45 次请求）。
图集列表页通常自己就写着总数（列表页页脚 `Showing 21 - 40 of 455`），所以这一版让**页面上的总数决定翻几页**。

```jsonc
"paging": {
  "mode": "page", "param": "p", "startAt": 0, "step": 1,
  "emptyStop": true,                                                 // 只在"没抓到总数"时起作用
  "maxPage": 60,                                                     // 仍是硬上限，防配错时无限翻
  "totalPattern": "Showing\\s+[\\d,]+\\s*-\\s*[\\d,]+\\s+of\\s+(\\d+)", // 从第 1 页正文抓总数
  "totalGroup": 1                                                    // 可省略，默认就是 1
},
"idsSplit": "href=\"https://gallery.example.org/(s/[0-9a-f]+/\\d+-\\d+)\"",
"maxItems": 1000                                                     // 安全阀：可省略，抓到总数时自动放宽到总数
```

> **最小写法**：只要 `totalPattern` 一条，其余都能省——`totalGroup` 默认 1；`perPage` 不写就按第 1 页实际命中数
> （列表首屏必满，`perPage=20` 与"不写"等价）；`maxPage` 有默认值 20，图集超过 400 条才需要调大；
> `emptyStop` 默认 true，只服务"抓不到总数"的兜底路径。

**抓取顺序（`SourcePipeline.fetchIdPages`）**

1. 先单独抓第 1 页：一次请求同时完成"抓 id"和"读总数"（`extractTotal`）。
2. 有总数 → `pages = ceil(total / perPage)`（夹在 `[2, maxPage]`），其余页按 `episodes.concurrency` **分块并发**抓。
   空页/坏页直接丢弃，不会顶掉别的页。
3. 没总数（没配 `totalPattern` 或正则不中）→ 退回逐页 `emptyStop` 兜底：某页 0 个 id 就当到底。
4. 抓完按 id 表顺序拼成一条长列表，**不再做二次去重/排序**（图集顺序就是页码顺序）。

**口径与坑**

| 事 | 口径 |
|---|---|
| `perPage` 写不写 | **建议不写**：不写时用第 1 页命中数（首屏必满，与写死 20 等价）。写死只在"首屏可能不满"的站点有意义 |
| `maxItems` | 安全阀（防配错把上千条灌进内存），**不是分页上限**：抓到总数时自动放宽到 `max(maxItems, total)`，写小也不会再把条目砍回去 |
| 总数正则 | 必须带捕获组并用 `totalGroup` 指定；`[\d,]+` 是为兼容 `1,234` 这种千分位 |
| 末页不满 | 23 页 × 20 = 460 > 455，末页只给 15 条，属正常 |
| 成本 | 455 张 = 23 次 id 表请求；顺序翻实测 20 秒以上，6 路并发约 3 秒 |
| 诊断日志 | `[ids] total=455 perPage=20 pages=23 concurrency=6`，兜底路径打印 `[ids] total=unknown pages=N` |

> 这一版解决的问题：配置写死 `maxPage=3` + `maxItems=60`，而图集实际 455 张（23 个 id 表页），
> 于是网页 455、App 只显示 60。现在 `maxPage` 只当安全阀，页数由页面自己报的总数算；
> `maxItems` 同理——总数已知时若它比总数小，会自动放宽到总数，不会再切掉已抓全的 id 表（2026-11-18）。

## 6. `fields` — 字段映射（核心）

`fields` 是"外部数据 → 域模型"的桥梁。**键名必须是本文第 7 节规定的域字段名**（编译期校验，写错键名直接报错）。

### 6.1 两种写法

**简写**（仅需路径时）：

```jsonc
"fields": {
  "id": "$.vod_id",
  "title": "$.vod_name"
}
```

**完整**（需要变换时）：

```jsonc
"fields": {
  "poster": {
    "kind": "jsonPath",                    // 抽取器类型，默认 jsonPath
    "from": ["$.vod_pic", "$.vod_pic_slide"], // 候选路径：按顺序取第一个非空
    "prefix": "{{imageBase}}",             // 前缀（模板）
    "suffix": "",
    "type": "string",                      // string|int|float|bool|stringList|long
    "default": "",                          // 全空时兜底
    "trim": true
  }
}
```

### 6.2 `FieldSpec` 全字段

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `kind` | enum | `jsonPath` | 抽取器类型，见 §8 |
| `from` | string \| string[] | — | 抽取表达式；数组为候选列表，取第一个非空 |
| `pattern` | string | — | `kind=regex` 时的正则 |
| `group` | int | 0 | 正则捕获组序号。**带捕获组时必须写 `1`**：默认 `0` 取的是整段匹配，`<yt:videoId>dQw...</yt:videoId>` 会把标签一起带回来 |
| `levels` | object[] | — | `kind=delimited` 时的多级分隔定义 |
| `parts` | string[] | — | `kind=template` 时的子表达式列表 |
| `join` | string | `""` | `kind=template` 时的连接符 |
| `split` | string | — | `type=stringList` 时的切分符 |
| `map` | object | — | 值映射表，如 `{"1":"电影","2":"电视剧"}`；未命中走 `default` |
| `prefix` / `suffix` | string | `""` | 前后缀（支持 `{{}}` 模板） |
| `rewrite` | object[] | — | 正则替换链，顺序执行 |
| `type` | enum | `string` | 目标类型 |
| `default` | any | — | 抽取结果为空时的兜底值 |
| `required` | bool | false | 为 true 时，该条目缺此字段将被**跳过并计入失败数** |
| `trim` | bool | true | 去除首尾空白 |

`rewrite` 结构：

```jsonc
"rewrite": [
  { "pattern": "\\s+", "replacement": " " },
  { "pattern": "HD|高清", "replacement": "4K" }
]
```

### 6.3 类型转换规则

| `type` | 行为 | 失败时 |
|---|---|---|
| `string` | 转字符串，`trim` | 走 `default` |
| `int` | `trim` 后 `toIntOrNull`，**不做字符清洗**。`"12集"`、`"1186.92"` 这类带后缀或小数的值都会失败，需先用 `rewrite` 处理 | 走 `default` |
| `float` | `trim` 后 `toFloatOrNull`，同样不做清洗（`"8.9分"` 会失败） | 走 `default` |
| `bool` | `1`/`true`/`yes`/`是` → true；`0`/`false`/`no`/`否` → false | 其他值走 `default` |
| `stringList` | 按 `split` 切分并去空 | 空列表 |
| `long` | 时间戳/毫秒 | 走 `default` |

### 6.4 `categories` 与 `filters` 的特化结构

```jsonc
"categories": {
  "request": { "method": "GET", "url": "{{baseUrl}}/api/nav" },
  "root": "$.data",
  "list": "$[*]",
  "fields": {
    "id":   "$.cat_id",
    "name": "$.cat_name",
    "icon": "$.cat_icon"
  }
}
```

```jsonc
"filters": {
  "groups": [
    { "key": "area", "title": "地区", "values": [
        { "label": "全部", "value": "" },
        { "label": "大陆", "value": "1" },
        { "label": "港台", "value": "2" }
    ]},
    { "key": "year", "title": "年份", "valuesFrom": {
        "request": { "method": "GET", "url": "{{baseUrl}}/api/years" },
        "list": "$[*]",
        "value": "$",
        "label": "$"
    }}
  ]
}
```

`valuesFrom` 为动态候选（v1 可选实现，静态 `values` 优先保证）。

---

## 7. 域字段契约（App 只认这些名字）

> **这是"App 需要哪些数据"的定义**。配置的作用就是把任意外部字段映射到这里。
> 编译期会校验 `fields` 的键是否在本节列表中，未知名会被拒绝（防止拼错了却不生效）。
>
> App 支持三种媒体：**视频 `video` / 音频 `audio` / 图片 `image`**。
> 三者共用 `MediaSummary` 外壳，用 `kind` 区分；条目统一用 `TrackGroup/Track` 承载（见 7.3）。
> 不存在三套独立结构 —— 这是刻意的设计，否则配置作者要学三遍。

### 7.1 `MediaSummary`（列表条目）

| 字段 | 类型 | 必填 | 用途 |
|---|---|---|---|
| `id` | string | ✅ | 详情跳转主键 |
| `title` | string | ✅ | 卡片标题 |
| `kind` | string | — | 媒体类型：`video` / `audio` / `image`，缺省 `video`。**决定详情页走哪条渲染分支** |
| `poster` | string | — | 缩略图：视频海报 / 音频封面 / 图片缩略 |
| `imageUrl` | string | — | 图片类的大图（查看器加载用）；此时 `poster` 是缩略图 |
| `subtitle` | string | — | 卡片角标/副标题（如"更新至12集"） |
| `year` | int | — | 年份 |
| `area` | string | — | 地区 |
| `type` | string | — | 类型（电影/电视剧） |
| `score` | float | — | 评分 |
| `duration` | int \| long | — | 时长：`type=int` 按**秒**、`type=long` 按**毫秒**解释，统一转 `durationMs`。字符串带小数（示例视频源 `"1186.92"`）时 `int` 会转换失败，需先 `rewrite` 取整 |
| `tags` | stringList | — | 标签（可用于筛选展示） |
| `playUrl` | string | — | 直连播放地址（用于"action=play"快捷播放） |
| `trackCount` | int | — | 集数 / 曲数 / 图片张数，用于卡片角标 |
| `artist` | string | — | 音频：歌手（列表卡片副标题常用） |
| `album` | string | — | 音频：专辑 / 播放列表名 |

> `kind` 建议用常量抽取器固定：`"kind": { "kind": "const", "from": "audio" }`。
> 不同资源可以给不同 `kind`，但**一个资源内部必须一致**；
> 想要"视频+音频混排在一个列表"不是 v1 目标，请拆成两个 feed。

### 7.2 `MediaDetail`

继承 `MediaSummary` 全部字段，并增加：

| 字段 | 类型 | 用途 |
|---|---|---|
| `description` | string | 简介（行数可变，UI 支持展开） |
| `actors` | stringList | 演员 |
| `director` | string | 导演 |
| `trackGroups` | `TrackGroup[]` | 条目分组：视频线路 / 音频播放列表 / 图集（见 7.3） |
| `related` | `MediaSummary[]` | 相关推荐（可选，需二次请求） |

### 7.3 `TrackGroup` / `Track`（三媒体通用的条目容器）

```kotlin
TrackGroup(name: String, kind: MediaKind, tracks: List<Track>)
Track(index: Int, title: String, url: String, thumb: String?, durationMs: Long?)
```

三种媒体共用一套结构，差异只在"怎么排列"：

| 媒体 | 一个 Group 是 | 一个 Track 是 | 常用 mode |
|---|---|---|---|
| 视频 | 一条线路 | 一集 | `delimited` |
| 音频 | 一个专辑 / 播放列表 | 一首曲目（或一章有声书） | `delimited` |
| 图片 | 一个图集 | 一张图（`url` 大图、`thumb` 缩略） | `array` |

#### 形态 A：`delimited`（视频剧集、音频曲目）

```jsonc
"tracks": {
  "main": {
    "mode": "delimited",                // 可省略，默认即此
    "groupNames": {
      "from": "$.vod_play_from",        // "线路1$$$线路2"
      "split": "$$$"
    },
    "items": {
      "from": "$.vod_play_url",         // "第1集$url1#第2集$url2$$$第1集$url3#..."
      "groupSplit": "$$$",
      "itemSplit": "#",
      "pairSplit": "$"                  // 有它 → 每项"标题$地址"；无它 → 整串是地址，标题自动编号
    },
    "label": { "autoIndex": true, "prefix": "第", "suffix": "集" }
  }
}
```

> 分隔符是这类接口最常见的踩坑点：必须与接口字面**完全一致**（CMS 系接口常见 3 个 `$`）。
> 引擎按字面匹配，不做"宽松匹配"，写错会 0 命中并报 `E011 分隔符未命中`。
>
> **切分顺序固定为 `groupSplit → itemSplit → pairSplit`（由粗到细）**。
> 顺序不能颠倒：`pairSplit` 是 `$`，若先按它切，`$$$` 会被切成三段空串，线路结构直接崩掉。

#### 形态 B：`array`（图片集最常用）

```jsonc
"tracks": {
  "photos": {
    "mode": "array",
    "from": "$.photos[*]",
    "fields": {
      "title": "$.title",
      "url":   { "kind": "jsonPath", "from": "$.url",   "prefix": "{{imageBase}}" },
      "thumb": { "kind": "jsonPath", "from": "$.thumb", "prefix": "{{imageBase}}" }
    }
  }
}
```

- `fields` 的键限定为 `title` / `url` / `thumb` / `duration`，其中 **`url` 必填**。
- 值可以是路径字符串，也可以是 `FieldSpec` 对象（要拼 `imageBase` 或转数值时用）。
- `title` 缺省时按 `label` 自动编号（"第N张"）。
- 图片不需要播放，所以 `playback` 对它不起作用，`playUrl` 对图片是可选的。

#### 形态 A/B 之外：`layout.tracks` 与 `detail.episodes` 并存

`detail` 资源里可以同时提供 `layout.tracks`（引用 `resources.tracks` 的键）与 `episodes`（逐条 `详情接口` 这类"二次请求"通路）。两条通路**并列**，不互相覆盖：

```
detail(key, id, extra, trackKey)
  → fetchEpisodeGroups(...)           // episodes 通路：id 表 → 逐条取数（示例视频源选集/预告/花絮）
  → buildSpecTrackGroups(trackKey, ..) // tracks 通路：按 TrackSpec 切分 payload
  → 两批 TrackGroup 依次拼进 MediaDetail.trackGroups
```

只有 `episodes` 时行为与本轮之前完全一致；只有 `layout.tracks` 时走纯配置切分；两者都给就得到"配置切分组 + 二次请求组"两段，UI 上表现为同一行的多个 chip。切 chip 只切**视图**，不重新请求（数据早在 detail 那一次响应里就齐了）。

### 7.3.1 实现契约（M4.1 / M4.2 落地后固定下来的细节）

这四条容易被配置作者忽略，但都是"写对了才出数据"的硬约束：

1. **`pairSplit` 默认是 `null`，不是 `"$"`。** 不写时每一项整串当地址、标题按 `label` 自动编号；要"标题$地址"必须显式配 `"pairSplit": "$"`。
2. **`groupSplit` 只在第一个分隔符处切一次。** 引擎用 `indexOf` 而不是 `split` 全切：`"线路1$$$线路2$$$线路3"` 的语义是"两组/三组"，而 `$` 结尾的空段会被 `split` 切出一堆空串，组数就错了。
3. **`array` 的 `prefix` / `suffix` 会走模板渲染。** `"prefix": "{{imageBase}}"` 里的 `{{...}}` 在取值时按当前变量作用域渲染；不渲染就会把字面量 `{{imageBase}}` 当域名拼进 URL——地址全 404，而且不报错。写 `prefix` 时按 `template` 抽取器的规则理解它。
4. **`from` 是相对 detail root 的路径。** `detail` 的 `root` 已经把响应裁到条目节点，所以 `tracks` 里应写 `"$.play_url"` 而不是 `"$.data.play_url"`；写成完整路径只会 0 命中。
5. **`array` 形态的 `from` 是"条目数组"，不是单条。** `"from": "$.images[*]"` 配 `fields` 一起用：`fields` 里每条只写"相对这一条"的 `$.xxx`。写 `"$.images"`（少了 `[*]`）会得到 1 条（整个数组当一条）。

### 7.3.2 图片源的写法（M4.6 落地后固定下来的细节）

`samples/` 里的壁纸示例配置是第一个示例图片配置（壁纸示例源每日壁纸），套路是**同一张图取两个尺寸**：

```jsonc
// 无封面大图、无版权方接口时，用 const 把站内跳转/封面固定下来
"fields": { "id": {...}, "title": {...}, "poster": {"kind": "template", "parts": ["{{baseUrl}}", "{{$.urlbase}}", "_400x240.jpg"]} }

"tracks": {
  "mode": "array",
  "from": "$.images[*]",
  "prefix": "{{baseUrl}}",
  "fields": {
    "url":   {"kind": "const", "from": "{{$.urlbase}}_1920x1080.jpg"},  // 原图
    "thumb": {"kind": "const", "from": "{{$.urlbase}}_400x240.jpg"}     // 缩略
  }
}
```

三条硬约束：

| 约束 | 说明 |
|---|---|
| 尺寸后缀必须站点真支持 | 每日壁纸接口的 `_1920x1080.jpg` / `_400x240.jpg` 是 200；`_480x270.jpg` / `_240x135.jpg` **404**。写错不报错，只表现为"卡片空白" |
| `tracks` 的 `url` 才进查看器 | `gallery_grid` 显示的是 `thumb`，点开后 `image_viewer` 取的是 `url`；两处都配齐才不会"点进去先白屏再出图" |
| 每条的 `title` 要有值 | 查看器顶栏用的是索引当前项的 `title`；缺了就只剩页码 |

### 7.3.3 音频源的写法（M4.5 落地后固定下来的细节）

`samples/` 里的音乐示例配置是第一个示例音频配置（公开的两级接口），套路是**两级接口 + 一条"必填字段当过滤器"**：

```jsonc
// 一级：列表里只有专辑，没有播放地址
"feeds": { "albums": {
  "request": { "url": "{{baseUrl}}/search?term={{p.q}}&media=music&entity=album&limit=20" },
  "list": "$.results[*]",
  "fields": { "id": "$.collectionId", "title": "$.collectionName", "subtitle": "$.artistName",
              "kind": { "kind": "const", "from": "audio" },
              "poster": { "kind": "jsonPath", "from": "$.artworkUrl100",
                          "rewrite": [{ "pattern": "100x100bb", "replacement": "600x600bb" }] } }
} }

// 二级：按专辑 id 拉全部曲目，响应第 0 条是专辑本身，其余才是曲目
"details": { "album": {
  "request": { "url": "{{baseUrl}}/lookup?id={{id}}&entity=song&limit=50" },
  "root": "$", "fields": { "id": "$.results[0].collectionId", ... }
} }
"tracks": { "songs": {
  "mode": "array", "from": "$.results[*]",
  "fields": { "title": { "from": "$.trackName", "required": true },
              "url":   { "from": "$.previewUrl", "required": true } }
} }
```

三条要点：

| 要点 | 原因 |
|---|---|
| 用 `required` 的 `url` 当过滤器 | 响应第 0 条是专辑元数据、没有 `previewUrl`；`array` 模式**url 为空即跳过该条**，所以不用写过滤表达式（v1 也不支持 `[?()]`） |
| 图片尺寸改成 `rewrite`，不是拼后缀 | 该接口的 `artworkUrl100` 形如 `…/100x100bb.jpg`，尺寸在路径中间：`_600x600.jpg` 拼不上，只能正则替换 `100x100bb` → `600x600bb` |
| 数字 id 可以直连做主键 | `JsonPrimitive.content` 保留原始文本（`"6781079346"`），不会变成 `6.78E9` 那种科学计数法；`MediaSummary.id` 是 String 也不丢精度 |

> 这类"列表条目与详情条目字段不同"的站点很常见（专辑/曲目、剧集/分集），
> 都推荐用 `required` 字段做隐式过滤，而不是给引擎加过滤语法。

### 7.4 `PagedResult<T>`

```kotlin
PagedResult(items: List<T>, page: Int, hasMore: Boolean, total: Int?, raw: Any?)
```

### 7.5 `FilterGroup` / `FilterValue`

```kotlin
FilterGroup(key: String, title: String, values: List<FilterValue>)
FilterValue(label: String, value: String)
```

---

## 8. 抽取器（Extractor）类型

| kind | 输入 | 表达式形态 | 适用 |
|---|---|---|---|
| `jsonPath` | JSON 树 | `$.data.items[*].vod_name` | 结构化接口（90% 场景） |
| `regex` | 文本 / HTML | `pattern` + `group` | 地址藏在任意文本中 |
| `css` | HTML | `css:` 选择器 + `attr` | 网页抓取 |
| `delimited` | 文本 | `levels` 多级切分 | 分隔符串 |
| `template` | JSON payload + 变量作用域 | `parts` 里的 `{{...}}` 片段 + `join` | 拼接多字段成 URL（示例视频源播放地址） |
| `const` | — | `from` 即常量字面量 | 固定值 |

### 8.1 JSONPath 支持子集（v1 明确边界）

```
$                  根
.name / .*         子节点 / 所有子节点
[0] / [-1]         索引（负数为倒数）
[*]                数组全部
..name             递归下降
['a b']            带空格/特殊字符的键
```

**不支持**（v1）：过滤器 `[?(@.x>1)]`、切片 `[1:3]`、函数 `length()`。

> 边界清楚的坏处是能力有限，好处是实现可靠、错误可定位。
> 实测覆盖 95% 的影视接口；真需要过滤时用"列表全取 + 字段级默认值 + `required` 跳过"间接实现。

### 8.2 `regex`

```jsonc
{ "kind": "regex", "from": "$.vod_content", "pattern": "https?://[^\"'<> ]+", "group": 0 }
```

- `from` 为空时，在**当前 payload** 上整段匹配：JSON 通路是 `root` 定位后的响应体，文本通路是 `listSplit` 切出来的**那一条**（见 §5.4）。所以文本通路通常不写 `from`。
- `group` 决定取哪个捕获组：默认 `0` 是整段匹配。**写了捕获组就必须把 `group` 改成 `1`**，否则 `<yt:videoId>(.*?)</yt:videoId>` 会连同标签一起返回。
- 匹配失败 → `default`；`required=true` → 条目跳过。
- 该抽取器对 JSON 与纯文本都有效（JSON payload 会先转成字符串），是文本通路的主力。

### 8.3 `css`

```jsonc
{ "kind": "css", "from": "$", "selector": "div.list a.item", "attr": "href" }
```

- `attr` 省略则取 `textContent`。
- 依赖 `expect HtmlParser`，Android 用 Jsoup。

### 8.4 `delimited`

```jsonc
{
  "kind": "delimited",
  "from": "$.vod_play_url",
  "levels": [
    { "split": "$$$" },
    { "split": "#" },
    { "split": "$", "take": 1 }
  ]
}
```

逐级切分，最后一级 `take` 取某个下标。

### 8.5 `template`

```jsonc
{
  "kind": "template",
  "parts": ["{{$.ul.ui[0].url}}", "{{$.fn}}", "?vkey=", "{{$.播放凭证字段}}"],
  "join": ""
}
```

两类占位符，都写成 `{{...}}`：

| 写法 | 取值来源 | 用于 |
|---|---|---|
| `{{$.path}}` | 当前 JSON payload（`root` 定位后） | 把两个 JSON 字段拼成一个 URL |
| `{{name}}` | 变量作用域（`baseUrl`、`imageBase`、`page`、`pageSize`…） | 拼基址、页码 |

- 不带 `{{}}` 的片段按**字面量**原样拼接（`"$.vod_id"` 这样写不会求值，只是字面文本）。
- `join` 是片段之间的连接符，默认空串。
- 文本通路上 payload 不是 JSON，`{{$.path}}` 解析为空；需要"从文本里拼"请改用 `regex`。

> 这就是会员视频示例源的播放地址：`ul.ui[0].url` + `fn` + `?vkey=` + `播放凭证字段`，四段拼一条，见 `samples/` 里的会员视频示例配置。

---

## 9. `pages` — 页面结构

### 9.1 结构

> **`title` 现在在首页不再渲染**（M4 收尾调整）：首页的画布不画 `layout.title ?: page.title` 这行大标题。
> 理由：数据源名已经在顶栏右侧的下拉里（那是"当前在哪"的唯一入口），栏目名已经在栏目 chips 里，
> 再顶一行"会员视频示例源"既是重复信息又白占一段竖向空间（用户明确要求省掉）。
> 配置里的 `title` 仍然保留语义（页面名 / 以后给无顶栏场景用），渲染层由 `UmpPage(showTitle=)` 控制，默认 `true`。

```jsonc
"pages": [
  {
    "id": "home",                      // 必填，唯一
    "title": "首页",
    "layout": { "type": "tabs", "position": "top" },   // 页面级容器布局
    "children": [                                      // tabs 的子页
      {
        "id": "home-hot",
        "title": "推荐",
        "layout": { "type": "banner_hero" },
        "feed": "hotList",             // 引用 resources.feeds 的 key
        "params": { "category": "1" },  // 注入请求的页面参数 {{p.category}}
        "card": { ... }                // 条目渲染模板
      },
      {
        "id": "home-movie",
        "title": "电影",
        "layout": { "type": "poster_grid", "columns": 3, "ratio": "2:3" },
        "feed": "hotList",
        "params": { "category": "2" }
      },
      {
        "id": "home-rec",
        "title": "为你推荐",
        "sections": [                   // 一个 Tab 内多个区块
          { "layout": { "type": "row_cards", "title": "最近更新" }, "feed": "hotList", "params": {"category":"3"} },
          { "layout": { "type": "poster_grid", "columns": 3 }, "feed": "hotList", "params": {"category":"4"} }
        ]
      }
    ]
  }
]
```

### 9.2 布局类型（Layout Palette v1）

| type | 参数 | 说明 | slot |
|---|---|---|---|
| `tabs` | `position: top\|bottom` | 容器，渲染子页 Tab | children |
| `sections` | `gap` | 纵向堆叠多个区块（一个 Tab 内多分组） | sections |
| `banner_hero` | `heightRatio`, `autoplay`, `intervalMs` | 顶部大图轮播 | card |
| `poster_grid` | `columns`, `ratio`, `gap` | 海报网格（分页列表） | card |
| `row_cards` | `title`, `showMore` | 横向滑动卡片行 | card |
| `list_linear` | `showIndex`, `divider` | 竖向列表（资讯/历史） | card |
| `category_filter` | `layout: inline\|dropdown` | 筛选条 + 结果墙 | filters + feed |
| `detail` | `expandLines` | 详情语义布局（固定结构） | fields |
| `track_panel` | `columns`, `groupTabs`, `showIndex` | 多组条目面板：视频=线路+选集，音频=播放列表 | trackGroups |
| `gallery_grid` | `columns`, `gap`, `showIndex` | 图集网格（详情页内嵌）。**M4.6 落地口径**：整格可点，点哪张就进 `image_viewer` 并停在那一页；`columns` 生效，`gap`/`showIndex` 先用固定值 | trackGroups |
| `image_viewer` | `zoom`, `swipe`, `showCounter` | 全屏看图：双指缩放、左右翻页、页码。**M4.6 落地口径**：缩放 1x–4x 写死（`zoom` 仅作开关预留）；翻页由 `HorizontalPager` 承担，`beyondViewportPageCount=1` 就是"前后各预加载 1 张"；`showCounter` 管右上角 `N / M` | trackGroups |
| `video_player` | `aspect`, `autoNext` | 视频播放页。**2026-11-20 口径**：手机/桌面把 `detail` 与播放器合并成 `ContentRoute`，不再单独路由到这条页面的 UI；该 layout 仍保留给 TV/未来端，`autoNext` 运行期已由用户设置 `player.autoNext` 取代 | — |
| `audio_player` | `showCover`, `showPlaylist` | 音频播放页：封面 + 进度 + 播放列表。**M4.5 落地口径**：两个参数都已接线（取自 `pages` 里 `audio_player` 那一条的 layout）；页面结构固定为"封面 → 标题/歌手 → 进度 → ±seekStepSec → 倍速 → 播放列表"，不按 `columns` 之类再排版 | trackGroups |
| `search` | `debounceMs`, `historySize` | 搜索页 | feed |
| `tabs_grid` | `columns` | Tab 内再嵌网格（复合） | children |

> 新增布局 = 加一个枚举 + 一个 Composable。**不允许配置拼装任意控件树**（见 01 §3 取舍一）。
>
> ⚠️ **`layout` 是严格 Schema**：写了没实现的键会直接 `[E002] unknown key` 让整份配置加载失败（不是静默忽略）。
> 所以本文列出的参数必须先落进 `core:model` 的 `LayoutSpec`——`gallery_grid`/`image_viewer`/`audio_player` 的那几个参数
> 就是在 M4.5/M4.6 落地时才补进去的（此前文档承诺了、代码不认）。

### 9.3 `card` — 条目模板

```jsonc
"card": {
  "style": "poster",        // poster | wide | compact | text
  "image": "poster",        // 取 MediaSummary 的哪个字段作图
  "title": "title",
  "badge": "subtitle",      // 右上角角标
  "subtitle": "year",       // 底部副标题
  "score": "score",         // 显示评分（null 则不显示）
  "ratio": "2:3",
  "action": { "type": "detail" }
}
```

`action` 类型：

| type | 行为 |
|---|---|
| `detail` | 用 `item.id` 打开详情页 |
| `play` | 用 `item.playUrl` 直接起播 |
| `link` | 打开外部浏览器，`url` 支持 `{{item.xxx}}` |
| `none` | 无点击（纯展示） |

### 9.4 语义页面

`detail` 页不需要在 `pages` 里描述"长什么样"，因为它的结构是产品固定的。
它只指定"用哪个资源"，**内部再按 `kind` 自动分支**：

| kind | 详情页呈现 |
|---|---|
| `video` | 海报 + 元信息 + 简介 + 线路 Tab + 选集网格 + 相关推荐 |
| `audio` | 封面 + 歌手/专辑 + 简介 + 播放列表（点击即播，不跳播放页） |
| `image` | 封面 + 简介 + 图集网格（点击进 `image_viewer`） |

```jsonc
{
  "id": "detail",
  "layout": { "type": "detail", "resource": "detailMain", "tracks": "epMain" },
  "showRelated": true
}
```

### 9.5 `channels` — 栏目切换（同一页面切多个取数入口）

一个页面在视觉上是**同一个网格/列表**，只是数据从不同 feed 来——这就是网站的"频道 Tab"。栏目只描述**取数入口**，不引入新的布局体系：

```jsonc
{
  "id": "home",
  "title": "会员视频示例源",
  "layout": { "type": "poster_grid", "columns": 3, "gap": 12, "ratio": "3:4" },
  "feed": "homeFeed",                     // 默认栏目；channels 缺省时也用它
  "viewModes": ["poster_grid", "list_linear"], // 允许用户切换的视图，见 9.6
  "channels": [
    { "id": "choice", "title": "首页",   "feed": "homeFeed" },
    { "id": "tv",     "title": "电视剧", "feed": "channelTv"    },
    { "id": "movie",  "title": "电影",   "feed": "channelMovie" },
    // 栏目可单独覆盖 params / layout / card，不填则继承页面级
    { "id": "hot", "title": "热播", "feed": "channelTv", "params": { "page": "2" },
      "layout": { "type": "list_linear" }, "card": { "style": "compact" } }
  ]
}
```

`ChannelSpec` 字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 栏目标识，页面内唯一（重复报 E002）；**不参与缓存键**——缓存键是 `feed\|params`（见 `docs/04 §7.4.1`），两个栏目共用同一 `feed` 且参数相同时会共用缓存 |
| `title` | 是 | 栏目条上的文字 |
| `feed` | 是 | 引用 `resources.feeds` 里的 key |
| `params` | 否 | 覆盖页面级 `params`，同时参与缓存键与分页参数（`{{p.category}}` 一类） |
| `layout` | 否 | 覆盖页面级 `layout`（**不允许 `detail`**） |

> **每个栏目各自持有分页状态**（M4 收尾补）：列表的会话缓存按 `feed|params` 分键，键里带 `page`
> —— 切到别的栏目再切回来，会接着上次翻到的页继续（不是回到第 1 页重来），
> 也不会把已经加载过的页重复追加。实现见 `docs/04 §7.4.1`。
| `card` | 否 | 覆盖页面级 `card` |

校验规则（`core:config` 与 `tools/validate_config.py` 互为交叉验证）：

| 情形 | 错误码 |
|---|---|
| `channels[i].id` 为空或页内重复 | E002 |
| `channels[i].feed` 不在 `resources.feeds` 里 | E005 |
| `channels[i].layout.type == detail` | E003 |
| `channels` 与 `children` 同时出现 | E003 |

**不做**的事（边界）：

- 栏目不承载"每栏目一套卡片模板链"——那是 `sections` + 多 feed 的职责；
- **栏目之间不共享分页进度**：每个栏目按 `feed|params` 各自记页码，切走再回来接着翻（见 §9.5 的说明）；
  但栏目不单独落盘，进程结束就清 —— 想长期记住要另做持久化；
- 栏目不做顺序/权限声明，数组顺序即 Tab 顺序。

**已知实现细节（2026-11-14 校对）**：页面级 `viewModes` 非空时，**栏目的 `layout` 覆盖不生效**——
`HomeRoute` 的 `viewMode` 初值取 `viewModes[0]`，随后 `renderPage = page.copy(layout = baseLayout.copy(type = viewMode))`
把它盖回去（`docs/04 §7.4.1`）。所以"某个栏目单独换排版"目前做不到：
要么接受全页同一种视图，要么把那个 feed 拆成独立页面。配置里写 `channels[].layout` 仍然合法（校验期不报错），
只是不会体现在首屏——这是**行为与配置不一致**的地方，记在这里避免下次当成 bug 排查一遍。

### 9.6 `viewModes` — 同一份数据的多种视图

`viewModes` 让用户在**不改数据**的前提下换排版（网格 ↔ 列表 ↔ 大图），值是 `LayoutType`：

```jsonc
"viewModes": ["poster_grid", "list_linear"]
```

- 必须是列表型布局（`poster_grid` / `list_linear` / `row_cards` / `banner_hero`），写 `detail` 报 E003；
- 第一项是默认视图；切换只替换 `LayoutSpec.type`，`card` 与数据沿用当前栏目；
- 不填等于只有 `pages[].layout.type` 一种视图。

> 把"栏目"和"视图"拆成两个字段，是因为二者正交：栏目是**数据维度**（换什么内容），视图是**呈现维度**（怎么排）。App 用 `cacheKey = feed|params` 复用已取到的数据，换视图不会重新发请求（见 04 §9.4）。

---

## 10. `playback` — 播放策略

```jsonc
"playback": {
  "strategy": "direct",                     // direct | resolver
  "headers": {                              // 播放请求头（很多 CDN 校验 Referer）
    "Referer": "{{baseUrl}}/",
    "User-Agent": "Mozilla/5.0"
  },
  "resolver": {                             // strategy=resolver 时必填（**当前未实现**，见 10.2）
    "request": {
      "method": "GET",
      "url": "https://parse.example.com/api?url={{url|raw}}"   // {{url|raw}} 语法也未实现（§4.2）
    },
    "extract": { "from": "$.url" },
    "required": true
  },
  "player": {
    "autoNext": true,                       // 作者建议值；**运行期不生效**——是否连播由用户的全局开关决定，默认关（见 §10.3）
    "aspect": "fit",                        // fit | fill | crop
    "seekStepSec": 10,
    "speedLevels": [0.5, 0.75, 1.0, 1.25, 1.5, 2.0],
    "rememberPosition": true
  },
  "web": false,                             // true = `playUrl` 是网页地址，交给内嵌浏览器播（见 §10.4）
  "audio": {                                // 仅音频条目生效
    "backgroundPlay": true,                 // 作者建议值；运行期以手机设置「播放 → 音乐后台播放」为准，默认开（2026-11-18）
    "mode": "loop",                         // sequential | loop | shuffle
    "showNotification": true                // 通知栏 / 锁屏控制
  }
}
```

**`playback` 只对 `kind=video|audio` 生效**：

- 图片（`kind=image`）不走播放器，`playback` 整块被忽略；
- 音频取 `audio` 子块，视频忽略它；
- `aspect` / `seekStepSec` 是视频语汇；音频只用 `speedLevels`（播客 1.2 / 1.5 倍速是刚需）。

**两段式解析的意义**：直链（`m3u8`/`mp4`）→ 直接喂播放器；
需要二次解析的站点 → 请求解析接口拿到真实地址再喂。配置层面对用户是同一个"选集"操作，差异被 `strategy` 吸收。

### 10.1 播放鉴权头来自"响应"时怎么办（待实现）

`playback.headers` 是**静态模板**，只能写字面量或引用 `{{baseUrl}}` 这类全局变量。
有一类站点把播放鉴权信息放在**播放接口的响应里**（示例短剧源的 `cdn: {authKey:"x-cdn-auth", authValue:"…", app:"150"}`），
静态头表达不了。现状与边界：

| 现状 / 口径 | 说明 |
|---|---|
| 示例短剧源当前不需要它 | 实测 m3u8 / `crypt.key` / `.ts` 不带 `x-cdn-auth` 也是 200，所以样本里没写这两行 |
| 真要支持时的最小改动 | `ResolverSpec` 增加 `headersFrom: Map<String, String>`（头名 → 响应里的 JSONPath），解析结果与 `playback.headers` 合并（后者优先）；同时 resolver 的 `request` 必须支持 `method` / `body` / `bodyType` 与 `{{url\|raw}}`（子项类播放接口是 POST） |
| 不做的事 | 不在配置里放 JS 或签名算法（`docs/01 §3` 取舍一）。需要客户端自签的源只能走"用户自建解析接口" |
| 能力不足时怎么表现 | 播不了就"可解释地播不了"：`PlayerCapabilities` / `W003` 说明原因，不允许白屏（§13 的阻断/警告分工） |

### 10.2 现状：`strategy=resolver` 整条链路都还只是契约

`strategy` 字段能解析、能校验（`strategy=resolver` 却没写 `resolver` 会报 `[E002] playback.resolver`），
但**运行期没有接**：`PlayTarget.url` 直接喂播放器（`docs/02` F6.2 仍是待办）。
现有示例源都不需要它——一条由 `详情接口` 直接拼出直链，另一条在 `episodes.item` 里就把 m3u8 写进了 `Track.url`。
`resolver.request` 目前也没接 `method`/`body`/`bodyType`，`{{url|raw}}` 语法未实现（§4.2）。
也就是说：**校验能过，但配置不生效**（`resolver` 被解析进模型后没人读）。上表那三件缺口补齐之前，
不要按"能用"来设计源配置；本文前面的 `parse.example.com` 就是这种"写作示例"。

### 10.3 `player.autoNext` 不是配置项，是用户开关

播完要不要自动接**同组下一集 / 下一首**，自 2026-11-16 起完全由用户决定：

| 项 | 口径 |
|---|---|
| 开关位置 | 播放页状态行右侧（"时长 0:33 · 播放中" 那一行的右边）「连续播放」 |
| 默认 | **关**（用户口径："默认不自动连续播放"） |
| 存哪 | 客户端本地设置 `SettingsKeys.PLAYER_AUTO_NEXT`（键名 `player.autoNext`，`"1"` 开 / 其它关），不是站点配置文件 |
| 作用域 | **全局**：对所有源、所有条目生效；视频页与音频页共用同一个值；换源、换集、杀进程重开都保持 |
| 配置里的 `playback.player.autoNext` | 仍能解析、能校验，但**运行期不读**——它是站点作者的建议值，用户的习惯优先 |
| 连播范围不变 | 只接**同组**下一集，不跨组（正片接预告是错的，跨组必须用户显式选） |

为什么不放在配置里：连播是"使用习惯"而不是"站点规则"。同一份配置在不同用户手里，有人爱连着看、
有人只想看一集就停；写在配置里等于让作者替用户决定，而且换一个源就变了。所以它跟"上次选的源"
一样落本地设置、由用户在界面上控制。

### 10.4 `playback.web` — 网页播放

有些站点的直链**必须由站点自己的播放器取**：YouTube 的 `streamingData` 地址带 `n` 参数签名，
还叠加 PoToken，浏览器外拿到的地址放几秒就 403。这类源只能让"网页"来播。

| 项目 | 口径 |
|---|---|
| 写法 | `"playback": { "web": true }`，`playUrl` 填**网页地址**（如 `{{baseUrl}}/watch?v={{id}}`） |
| 客户端行为 | 内容页跳过详情请求与播放器创建，把地址交给内嵌浏览器；控件、清晰度、字幕全由站点提供 |
| 不支持网页播放的端 | 桌面（未接内嵌浏览器）、TV 4.4（系统 WebView 版本过低）→ 页面直接写明原因，不黑屏 |
| 不生效的东西 | `headers`（浏览器自己带）、`seekStepSec`/`speedLevels`/`rememberPosition`（进度存在站点账号里）、`resolver` |
| 已知代价 | 首次加载比直链慢 1~3 秒；站点自己的"打开应用"横幅会出现；画面按站点给的页面比例，不保证 16:9 铺满 |

判断标准很简单：**能拿到的 `playUrl` 是 `.mp4`/`.m3u8` 就走默认（ExoPlayer），是网页就开 `web`。**

---

## 11. `theme` — 主题

```jsonc
"theme": {
  "mode": "dark",              // dark | light | system
  "primary": "#FF6B35",
  "background": "#0F0F13",
  "surface": "#17171C",
  "onSurface": "#E6E6EA",
  "accent": "#3DDC97",
  "cornerRadius": 12,
  "density": "comfortable",    // compact | comfortable
  "posterColumns": 3,          // 全局默认列数，可被单个 layout 覆盖
  "fontScale": 1.0             // 0.85 – 1.3
}
```

**约束**：颜色必须 `#RRGGBB` 或 `#AARRGGBB`；`cornerRadius` 0–32；`fontScale` 0.85–1.3（越界钳制而非报错，保证可用性）。

`primary` 同时是**源标识色**的来源之一：`meta.color` 没写、但这里显式配了（不是默认 `#FF6B35`）时，客户端用 `primary` 作为该源的颜色；连 `primary` 都没配，就自动分配（见 §2.1）。反过来说，**只想换个主题色、不想多写 `meta.color`** 的源，把 `primary` 配成想要的颜色就够了。

---

## 12. `flags` — 功能开关

```jsonc
"flags": {
  "enableSearch": true,         // 首页顶栏「搜索」入口；还需 resources.search 至少一条才会出现
  "enableHistory": true,
  "enableFavorite": true,
  "allowCleartext": false,      // 是否允许 http（默认只允许 https）
  "debugTrace": true            // 设计稿字段；调试器已删除，此项当前不生效（保留字段以免老配置报错）
}
```

### 12.1 搜索入口的条件（2026-11-16 定稿）

入口显示在首页顶栏**「源选择」下拉的左侧**，点进去是"当前源内的搜索页"。判断口径是**两个条件同时成立**：

| 条件 | 说明 |
|---|---|
| `flags.enableSearch` 为 `true` | 作者意图层面的开关，默认 `true` |
| `resources.search` 至少有一条 | 数据层面的能力；只有开关没有资源，点进去只会一屏"这个源没有配置搜索"，所以入口干脆不出现 |

`tools/validate_config.py` 对"开了关却没有 search 资源"出**警告**（`flags.enableSearch`），对"search 资源的 url/params 里没有 `{{keyword}}`"出警告——这两种都是"配了但按不了/搜不出"的静默失败。

**v1 不支持多源搜索**：`flags.multiSourceSearch` 已从 Schema 删除（2026-11-16）。搜索天然是"当前源内"，因此不存在合并多个站点结果与跨源排序的问题；`enableSearch` 保留但只作用于当前源。

**旧配置兼容**：`ignoreUnknownKeys = false` 是刻意的（拼错键名要立刻报错），所以删字段会让含该键的历史配置整体判为"结构不符合 Schema"。`ConfigLoader.stripDeprecated()` 在 L1（`normalize` 之前）把 `flags.multiSourceSearch` 直接摘掉——它本来也不再有行为。以后再有这类"字段删除"，同样只在这一处登记。

---

## 13. 校验规则与错误码

| 码 | 阶段 | 含义 | 处理 |
|---|---|---|---|
| `E001` | L1 | JSON 语法错误 | 阻断导入，提示行列 |
| `E002` | L2 | 缺少必填字段 | 阻断，提示字段路径 |
| `E003` | L2 | 枚举值非法（如 `layout.type` 不认识） | 阻断，列出合法值 |
| `E004` | L3 | JSONPath / 正则语法错误 | 阻断，提示表达式与位置 |
| `E005` | L3 | 引用不存在（`page.feed` 指向未定义的资源） | 阻断 |
| `E006` | L3 | 循环引用（页面 children 递归自身） | 阻断 |
| `E007` | L1 | 配置体积超限（>1MB）或嵌套深度 > 20 | 阻断 |
| `E008` | L1 | `schemaVersion` 不受支持且无迁移路径 | 阻断 |
| `E009` | L3 | `fields` 出现未定义的域字段名 | 阻断（防拼写错误静默失效） |
| `E010` | L3 | URL scheme 非法（非 http/https） | 阻断 |
| `E011` | 运行期 | 分隔符未命中（`groupSplit`/`itemSplit`/`pairSplit` 切不出预期层数） | 该组降级为空，不阻断 |
| `W001` | L3 | `required: true` 缺失关键字段的容错提示 | 警告 |
| `W002` | L3 | `card.image` 指向的字段未在 `fields` 中定义 | 警告（渲染时为空图） |
| `W003` | 运行期 | 当前平台不支持该配置项（如 `kind=css` 在无 HTML 解析端） | 警告，降级执行，不计为阻断 |
| `W004` | 运行期 | `runtime.imageDecrypt` 存在但解密失败（key 轮换 / 不是密文 / 算法不符） | 警告，该图用占位图；同一 key 连续失败只记一次（§3.2）。**码已定义，触发尚未实现**：当前解密失败只是静默留空位，不产生任何提示 |

**阻断 vs 警告**：阻断级不允许多余容忍（宁可拒绝导入也不能让用户带着坏配置运行到白屏）；警告级允许导入但设置页标黄。

### 13.1 资源级规则清单（`core:config` 与 `tools/validate_config.py` 共同实现）

| 规则 | 报码 | 说明 |
|---|---|---|
| 列表资源必须声明 `list` **或** `listSplit` | `E002` | 二选一，都不写等于"没有列表可抽" |
| `listSplit` 必须是能编译的正则 | `E004` | 只写 `<entry>` 会切出空片段，应写成 `(?s)<entry>(.*?)</entry>` |
| `responseType` 为 `text`/`html` 且是列表资源时，必须声明 `listSplit` | `E002` | 纯文本上没有 JSON 文档，`list` 路径无从作用（§5.4） |
| `strip` 的 `prefix` 与 `suffix` 不能同时为空 | `E002` | 空壳剥壳等于没写，属于配置错误 |
| `root` 只在 JSON/AUTO 通路校验 | — | 文本通路不校验 `root` |
| `kind=const` 的 `from` 是**字面量**，不按 JSONPath 校验 | — | 只有 `kind` 字段的常量值参与 `video`/`audio`/`image` 白名单校验 |
| `regex`/`template`/`css` 不要求 `from` | `E002` | 它们分别由 `pattern` / `parts` / `selector` 提供内容 |
| `resources.categories` 的字段只允许 `id`/`name`/`icon` | `E009` | 导航特化结构，不套用媒体域字段表（§6.4） |
| `details` 必须映射 `id` 与 `title` | `E002` | 缺任一则详情页无法定位条目 |
| `runtime.imageDecrypt.algo` / `runtime.crypto.algo` / 任一挂点的 `algo` 必须在算法白名单内 | `E003` | 白名单见 §3.3.2（AES 家族 12 个 + DES 2 个 + 3DES 2 个）；`sm4-cbc` 这类不在表里的一律拒绝（**已落地**，见 §3.2 / §3.3） |
| `key` 按 `keyFormat` 解码后的字节数必须等于算法要求（AES 16/24/32、DES 8、3DES 24） | `E002` | 长度不对等于"每次解密都失败"，必须在导入期拦下；`runtime.crypto` 允许 key 为空（它只是默认值），挂点继承后仍为空才报 |
| `iv` 按 `ivFormat` 解码后的字节数必须等于算法要求（ECB 0 / GCM 12 / AES 16 / DES 8） | `E002` | 同上；ECB 不需要 iv，写了也忽略 |
| `padding` 只允许 `pkcs7` / `none` / `zero`；`output` 只允许 `base64` / `base64url` / `hex` / `raw`；`keyFormat`/`ivFormat` 只允许 `text` / `hex` / `base64` | `E003` | 写别的（如 `zlib`、`base32`）一律拒绝（**已落地**，见 §3.3） |
| CTR / GCM 的 `padding` 必须是 `none` | `E003` | 流式/认证模式补块只会破坏数据；`aes-*-gcm` 的 tag 固定 128 位、附在密文尾部 |
| `decrypt.from` 必须是合法 JSONPath | `E004` | 路径写错会让"取密文"取成空值，整段响应判定必然失败 |
| `request.encrypt.mode` 只允许 `params` / `whole`；`mode=params` 时 `params` 不能为空 | `E002` / `E003` | 空 `params` 等于什么都没加密，是最难查的一类配置错误 |
| `resources.tracks.*.fields` 上的 `decrypt` | `W003` | v1 字段级解密只接在 `resources.feeds/details/search/categories` 的 fields 上，这里声明了不会生效 |

---

## 14. 版本与迁移

```kotlin
interface ConfigMigration {
    val from: String
    val to: String
    fun migrate(raw: JsonObject): JsonObject
}
```

- 迁移器按 `from → to` 串联，如 `0.9 → 1.0 → 1.1`。
- 迁移在 **L1（Raw JSON 层）** 执行，因此不需要维护旧版本的强类型模型。
- 迁移后必须重新走完整校验；失败则报告"该配置来自 v0.9，自动升级失败"并保留原始文本供用户修改。

---

## 15. 完整示例

见 `samples/`（2026-11-20 现存 7 份，含量与标识一律为占位值，全部通过 `tools/validate_config.py`）。每份只演示一种能力，可对照下表找同类：

- 文本分条 — Atom XML 走 `listSplit` 文本分条（§5.4）
- 外壳剥离 + 播放地址模板拼接 + `episodes` 选集 — `VAR={…};` 外壳剥离后拼 `playUrl` 模板（§5.5）
- 壁纸数组 — 每日壁纸接口，8 张图，`tracks` 走 `array` 形态，进图片查看器
- 两级音乐接口 — `search` / `lookup` 两跳；曲目 `previewUrl` 为 30 秒 m4a，走音频播放页
- 公开 JSON 接口 + 7 个栏目 + 逐集 POST 取 HLS 直链 — 同时是"封面相对路径 vs 绝对签名地址"与**密文封面**（`runtime.imageDecrypt`，§3.2）的样本
- 整站 HTML、零 JSON 接口 — 文本 + regex + `paging` 多页
- 多频道长视频 — 8 个频道；详情 + `playurl` 直链，播放头必须桌面 UA。**2026-11-20 用户确认：电影/电视剧/动画/纪录片保持 UGC 分区，不做正片库**

> 早期文档里的 `minimal.json` / `demo-site.json` / `audio.json` / `gallery.json` / `crypto-demo.json` 是设计阶段的写作示例，仓库已不再保留；通用加解密由 `CryptoCodecTest` / `CryptoConfigTest` / `CryptoPipelineTest` 覆盖，不再用单独样本证明。

全部样本都在 `core:config` 的单测里做"必须通过校验"的回归样本（`SampleConfigTest.everySampleCompilesWithoutError`
会把 `samples/*.json` 全量编译一遍），改完配置先跑 `tools/validate_config.py`，再跑 `:core:config:jvmTest`。

---

## 16. 配置的平台无关性（多端硬约束）

**硬规则：配置文件里不出现任何"端"的概念。**

理由：同一份配置要被 Android / 桌面 / TV / iOS 共用。一旦配置里出现
"如果是 Android 就……"这类分支，配置作者就得为每端维护一份，多端方案立刻失效。

### 16.1 差异全部由 App 承担，配置只描述业务

| 差异点 | 配置里怎么写 | App 怎么处理 |
|---|---|---|
| 海报列数 | 只写 `theme.posterColumns`（**手机基准值**） | 按断点放大：Compact 沿用、Medium +1、Expanded +2～4 |
| 输入方式 | 不涉及 | 触摸 / 鼠标 / DPad 由组件库适配，配置不感知 |
| 播放格式 | 只给 URL（或 resolver 配置） | `PlayerCapabilities` 判定，不支持时走降级链 |
| HTML 解析 | 写 `kind=css` 是**合法**的 | 无 HTML 解析端降级为"文本 + 正则"，输出 `W003` |
| 明文 HTTP | `flags.allowCleartext` | 各端按自身安全策略执行（Android 还需 network security config 配合） |
| 加密图片（密文封面） | 只写 `runtime.imageDecrypt`（算法 + key/iv + padding/output） | 各端用自带密码库实现（JCE / CommonCrypto / WebCrypto），**不引入第三方加密依赖**；本端没有可用实现时按 `W003` 降级 |
| 通用加解密（请求加密 / 响应解密 / 字段解密） | 只写 `runtime.crypto` + 各挂点，**不写任何脚本或平台分支** | 与图片同一条引擎（§3.3）：算法白名单 16 个，base64/hex 是纯 Kotlin 实现，两端共用同一套 commonMain 代码 |
| 断点/密度 | 只给一个基准值 | 由 `PlatformInfo` + 容器宽度计算 |

### 16.2 唯一与平台策略有关的字段

只有 `flags.allowCleartext` 会因平台策略不同而表现不同（Android 默认禁明文，桌面通常不限制）。
**除此之外没有任何字段影响跨端行为** —— 这是刻意约束，不是遗漏。

> `runtime.imageDecrypt` 是"实现方式因端而异、语义完全相同"的一例：各端用自带密码库（JCE / CommonCrypto / WebCrypto），
> 但**算法、key/iv、填充规则、嗅探优先级都按本文固定**，配置里不出现任何"这个端怎么写"的分支。
> 某端暂时没有实现时按 `W003` 降级（图变占位），不得让配置失败。

### 16.3 新增平台时必须遵守

1. **不为新端新增配置字段**。确实需要时，走"可选字段 + 缺省即通用"，
   且默认值必须保证其他端行为完全不变。
2. 新端的能力缺口写进 `PlayerCapabilities` / `ExtractorSupport`，
   **不要写进配置**（否则配置会被端绑死）。
3. 校验器（`tools/validate_config.py` 及未来的 Kotlin 版）只做平台无关校验；
   平台相关能力检查放在运行时，输出 `W003`，**不得升级为阻断级 `E`**。
