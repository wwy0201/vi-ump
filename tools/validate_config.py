#!/usr/bin/env python3
"""
vi-ump 配置静态校验器（参考实现）

用途：
  1. 在没有 Gradle/Android 环境的机器上，先用它把示例配置验一遍；
  2. 作为 `core:config` 的 Kotlin 校验器（L3 阶段）的行为参照 —— 错误码与规则一一对应。

对应规范：docs/03-配置Schema规范.md §13
错误码：E001 语法 / E002 缺必填 / E003 枚举非法 / E004 路径语法 / E005 引用不存在
        E009 未定义域字段 / E010 URL scheme / E011 分隔符为空

支持三种媒体（video / audio / image）：
  - resources.tracks 支持 delimited（剧集/曲目串）与 array（图片集）两种模式
  - fields.kind 用 const 抽取器时可校验取值

支持非 JSON 响应（docs/03 §5.4）：
  - strip：剥掉响应体的固定前后缀（如腾讯 `QZOutputJson={...};`）
  - listSplit：文本分条正则，声明后忽略 list；text/html 列表资源必须声明它

用法：
  python tools/validate_config.py samples/*.json
"""

import base64
import json
import re
import sys

# 与 core:model 的 DomainField 白名单保持一致
DOMAIN_FIELDS = {
    "id", "title", "kind", "poster", "imageUrl", "subtitle", "year", "area",
    "type", "score", "duration", "tags", "playUrl", "trackCount",
    "description", "actors", "director", "artist", "album",
    "trackGroups", "related", "detailResource",
}
CATEGORY_FIELDS = {"id", "name", "icon"}

MEDIA_KINDS = {"video", "audio", "image"}

LAYOUT_TYPES = {
    "tabs", "tabs_grid", "sections", "banner_hero", "poster_grid",
    "row_cards", "list_linear", "category_filter", "detail",
    "track_panel", "gallery_grid", "image_viewer",
    "video_player", "audio_player", "search",
}

EXTRACTOR_KINDS = {"jsonPath", "regex", "css", "delimited", "template", "const"}
FIELD_TYPES = {"string", "int", "float", "bool", "stringList", "long"}
RESPONSE_TYPES = {"json", "text", "html", "auto"}
PAGING_MODES = {"page", "offset", "cursor"}
STRATEGIES = {"direct", "resolver"}
TRACK_MODES = {"delimited", "array"}
AUDIO_MODES = {"sequential", "loop", "shuffle"}

# 与 core:query 的 CryptoCodec.KEY_SIZES 保持一致（docs/03 §3.3）
CRYPTO_KEY_SIZES = {
    "aes-128-cbc": 16, "aes-192-cbc": 24, "aes-256-cbc": 32,
    "aes-128-ecb": 16, "aes-192-ecb": 24, "aes-256-ecb": 32,
    "aes-128-ctr": 16, "aes-192-ctr": 24, "aes-256-ctr": 32,
    "aes-128-gcm": 16, "aes-192-gcm": 24, "aes-256-gcm": 32,
    "des-cbc": 8, "des-ecb": 8,
    "des3-cbc": 24, "des3-ecb": 24,
}
CRYPTO_PADDINGS = {"pkcs7", "none", "zero"}
CRYPTO_OUTPUTS = {"base64", "base64url", "hex", "raw"}
CRYPTO_FORMATS = {"text", "hex", "base64"}


def crypto_iv_size(algo):
    if algo.endswith("-ecb"):
        return 0
    if algo.endswith("-gcm"):
        return 12
    if algo.startswith("des"):
        return 8
    return 16


def crypto_bytes(raw, fmt):
    """按 keyFormat/ivFormat 把字符串解成字节；`text` 就是 UTF-8 原样（黄果那种）"""
    if fmt == "text":
        return raw.encode("utf-8")
    if fmt == "hex":
        try:
            return bytes.fromhex(raw)
        except ValueError:
            return None
    if fmt == "base64":
        try:
            return base64.b64decode(raw, validate=True)
        except Exception:
            return None
    return None


def check_crypto(rep, path, spec, require_key=True):
    """一组加解密参数。与 ConfigValidator.checkCrypto 同一套规则"""
    algo = (spec.get("algo") or "aes-128-cbc").strip().lower().replace("_", "-")
    if algo not in CRYPTO_KEY_SIZES:
        rep.err(path + ".algo", "E003",
                f'不支持的算法「{algo}」；可用: {sorted(CRYPTO_KEY_SIZES)}')
        return
    pad = spec.get("padding", "pkcs7")
    if pad not in CRYPTO_PADDINGS:
        rep.err(path + ".padding", "E003", f'只允许 {sorted(CRYPTO_PADDINGS)}，收到「{pad}」')
    out = spec.get("output", "base64")
    if out not in CRYPTO_OUTPUTS:
        rep.err(path + ".output", "E003", f'只允许 {sorted(CRYPTO_OUTPUTS)}，收到「{out}」')
    kf = spec.get("keyFormat", "text")
    if kf not in CRYPTO_FORMATS:
        rep.err(path + ".keyFormat", "E003", f'只允许 {sorted(CRYPTO_FORMATS)}，收到「{kf}」')
    ivf = spec.get("ivFormat", "text")
    if ivf not in CRYPTO_FORMATS:
        rep.err(path + ".ivFormat", "E003", f'只允许 {sorted(CRYPTO_FORMATS)}，收到「{ivf}」')

    key_raw = spec.get("key") or ""
    if not key_raw:
        if require_key:
            rep.err(path + ".key", "E002", "缺少 key（也没有从 runtime.crypto 继承到）")
            return
    else:
        kb = crypto_bytes(key_raw, kf)
        want = CRYPTO_KEY_SIZES[algo]
        if kb is None or len(kb) != want:
            rep.err(path + ".key", "E002",
                    f"{algo} 需要 {want} 字节密钥（keyFormat={kf}），"
                    f"当前 {'无法解码' if kb is None else str(len(kb)) + ' 字节'} "
                    f"—— 长度不对等于每次解密都失败")

    need = crypto_iv_size(algo)
    if need > 0:
        iv_raw = spec.get("iv") or ""
        ivb = crypto_bytes(iv_raw, ivf) if iv_raw else None
        if ivb is None or len(ivb) != need:
            rep.err(path + ".iv", "E002",
                    f"{algo} 需要 {need} 字节 iv（ivFormat={ivf}），"
                    f"当前 {'未填或无法解码' if ivb is None else str(len(ivb)) + ' 字节'}")

    if algo.endswith(("-ctr", "-gcm")) and pad != "none":
        rep.err(path + ".padding", "E003",
                f"{algo} 是流式/认证模式，padding 必须 none（当前「{pad}」）")


def merge_crypto(base, spec):
    """挂点缺省继承 runtime.crypto（与 Kotlin 的 bakeCrypto 一致）"""
    base = base or {}
    return {
        "algo": spec.get("algo") if spec.get("algo") is not None else base.get("algo", "aes-128-cbc"),
        "key": spec.get("key") if spec.get("key") is not None else base.get("key", ""),
        "iv": spec.get("iv") if spec.get("iv") is not None else base.get("iv", ""),
        "padding": spec.get("padding") if spec.get("padding") is not None else base.get("padding", "pkcs7"),
        "output": spec.get("output") if spec.get("output") is not None else base.get("output", "base64"),
        "keyFormat": spec.get("keyFormat") if spec.get("keyFormat") is not None else base.get("keyFormat", "text"),
        "ivFormat": spec.get("ivFormat") if spec.get("ivFormat") is not None else base.get("ivFormat", "text"),
    }


def check_decrypt(rep, path, dec, base):
    if not isinstance(dec, dict):
        rep.err(path, "E002", "decrypt 必须是对象 {from?, algo?, key?, iv?, ...}")
        return
    if dec.get("from"):
        check_path_expr(rep, path + ".from", dec["from"])
    check_crypto(rep, path, merge_crypto(base, dec))


def check_encrypt(rep, path, enc, base):
    if not isinstance(enc, dict):
        rep.err(path, "E002", "encrypt 必须是对象 {mode, params, ...}")
        return
    mode = (enc.get("mode") or "params").strip().lower()
    if mode not in ("params", "whole"):
        rep.err(path + ".mode", "E003", f'只允许 params / whole，收到「{enc.get("mode")}」')
    params = enc.get("params") or []
    if mode == "params" and not params:
        rep.err(path + ".params", "E002", "mode=params 必须列出要加密的参数名")
    check_crypto(rep, path, merge_crypto(base, enc))


class Report:
    def __init__(self, name):
        self.name = name
        self.errors = []
        self.warnings = []

    def err(self, path, code, msg):
        self.errors.append(f"[{code}] {path}: {msg}")

    def warn(self, path, msg):
        self.warnings.append(f"[W] {path}: {msg}")

    def dump(self):
        ok = not self.errors
        print(f"{'PASS' if ok else 'FAIL'}  {self.name}")
        for e in self.errors:
            print("   -", e)
        for w in self.warnings:
            print("   ~", w)
        return ok


def check_path_expr(rep, path, expr, allow_empty=False):
    if expr in (None, ""):
        if not allow_empty:
            rep.err(path, "E002", "路径表达式为空")
        return
    if not isinstance(expr, str):
        rep.err(path, "E002", "路径表达式必须是字符串")
        return
    if not expr.startswith("$"):
        rep.err(path, "E004", f"JSONPath 必须以 $ 开头: {expr}")
        return
    if expr.count("[") != expr.count("]"):
        rep.err(path, "E004", f"方括号不匹配: {expr}")
    if "?(" in expr or "()" in expr:
        rep.warn(path, f"v1 不支持过滤器/函数，可能 0 命中: {expr}")


def check_url(rep, path, url, allow_cleartext=False):
    if not url:
        rep.err(path, "E002", "url 为空")
        return
    low = url.lower()
    if low.startswith(("file:", "content:", "javascript:")):
        rep.err(path, "E010", f"非法 scheme: {url[:40]}")
    elif low.startswith("http://") and not allow_cleartext:
        rep.warn(path, "http 明文地址，需 flags.allowCleartext=true 才能在 Android 上请求")
    elif not low.startswith(("http://", "https://", "{{")):
        rep.err(path, "E010", f"URL 既不是 http(s) 也不是模板: {url[:40]}")


def check_fields(rep, path, fields, allowed, crypto=None):
    for key, spec in (fields or {}).items():
        fp = f"{path}.{key}"
        if key not in allowed:
            rep.err(fp, "E009", f"未定义的域字段 `{key}`（合法: {sorted(allowed)}）")
            continue
        if isinstance(spec, str):
            check_path_expr(rep, fp, spec)
            continue
        if not isinstance(spec, dict):
            rep.err(fp, "E002", "字段映射既不是字符串也不是对象")
            continue

        k = spec.get("kind", "jsonPath")
        if k not in EXTRACTOR_KINDS:
            rep.err(fp + ".kind", "E003", f"未知抽取器 {k}")
            continue

        src = spec.get("from")
        if isinstance(src, list):
            if not src:
                rep.err(fp + ".from", "E002", "候选路径数组为空")
            for i, s in enumerate(src):
                check_path_expr(rep, f"{fp}.from[{i}]", s)
        elif src is not None:
            if k == "jsonPath":
                check_path_expr(rep, fp + ".from", src)
            elif k == "const":
                # kind 字段用常量时，校验取值合法
                if key == "kind" and src not in MEDIA_KINDS:
                    rep.err(fp, "E003", f"kind 常量必须是 {sorted(MEDIA_KINDS)}，实际 {src}")
            elif k == "regex" and not spec.get("pattern"):
                rep.err(fp, "E002", "kind=regex 时必须提供 pattern")
            elif k == "css" and not spec.get("selector"):
                rep.err(fp, "E002", "kind=css 时必须提供 selector")
            elif k == "delimited" and not spec.get("levels"):
                rep.err(fp, "E002", "kind=delimited 时必须提供 levels")
            elif k == "template" and not spec.get("parts"):
                rep.err(fp, "E002", "kind=template 时必须提供 parts")
        elif k in ("jsonPath", "delimited"):
            rep.err(fp + ".from", "E002", f"kind={k} 时必须提供 from")
        elif k == "const":
            rep.err(fp + ".from", "E002", "kind=const 时必须把常量值写在 from")

        t = spec.get("type", "string")
        if t not in FIELD_TYPES:
            rep.err(fp + ".type", "E003", f"未知类型 {t}")
        if spec.get("required") is True and key not in ("id", "title"):
            rep.warn(fp, "required=true 会让任何一条缺该字段的记录被跳过，确认是否有意")
        # 单字段解密（§3.3）：值已经在手上，这里的 from 无语义
        if spec.get("decrypt") is not None:
            check_decrypt(rep, fp + ".decrypt", spec["decrypt"], crypto)


def check_resource(rep, path, res, is_list=True, crypto=None):
    req = res.get("request", {})
    check_url(rep, path + ".request.url", req.get("url", ""))
    rt = res.get("responseType", "json")
    if rt not in RESPONSE_TYPES:
        rep.err(path + ".responseType", "E003", f"未知 responseType {rt}")
    strip = res.get("strip")
    if isinstance(strip, dict):
        if not strip.get("prefix") and not strip.get("suffix"):
            rep.err(path + ".strip", "E002", "strip 的 prefix 与 suffix 不能同时为空")
    elif strip is not None:
        rep.err(path + ".strip", "E002", "strip 必须是 {prefix?, suffix?} 对象")

    list_split = res.get("listSplit")
    if list_split is not None:
        if not isinstance(list_split, str) or not list_split.strip():
            rep.err(path + ".listSplit", "E002", "listSplit 必须是非空正则")
            list_split = None
        else:
            try:
                re.compile(list_split)
            except re.error as e:
                rep.err(path + ".listSplit", "E004", f"正则语法错误: {e}")

    if res.get("root"):
        check_path_expr(rep, path + ".root", res["root"])
    if is_list:
        if not res.get("list") and not list_split:
            rep.err(path + ".list", "E002", "列表资源必须声明 list 或 listSplit")
        elif res.get("list"):
            check_path_expr(rep, path + ".list", res["list"])
        # 纯文本响应上没有 JSON 文档，list 路径无从作用，必须改用文本分条
        if rt not in ("json", "auto") and not list_split:
            rep.err(
                path + ".listSplit", "E002",
                f"{rt} 响应的列表资源必须声明 listSplit：JSONPath 无法作用在纯文本上",
            )
    pg = res.get("paging")
    if pg:
        mode = pg.get("mode", "page")
        if mode not in PAGING_MODES:
            rep.err(path + ".paging.mode", "E003", f"未知分页模式 {mode}")
        if pg.get("hasMore"):
            check_path_expr(rep, path + ".paging.hasMore", pg["hasMore"])

    # 响应解密 / 请求参数加密（§3.3）
    if res.get("decrypt") is not None:
        check_decrypt(rep, path + ".decrypt", res["decrypt"], crypto)
    if isinstance(req, dict) and req.get("encrypt") is not None:
        check_encrypt(rep, path + ".request.encrypt", req["encrypt"], crypto)


def check_track_spec(rep, path, spec):
    """resources.tracks.* —— 两种模式"""
    mode = spec.get("mode", "delimited")
    if mode not in TRACK_MODES:
        rep.err(path + ".mode", "E003", f"未知 tracks 模式 {mode}（合法: {sorted(TRACK_MODES)}）")
        return

    if mode == "delimited":
        gn = spec.get("groupNames") or {}
        it = spec.get("items") or {}
        if gn.get("from"):
            check_path_expr(rep, path + ".groupNames.from", gn["from"])
        if not it.get("from"):
            rep.err(path + ".items.from", "E002", "delimited 模式必须声明 items.from")
        else:
            check_path_expr(rep, path + ".items.from", it["from"])
        for fld, val in (
            ("groupNames.split", gn.get("split")),
            ("items.groupSplit", it.get("groupSplit")),
            ("items.itemSplit", it.get("itemSplit")),
        ):
            if val is not None and val == "":
                rep.err(f"{path}.{fld}", "E011", "分隔符不能为空字符串")
        # 顺序约束：pairSplit 不得与更粗的分隔符相同长度
        pair = it.get("pairSplit")
        if pair and it.get("groupSplit") and pair.startswith(it["groupSplit"]):
            rep.warn(path, "pairSplit 与 groupSplit 前缀相同，按粗到细切分后可能错位")
    else:
        if not spec.get("from"):
            rep.err(path + ".from", "E002", "array 模式必须声明 from")
        else:
            check_path_expr(rep, path + ".from", spec["from"])
        f = spec.get("fields") or {}
        allowed = {"title", "url", "thumb", "duration"}
        for k, v in f.items():
            if k not in allowed:
                rep.err(f"{path}.fields.{k}", "E003", f"array 模式不支持字段 {k}（合法: {sorted(allowed)}）")
                continue
            if isinstance(v, str):
                check_path_expr(rep, f"{path}.fields.{k}", v)
            elif isinstance(v, dict):
                check_path_expr(rep, f"{path}.fields.{k}.from", v.get("from"))
                if v.get("decrypt") is not None:
                    # v1 字段级解密只接在 resources.*.fields 上，这里声明了不会生效
                    rep.warn(f"{path}.fields.{k}",
                             "resources.tracks 的字段解密暂未生效（v1 只支持 resources.*.fields），该字段按明文处理")
            else:
                rep.err(f"{path}.fields.{k}", "E002", "字段映射既不是字符串也不是对象")
        if not f.get("url"):
            rep.err(path + ".fields.url", "E002", "array 模式必须映射 url")


def walk_pages(rep, pages, feeds, details, tracks, path="pages"):
    for i, p in enumerate(pages or []):
        pp = f"{path}[{i}]"
        if not p.get("id"):
            rep.err(pp + ".id", "E002", "缺少页面 id")

        lay = p.get("layout") or {}
        t = lay.get("type")
        if not t:
            rep.err(pp + ".layout.type", "E002", "缺少 layout.type")
            continue
        if t not in LAYOUT_TYPES:
            rep.err(pp + ".layout.type", "E003", f"未知布局类型 {t}（合法: {sorted(LAYOUT_TYPES)}）")
            continue

        if t == "detail":
            r = lay.get("resource")
            if not r:
                rep.err(pp + ".layout.resource", "E002", "detail 布局必须指定 resource")
            elif r not in details:
                rep.err(pp + ".layout.resource", "E005", f"引用的详情资源不存在: {r}")
            tr = lay.get("tracks")
            if tr and tr not in tracks:
                rep.err(pp + ".layout.tracks", "E005", f"条目资源不存在: {tr}（已定义: {sorted(tracks)}）")
        elif t in ("video_player", "audio_player", "image_viewer"):
            pass  # 运行期由导航参数驱动，不需要配置引用
        else:
            f = p.get("feed")
            if f and f not in feeds:
                rep.err(pp + ".feed", "E005", f"引用不存在的资源: {f}（已定义: {sorted(feeds)}）")

        walk_pages(rep, p.get("children"), feeds, details, tracks, pp + ".children")
        walk_pages(rep, p.get("sections"), feeds, details, tracks, pp + ".sections")

        # 栏目 tab：只切"取数入口"。channels 与 children 互斥（一个切数据、一个切布局）
        chans = p.get("channels") or []
        if chans and (p.get("children") or []):
            rep.err(pp + ".channels", "E003", "channels 与 children 互斥：栏目切数据、children 切布局，二者只能选一种")
        seen = set()
        for ci, ch in enumerate(chans):
            cp = f"{pp}.channels[{ci}]"
            if not ch.get("id"):
                rep.err(cp + ".id", "E002", "栏缺少 id")
            elif ch["id"] in seen:
                rep.err(cp + ".id", "E002", f"栏目 id 重复: {ch['id']}")
            else:
                seen.add(ch["id"])
            cf = ch.get("feed")
            if not cf:
                rep.err(cp + ".feed", "E002", "栏目缺少 feed")
            elif cf not in feeds:
                rep.err(cp + ".feed", "E005", f"栏目引用的资源不存在: {cf}（已定义: {sorted(feeds)}）")
            cl = ch.get("layout") or {}
            if cl.get("type") == "detail":
                rep.err(cp + ".layout.type", "E003", "栏目不能用 detail 布局")

        for vi, mode in enumerate(p.get("viewModes") or []):
            if mode == "detail":
                rep.err(f"{pp}.viewModes[{vi}]", "E003", "视图切换不能包含 detail")


def validate(file_path):
    name = file_path.replace("\\", "/").split("/")[-1]
    rep = Report(name)
    try:
        doc = json.load(open(file_path, encoding="utf-8"))
    except Exception as e:
        rep.err("<root>", "E001", f"JSON 语法错误: {e}")
        return rep

    meta = doc.get("meta") or {}
    if not meta.get("id"):
        rep.err("meta.id", "E002", "缺少 meta.id")
    if not meta.get("name"):
        rep.err("meta.name", "E002", "缺少 meta.name")

    runtime = doc.get("runtime") or {}
    if not runtime.get("baseUrl"):
        rep.err("runtime.baseUrl", "E002", "缺少 runtime.baseUrl")

    # 加解密（§3.2 密文图片 / §3.3 通用）：写错的唯一表现是"静默失败"（图灰块、列表空、
    # 播放地址乱码，哪里都不报错），只能在导入期拦。
    crypto_defaults = runtime.get("crypto")
    if crypto_defaults is not None:
        if isinstance(crypto_defaults, dict):
            # runtime.crypto 只是默认值：各挂点可以自带 key，所以这里允许 key 为空
            check_crypto(rep, "runtime.crypto", crypto_defaults, require_key=False)
        else:
            rep.err("runtime.crypto", "E003", "runtime.crypto 必须是对象")

    dec = runtime.get("imageDecrypt")
    if dec is not None:
        if not isinstance(dec, dict):
            rep.err("runtime.imageDecrypt", "E003", "必须是对象 {algo,key,iv,padding}")
        else:
            # 图片通路是字节进字节出，output 默认 raw（其余口径与通用一致）
            as_crypto = dict(dec)
            as_crypto.setdefault("output", "raw")
            check_crypto(rep, "runtime.imageDecrypt", as_crypto, require_key=True)

    flags = doc.get("flags") or {}
    allow_cleartext = bool(flags.get("allowCleartext"))
    # 搜索入口是"开关 + 数据"两个条件（见 docs/03 §12）：只打开关却没配 search，
    # 首页也不会出现按钮，作者会以为是渲染坏了
    enable_search = bool(flags.get("enableSearch", True))

    res = doc.get("resources") or {}
    feeds = res.get("feeds") or {}
    details = res.get("details") or {}
    tracks = res.get("tracks") or {}
    search = res.get("search") or {}

    if not feeds:
        rep.err("resources.feeds", "E002", "至少需要 1 个列表资源")
    if not details:
        rep.err("resources.details", "E002", "至少需要 1 个详情资源")

    if res.get("categories"):
        check_resource(rep, "resources.categories", res["categories"], is_list=True, crypto=crypto_defaults)
        check_fields(rep, "resources.categories.fields",
                     res["categories"].get("fields"), CATEGORY_FIELDS, crypto_defaults)

    for key, spec in feeds.items():
        check_resource(rep, f"resources.feeds.{key}", spec, crypto=crypto_defaults)
        check_fields(rep, f"resources.feeds.{key}.fields", spec.get("fields"), DOMAIN_FIELDS, crypto_defaults)

    for key, spec in details.items():
        check_resource(rep, f"resources.details.{key}", spec, is_list=False, crypto=crypto_defaults)
        check_fields(rep, f"resources.details.{key}.fields", spec.get("fields"), DOMAIN_FIELDS, crypto_defaults)
        for req in ("id", "title"):
            if req not in (spec.get("fields") or {}):
                rep.err(f"resources.details.{key}.fields.{req}", "E002", f"详情资源必须映射 {req}")

    if enable_search and not search:
        rep.warn("flags.enableSearch",
                 "enableSearch=true 但没有 resources.search：首页不会出现搜索入口"
                 "（入口条件是 开关 + 至少一条 search 资源）")

    for key, spec in search.items():
        check_resource(rep, f"resources.search.{key}", spec, crypto=crypto_defaults)
        check_fields(rep, f"resources.search.{key}.fields", spec.get("fields"), DOMAIN_FIELDS, crypto_defaults)
        # 关键词占位符不一定在 url 上（也可能塞进 params），两处都查
        req = spec.get("request") or {}
        blob = (req.get("url") or "") + " " + " ".join(str(v) for v in (req.get("params") or {}).values())
        if "{{keyword}}" not in blob:
            rep.warn(f"resources.search.{key}",
                     "request 的 url / params 里没有 {{keyword}}：搜索词不会进入请求")

    for key, spec in tracks.items():
        check_track_spec(rep, f"resources.tracks.{key}", spec)

    feed_pool = dict(feeds)
    feed_pool.update(search)
    walk_pages(rep, doc.get("pages"), feed_pool, details, tracks)

    pb = doc.get("playback") or {}
    st = pb.get("strategy", "direct")
    if st not in STRATEGIES:
        rep.err("playback.strategy", "E003", f"未知播放策略 {st}")
    if st == "resolver" and not pb.get("resolver"):
        rep.err("playback.resolver", "E002", "strategy=resolver 时必须提供 resolver")

    au = pb.get("audio") or {}
    if au.get("mode") and au["mode"] not in AUDIO_MODES:
        rep.err("playback.audio.mode", "E003", f"未知音频模式 {au['mode']}（合法: {sorted(AUDIO_MODES)}）")

    return rep


def main(argv):
    if len(argv) < 2:
        print("用法: python tools/validate_config.py <config.json> [...]")
        return 2
    all_ok = True
    for f in argv[1:]:
        rep = validate(f)
        all_ok &= rep.dump()
        print()
    print("总体:", "PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
