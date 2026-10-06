# -*- coding: utf-8 -*-
"""本地维护: 红果账号（验证码登录）+ 观看进度 / 收藏 双向同步。

纯协议实现，不需要模拟器常驻；登录态保存在本机应用数据目录。
签名走 backend 自带的脱机签名服务（SIGN_SERVER）。

协议要点（均以真机抓包核对）：
  * 验证码登录
      POST /passport/mobile/send_code/v1/   mobile/type/unbind_exist = XOR(0x05) 后 hex
      POST /passport/mobile/sms_login/      mobile/code          = XOR(0x05) 后 hex
      mix_mode=1；表单编码；返回 Set-Cookie 即登录态
  * 观看进度（手机“历史”页读的就是这个）
      POST /reading/bookapi/read_history/update/v   body gzip + x-ss-stub
      GET  /reading/bookapi/read_history/list/v?book_type=2
  * 收藏（短剧书架）
      POST /reading/bookapi/bookshelf/video/update/v   video_shelf_operate_type 0=加 1=删
      GET  /reading/bookapi/bookshelf/video/list/v

请求体统一 gzip 压缩，X-SS-STUB = 压缩后字节的 MD5(大写)，Content-Encoding: gzip。
"""
import gzip
import hashlib
import io
import json
import os
import re
import threading
import time

import requests

import hongguo as H

# ---- 登录态存放位置 -------------------------------------------------------
_DATA_DIR = os.environ.get("HONGGUO_DATA_DIR") or os.path.join(
    os.environ.get("APPDATA") or os.path.expanduser("~"), "cn.guoban.desktop-companion")
SESSION_PATH = os.environ.get("HONGGUO_ACCOUNT_FILE") or os.path.join(
    _DATA_DIR, "desktop-account.json")

# 验证码登录接口所在的 host（与主 API host 不同）
PASSPORT_HOST = os.environ.get("HONGGUO_PASSPORT_HOST", "security.snssdk.com")

# 护照接口的 UA 必须与真机一致（模拟器抓包原文）。
# 用内容接口那套 UA 或自定义 UA 会被风控判为异常客户端，登录直接返回 error_code=7。
PASSPORT_UA = os.environ.get(
    "HONGGUO_PASSPORT_UA",
    "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; "
    "Build/V417IR;tt-ok/3.12.13.20)")

# ---- 护照请求的设备身份 ---------------------------------------------------
# 护照接口会校验设备指纹：device_id / iid / cdid 缺失会被判为异常客户端。
# 内容接口不校验这些（所以搜索播放一直正常），只有登录会踩到。
DEVICE_PATH = os.environ.get("HONGGUO_DEVICE_FILE") or os.path.join(
    _DATA_DIR, "desktop-device.json")

# 机型档案：与真实红果客户端一致（护照侧对参数完整性敏感，故写全）
PASSPORT_DEVICE_DEFAULTS = {
    "device_brand": "HONOR", "device_type": "PGT-AN10",
    "resolution": "1080*1920", "dpi": "480",
    "os": "android", "os_version": "12", "os_api": "32",
    "rom_version": "V417IR release-keys", "host_abi": "arm64-v8a",
    "channel": "vivo_8662_64", "ac": "wifi", "ssmix": "a",
    "language": "zh", "dragon_device_type": "phone",
    "manifest_version_code": "73932", "update_version_code": "73932",
    "version_code": "73932", "version_name": "7.3.9.32",
    "pv_player": "73932", "compliance_status": "0",
    "need_personal_recommend": "1", "player_so_load": "1",
    "is_android_pad_screen": "0", "okhttp_version": "4.2.243.31-douyin",
    "use_store_region_cookie": "1", "use_new_token_expire_rule": "true",
    "passport-sdk-version": "5051452",
    # 运行态/会话字段：真机每次请求都带，缺失可能被判为异常客户端
    "aid": "8662", "app_name": "novelread", "device_platform": "android",
    "gender": "2", "har_status": "0", "charging": "0",
    "network_type": "4", "down_speed": "60000", "font_scale": "100",
    "battery_pct": "93", "screen_brightness": "102", "current_volume": "0",
    "app_dark_mode": "0", "sys_dark_mode": "0", "sys_mini_window": "0",
    "app_mini_window": "0", "is_power_save_mode": "0",
    "normal_session_cnt_in_day": "1", "normal_session_cnt_in_life": "1",
    "cold_start_session_cnt_in_day": "1", "cold_start_session_cnt_in_life": "1",
}

# 允许放进请求体的字段（registered / source 等本地标记必须排除）
PASSPORT_BODY_FIELDS = set(PASSPORT_DEVICE_DEFAULTS) | {
    "device_id", "iid", "cdid", "normal_session_id", "cold_start_session_id",
}


def _digits(n):
    import random
    return "".join(random.choice("0123456789") for _ in range(n))


# 已注册设备身份的来源优先级：
#   1) 显式环境变量（HONGGUO_DEVICE_ID / _IID / _CDID）
#   2) 本机设备文件（首次从模拟器同步后固定下来）
#   3) 模拟器（adb 读取，已注册的那台）
# 随机生成的 device_id 会被护照边缘直接 403，所以不能凭空造。
DEVICE_ENV = ("HONGGUO_DEVICE_ID", "HONGGUO_DEVICE_IID", "HONGGUO_DEVICE_CDID")


def _from_env():
    values = [os.environ.get(name) for name in DEVICE_ENV]
    if all(values):
        return {"device_id": values[0], "iid": values[1], "cdid": values[2]}
    return None


def _from_emulator():
    """从模拟器里已注册的红果客户端读设备身份。

    只读 shared_prefs 里的公开字段，不注入进程、不改动 App。
    实测位置：
      device_id / iid -> applog_stats.xml
      cdid            -> com.ss.android.deviceregister.utils.Cdid.xml
    """
    import subprocess
    adb = os.environ.get("ADB", r"D:\Tools\adb\adb.exe")
    dev = os.environ.get("ADB_DEVICE", "127.0.0.1:16448")
    prefs = "/data/data/com.phoenix.read/shared_prefs"

    def read(name):
        try:
            out = subprocess.run([adb, "-s", dev, "shell", "cat", "%s/%s" % (prefs, name)],
                                 capture_output=True, timeout=20)
        except Exception:
            return ""
        return out.stdout.decode("utf-8", "replace")

    def grab(text, field):
        for pat in (r'name="%s"[^>]*>([^<]+)<' % re.escape(field),
                    r'name="%s"[^>]*value="([^"]+)"' % re.escape(field)):
            m = re.search(pat, text)
            if m and m.group(1).strip():
                return m.group(1).strip()
        return ""

    found = {}
    stats = read("applog_stats.xml")
    if stats:
        found["device_id"] = grab(stats, "device_id")
        found["iid"] = grab(stats, "install_id")
    cdid = read("com.ss.android.deviceregister.utils.Cdid.xml")
    if cdid:
        found["cdid"] = grab(cdid, "cdid")
    if all(found.get(k) for k in ("device_id", "iid", "cdid")):
        return found
    return None


def _session_ids():
    """真机会带会话 id（形如 <uuid>#<序号>）。缺失时补上并固定。"""
    data = load_device()
    changed = False
    for key, suffix in (("normal_session_id", "#1"), ("cold_start_session_id", "")):
        if not data.get(key):
            import uuid
            data[key] = str(uuid.uuid4()) + suffix
            changed = True
    if changed and data.get("registered"):
        try:
            io.open(DEVICE_PATH, "w", encoding="utf-8").write(
                json.dumps(data, ensure_ascii=False, indent=1))
        except OSError:
            pass
    return data


def load_device():
    """本机设备身份：一旦确定就固定下来，避免每次登录换设备。"""
    env = _from_env()
    if env:
        return env
    try:
        data = json.loads(io.open(DEVICE_PATH, encoding="utf-8").read())
        if isinstance(data, dict) and data.get("device_id") and data.get("registered"):
            return data
    except Exception:
        pass
    synced = _from_emulator()
    if synced:
        synced["registered"] = True
        synced["source"] = "emulator"
        try:
            os.makedirs(os.path.dirname(DEVICE_PATH), exist_ok=True)
            io.open(DEVICE_PATH, "w", encoding="utf-8").write(
                json.dumps(synced, ensure_ascii=False, indent=1))
        except OSError:
            pass
        return synced
    # 兜底：沿用已存文件（即便未标记 registered）
    try:
        data = json.loads(io.open(DEVICE_PATH, encoding="utf-8").read())
        if isinstance(data, dict) and data.get("device_id"):
            return data
    except Exception:
        pass
    # 最后才随机 —— 但要标清楚「未注册」。
    # 2026-10-07 实测：随机生成的 device_id 在字节的注册服务里没有记录，
    # 登录会被判为异常客户端，稳定返回 error_code=7（系统繁忙）。
    # 模拟器里那台真实注册过的设备同样条件下返回 1202/1203（正常进入校验）。
    # 所以调用方应优先用 registered=True 的设备；这里只作最后兜底。
    import uuid
    log_event("device_unregistered",
              note="随机设备未经注册，登录可能被判异常客户端")
    return {"device_id": _digits(16), "iid": _digits(16),
            "cdid": str(uuid.uuid4()), "registered": False, "source": "random"}


def passport_query():
    """护照请求要带的完整设备参数（内容接口那套精简 query 不够用）。"""
    query = dict(PASSPORT_DEVICE_DEFAULTS)
    query.update(_session_ids())
    return query
SMS_TYPE = os.environ.get("HONGGUO_SMS_TYPE", "24")   # 抓包: type=24 → 登录场景

_lock = threading.RLock()
_cache = None

# ---- 诊断日志 -------------------------------------------------------------
# 登录失败时用户没有可查的证据，所以每次护照调用都留一条记录。
# 绝不记录 token / cookie / 完整手机号 / 完整验证码。
LOG_PATH = os.environ.get("HONGGUO_ACCOUNT_LOG") or os.path.join(_DATA_DIR, "account-log.jsonl")
LOG_MAX_BYTES = 512 * 1024


def mask_mobile(value):
    text = re.sub(r"\D", "", str(value or ""))
    if len(text) < 7:
        return "***"
    return text[:3] + "****" + text[-4:]


def log_event(event, **fields):
    try:
        os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
        try:
            if os.path.getsize(LOG_PATH) > LOG_MAX_BYTES:
                os.replace(LOG_PATH, LOG_PATH + ".1")
        except OSError:
            pass
        record = {"t": int(time.time() * 1000), "event": event}
        record.update(fields)
        with io.open(LOG_PATH, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError:
        pass


def read_log(limit=50):
    items = []
    try:
        for line in io.open(LOG_PATH, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                items.append(json.loads(line))
            except ValueError:
                continue
    except OSError:
        return []
    return items[-int(limit):]


# ---- 编解码 ---------------------------------------------------------------
def xor_hex(text):
    """红果 passport 的字段编码：UTF-8 字节逐字节异或 0x05，再转小写 hex。"""
    return bytes(b ^ 5 for b in str(text).encode("utf-8")).hex()


def normalize_mobile(mobile):
    """护照接口要求手机号带国家码并按 3-4-4 分组。

    实测：真机发的是 '+86157 3063 9941'（含 '+86' 与空格），
    XOR(0x05) 后与抓包逐字节一致；发裸 11 位会被判为非法号码，
    服务端直接返回 error_code=7（提示却是「系统繁忙」）。
    """
    digits = re.sub(r"\D", "", str(mobile or ""))
    if len(digits) == 13 and digits.startswith("86"):
        digits = digits[2:]
    if len(digits) == 11 and digits.startswith("1"):
        # 真机形态：+86 前缀 + 3-4-4 分组（组间是空格）
        return "+86" + digits[:3] + " " + digits[3:7] + " " + digits[7:11]
    return ("+86" + digits) if digits else ""


def xor_unhex(value):
    try:
        return bytes(b ^ 5 for b in bytes.fromhex(value)).decode("utf-8")
    except Exception:
        return ""


def _gzip_body(payload):
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=6, mtime=0) as gz:
        gz.write(raw)
    return buf.getvalue()


# ---- 登录态读写 -----------------------------------------------------------
def load_session():
    global _cache
    with _lock:
        if _cache is not None:
            return _cache
        data = {}
        try:
            if os.path.isfile(SESSION_PATH):
                data = json.loads(io.open(SESSION_PATH, encoding="utf-8").read())
        except Exception:
            data = {}
        if not isinstance(data, dict):
            data = {}
        _cache = data
        return _cache


def save_session(data):
    global _cache
    with _lock:
        _cache = data or {}
        try:
            os.makedirs(os.path.dirname(SESSION_PATH), exist_ok=True)
            tmp = SESSION_PATH + ".tmp"
            io.open(tmp, "w", encoding="utf-8").write(
                json.dumps(_cache, ensure_ascii=False, indent=2))
            os.replace(tmp, SESSION_PATH)
        except OSError:
            pass
    return _cache


def clear_session():
    """退出登录：先留一份可恢复的备份，再清空。

    验证码登录尚未稳定，万一退出后登不回来，用户不该被卡死；
    所以这里把当前登录态另存一份，restore_session() 可以原样恢复。
    """
    current = load_session() or {}
    if current.get("token") or current.get("cookie"):
        try:
            io.open(SESSION_PATH + ".last", "w", encoding="utf-8").write(
                json.dumps(current, ensure_ascii=False, indent=2))
        except OSError:
            pass
    return save_session({})


def restore_session():
    """把上一次「退出登录」前的登录态恢复回来。"""
    try:
        data = json.loads(io.open(SESSION_PATH + ".last", encoding="utf-8").read())
    except Exception:
        return {"ok": False, "error": "没有可恢复的登录态"}
    if not (isinstance(data, dict) and (data.get("token") or data.get("cookie"))):
        return {"ok": False, "error": "备份里没有有效登录态"}
    save_session(data)
    log_event("restore_session", ok=True)
    return {"ok": True, "session": public_session(data)}


def has_restorable():
    """是否存在可恢复的登录态（用于前端显示恢复入口）。"""
    try:
        data = json.loads(io.open(SESSION_PATH + ".last", encoding="utf-8").read())
        return bool(isinstance(data, dict) and (data.get("token") or data.get("cookie")))
    except Exception:
        return False


def is_logged_in():
    s = load_session()
    return bool(s.get("cookie") or s.get("token"))


# ---- 请求 -----------------------------------------------------------------
def _headers(session=None, extra=None):
    headers = dict(H.CFG.get("session_headers") or {})
    session = session if session is not None else load_session()
    token = (session or {}).get("token")
    cookie = (session or {}).get("cookie")
    if token:
        headers["x-tt-token"] = token
    if cookie:
        headers["cookie"] = cookie
    headers["content-type"] = "application/json; charset=utf-8"
    if extra:
        headers.update(extra)
    return headers


def _call(method, path, body=None, extra=None, session=None, host=None, form=None,
          passport=False):
    """发一个带签名的红果请求。body/form 二选一。

    passport=True 时用完整设备参数（登录接口校验设备指纹，精简 query 会被风控）。
    """
    if passport:
        merged = dict(passport_query())
        if extra:
            merged.update(extra)
        url = H.build_url(path, merged)
    else:
        url = H.build_url(path, extra)
    if host:
        url = re.sub(r"^https://[^/]+", "https://" + host, url)
    headers = _headers(session)
    data = None
    if form is not None:
        data = form.encode("utf-8")
        headers["content-type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = _gzip_body(body)
        headers["content-encoding"] = "gzip"
        headers["x-ss-stub"] = hashlib.md5(data).hexdigest().upper()
    if passport:
        # 真机护照请求的三个硬性条件（2026-10-07 抓包 + 剥离实验确认）：
        #   1) UA 必须是 App 原文（内容接口那套 UA 会被判异常客户端 → error_code=7）
        #   2) 必须带 x-ss-req-ticket（毫秒时间戳），否则同样退化成 error_code=7
        #   3) 请求体必须带 x-ss-stub（登录/发码都是表单，所以是真机那种 32 位大写十六进制）
        # 只补 1+2 仍然失败；补上 3 后服务端才放行（错误码从 7 变成「验证码错误/过期」）。
        #
        # 头部要与真机逐项对齐（2026-10-07 对照实验，真机头部全量通过风控）：
        #   · 去掉内容接口那套 x-tt-store-region*，以及 X-Neptune / X-Soter（护照请求不带）
        #   · 补 lc / x-vc-bdturing-sdk-version
        #   · cookie 与 x-tt-passport-csrf-token 必须带上（真机请求里有；
        #     之前 sms_login 传了空 session，等于完全不发 cookie，这是被拦的关键）
        for stale in ("x-tt-store-region", "x-tt-store-region-src"):
            headers.pop(stale, None)
        headers["user-agent"] = PASSPORT_UA
        headers["lc"] = "101"
        headers["x-vc-bdturing-sdk-version"] = "4.0.3.cn"
        headers["x-ss-req-ticket"] = str(int(time.time() * 1000))
        # x-ss-stub 不能是 md5(请求体)！
        # 2026-10-07 交替对照（各测 2 次，结果稳定）：
        #   x-ss-stub = md5(请求体)     -> error_code=7（被拦）
        #   x-ss-stub = 随机 32 位 hex  -> 1203（通过，进入验证码校验）
        # 上游文档写的「x-ss-stub = body 的 MD5」对护照接口不成立；
        # 服务端会拒绝 stub 恰好等于请求体 md5 的请求。这里用随机值。
        import secrets as _secrets
        headers["x-ss-stub"] = _secrets.token_hex(16).upper()
        # csrf：优先用会话里的；没有就从 cookie 里抠出来。
        csrf = ""
        cookie_text = headers.get("cookie") or ""
        m = re.search(r"passport_csrf_token=([^;]+)", cookie_text)
        if m:
            csrf = m.group(1)
        if csrf:
            headers["x-tt-passport-csrf-token"] = csrf
    headers.update(H.sign(url, headers))
    headers.pop("accept-encoding", None)
    if passport:
        # 签名会补上 X-Neptune / X-Soter，但真机的护照请求没有这两个头，
        # 必须在签名之后删，否则等于没删。
        for stale in ("x-neptune", "x-soter", "X-Neptune", "X-Soter"):
            headers.pop(stale, None)
    r = H.http_request(method, url, data=data, headers=headers, timeout=30)
    return r


def _json(response):
    try:
        return response.json()
    except ValueError:
        return {"code": -1, "message": "非 JSON 响应", "raw": response.text[:300]}


# ---- 验证码登录 -----------------------------------------------------------
def _passport_form(mobile=None, code=None, with_device=False):
    """构造护照表单。

    with_device=True 时把设备参数也编进请求体 —— 真机就是这么发的
    （sms_login 请求体 55 个字段，设备字段和业务字段在同一个表单里）。
    只放 URL query 不生效：服务端校验的是请求体。
    """
    from urllib.parse import quote
    parts = []
    if mobile:
        parts.append("mobile=" + xor_hex(mobile))
    if code:
        parts.append("code=" + xor_hex(code))
    parts += [
        "account_sdk_source=app",
        "passport_support_flow=captcha%2Cverify",
        "mix_mode=1",
    ]
    if with_device:
        query = passport_query()
        for key, value in query.items():
            if key not in PASSPORT_BODY_FIELDS:
                continue
            parts.append("%s=%s" % (key, quote(str(value), safe="")))
    return "&".join(parts)


def _passport_result(body):
    """passport 的响应形状与内容接口不同：
    成功是 {"message":"success","data":{...}}；
    失败是 {"message":"error","data":{"error_code":N,"description":"..."}}。
    """
    if not isinstance(body, dict):
        return False, "响应格式异常", None
    data = body.get("data")
    if isinstance(data, dict) and data.get("error_code"):
        return False, str(data.get("description") or "请求被拒绝"), data.get("error_code")
    message = str(body.get("message") or "").lower()
    if message == "success" or body.get("code") in (0, "0"):
        return True, "", None
    return False, str(body.get("message") or "请求失败"), None


def send_code(mobile):
    mobile = re.sub(r"\D", "", str(mobile or ""))
    if not re.fullmatch(r"1\d{10}", mobile):
        return {"ok": False, "error": "手机号格式不正确"}
    form = (_passport_form(mobile=normalize_mobile(mobile), with_device=True)
            + "&type=" + xor_hex(SMS_TYPE)
            + "&unbind_exist=" + xor_hex("1") + "&auto_read=0")
    r = _call("POST", "/passport/mobile/send_code/v1/", form=form,
              host=PASSPORT_HOST, passport=True)
    j = _json(r)
    ok, why, err_code = _passport_result(j)
    data = j.get("data") if isinstance(j.get("data"), dict) else {}
    log_event("send_code", host=PASSPORT_HOST, http=r.status_code, ok=ok,
              mobile=mask_mobile(mobile), type=SMS_TYPE,
              error_code=err_code, message=str(j.get("message"))[:80],
              description=str(data.get("description") or why)[:120],
              has_ticket=bool(data.get("mobile_ticket")),
              retry_time=data.get("retry_time"))
    return {"ok": ok, "code": j.get("code"), "message": j.get("message"),
            "error": "" if ok else why, "error_code": err_code,
            "hasTicket": bool(data.get("mobile_ticket")),
            "retryTime": data.get("retry_time"),
            "data": j.get("data"), "http": r.status_code}


def _extract_session(response, session):
    """从登录响应里取出 token / cookie。"""
    cookie = ""
    try:
        jar = getattr(response, "cookies", None)
        if jar is not None and len(jar):
            cookie = "; ".join("%s=%s" % (c.name, c.value) for c in jar)
    except Exception:
        cookie = ""
    if not cookie:
        raw = response.headers.get("set-cookie") or ""
        if raw:
            cookie = re.sub(r";\s*(Path|Domain|Expires|Max-Age|Secure|HttpOnly|SameSite)[^;]*", "", raw, flags=re.I)
    if not cookie:
        cookie = (session or {}).get("cookie") or ""
    try:
        body = response.json()
    except ValueError:
        body = {}
    data = body.get("data") if isinstance(body, dict) else None
    token = ""
    if isinstance(data, dict):
        for key in ("token", "session_key", "x_tt_token", "x-tt-token"):
            if data.get(key):
                token = str(data[key])
                break
    if not token:
        token = (session or {}).get("token") or ""
    user = ""
    uid = ""
    if isinstance(data, dict):
        user = str(data.get("user_name") or data.get("name") or "")
        uid = str(data.get("user_id") or data.get("uid") or data.get("user_id_str") or "")
    return {"token": token, "cookie": cookie, "user_name": user, "uid": uid}


def sms_login(mobile, code):
    mobile = re.sub(r"\D", "", str(mobile or ""))
    code = re.sub(r"\D", "", str(code or ""))
    if not re.fullmatch(r"1\d{10}", mobile):
        return {"ok": False, "error": "手机号格式不正确"}
    if not re.fullmatch(r"\d{4,8}", code):
        return {"ok": False, "error": "验证码格式不正确"}
    form = _passport_form(mobile=normalize_mobile(mobile), code=code, with_device=True)
    r = _call("POST", "/passport/mobile/sms_login/", form=form,
              host=PASSPORT_HOST, passport=True)
    j = _json(r)
    ok, why, err_code = _passport_result(j)
    data = j.get("data") if isinstance(j.get("data"), dict) else {}
    if not ok:
        log_event("sms_login_failed", host=PASSPORT_HOST, http=r.status_code,
                  mobile=mask_mobile(mobile), code_len=len(code),
                  error_code=err_code, message=str(j.get("message"))[:80],
                  description=str(data.get("description") or why)[:120])
        return {"ok": False, "code": err_code if err_code is not None else j.get("code"),
                "error": why or "登录失败", "error_code": err_code,
                "http": r.status_code}
    session = _extract_session(r, {})
    session["saved_at"] = int(time.time())
    session["mobile"] = mobile
    save_session(session)
    log_event("sms_login_ok", host=PASSPORT_HOST, http=r.status_code,
              mobile=mask_mobile(mobile), has_cookie=bool(session.get("cookie")))
    # 立刻用登录态拉一次账号信息，确认真的可用
    info = _json(_call("GET", "/reading/user/info/v", session=session))
    if isinstance(info.get("data"), dict):
        session["user_name"] = info["data"].get("user_name") or session.get("user_name") or ""
        session["uid"] = str(info["data"].get("user_id") or session.get("uid") or "")
        save_session(session)
    return {"ok": True, "code": 0, "session": public_session(session)}


def public_session(session=None):
    s = session if session is not None else load_session()
    return {
        "loggedIn": bool(s.get("cookie") or s.get("token")),
        "userName": s.get("user_name") or "",
        "uid": s.get("uid") or "",
        "mobile": (s.get("mobile") or "")[:3] + "****" + (s.get("mobile") or "")[-4:]
        if s.get("mobile") else "",
        "savedAt": s.get("saved_at") or 0,
    }


# ---- 观看进度 / 历史 ------------------------------------------------------
def sync_progress(series_id, episode, total=0, position=0, duration=0, series=None):
    """把桌面端的观看进度上报到账号（手机“历史”页立即可见）。

    合并规则：同一剧集「取更靠前的进度」，不允许把云端进度改小。
    否则会出现：手机看到 520 集，PC 本地停在 500 集，PC 一上报就把 520 覆盖成 500。
    所以上报前先读云端，只有本地确实更靠前时才写。
    """
    if not is_logged_in():
        return {"ok": False, "error": "未登录红果账号"}
    series_id = str(series_id)
    if not re.fullmatch(r"[0-9]{8,24}", series_id):
        return {"ok": False, "error": "剧集标识不合法"}
    episode = int(episode)
    if not 1 <= episode <= 100000:
        return {"ok": False, "error": "集号不合法"}
    # 先比云端：本地不更靠前就跳过，避免把多端的进度改小。
    try:
        cloud = remote_progress(series_id)
    except Exception:
        cloud = {"ok": False}
    if cloud.get("ok"):
        cloud_ep = int(cloud.get("episode") or 0)
        cloud_pos = int(cloud.get("position") or 0)
        if cloud_ep > episode:
            log_event("progress_skipped", reason="cloud_ahead",
                      cloud_episode=cloud_ep, local_episode=episode)
            return {"ok": True, "skipped": True, "episode": cloud_ep,
                    "reason": "云端进度更靠前，保持不变"}
        if cloud_ep == episode and cloud_pos > int(max(0, position)):
            log_event("progress_skipped", reason="cloud_position_ahead",
                      cloud_episode=cloud_ep, local_position=int(max(0, position)))
            return {"ok": True, "skipped": True, "episode": cloud_ep,
                    "reason": "云端播放位置更靠前，保持不变"}
    vid = ""
    try:
        _, episodes = H.get_episodes(series_id)
        target = next((it for it in episodes if int(it.get("index") or 0) == episode), None)
        if target:
            vid = str(target.get("vid") or "")
    except Exception:
        vid = ""
    now = int(time.time() * 1000)
    item = {
        "book_id": int(series_id),
        "book_id_str": series_id,
        "book_type": 2,
        "vid_index": episode,
        "chapter_index": episode,
        "read_timestamp_ms": now,
        "update_timestamp_ms": now,
        "current_play_position": int(max(0, position)),
        "player_accumulate_total_time": int(max(0, position)),
        "duration": int(max(0, duration)),
        "episode_cnt": int(max(0, total)),
        "is_delete": False,
        "use_soft_delete": True,
        "genre_type": 2150,
    }
    if vid and re.fullmatch(r"\d{8,24}", vid):
        item["vid"] = int(vid)
    if series and isinstance(series, dict):
        if series.get("title"):
            item["book_name"] = str(series["title"])[:120]
        if series.get("cover"):
            item["thumb_url"] = str(series["cover"])[:400]
    r = _call("POST", "/reading/bookapi/read_history/update/v",
              body={"update_datas": [item]})
    j = _json(r)
    if j.get("code") not in (0, "0"):
        log_event("progress_failed", series_id=series_id, episode=episode,
                  code=j.get("code"), message=str(j.get("message"))[:80])
        return {"ok": False, "code": j.get("code"),
                "error": j.get("message") or "上报失败"}
    fails = ((j.get("data") or {}).get("update_fail_datas") or []) if isinstance(j.get("data"), dict) else []
    # 成功也要记一笔：原先只有「跳过」才写日志，导致事后排查时
    # 「一次 progress 都没有」既可能是没上报、也可能是上报成功没记，
    # 无法区分（2026-10-07 排查时就踩了这个坑）。
    log_event("progress_ok", series_id=series_id, episode=episode,
              position=int(max(0, position)), total=int(max(0, total)),
              has_vid=bool(vid), failed=len(fails))
    return {"ok": not fails, "code": 0, "episode": episode, "failed": len(fails)}


def _history_raw(limit=30):
    """读一次云端历史原始列表（内部用）。"""
    r = _call("GET", "/reading/bookapi/read_history/list/v", extra={
        "book_type": "2", "offset": "0", "limit": str(int(limit)),
        "query_soft_deleted": "false", "is_first_load": "false",
        "last_min_read_timestamp_ms": "0", "full_field": "false"})
    return _json(r)


def remote_progress(series_id):
    """查单部剧在云端的进度（用于「取最新」合并，避免把进度改小）。"""
    if not is_logged_in():
        return {"ok": False, "error": "未登录红果账号"}
    series_id = str(series_id)
    if not re.fullmatch(r"[0-9]{8,24}", series_id):
        return {"ok": False, "error": "剧集标识不合法"}
    j = _history_raw(limit=100)
    if j.get("code") not in (0, "0"):
        return {"ok": False, "code": j.get("code"), "error": j.get("message")}
    for it in (((j.get("data") or {}).get("data_list")) or []):
        if str(it.get("book_id_str") or it.get("book_id") or "") == series_id:
            return {"ok": True,
                    "episode": int(it.get("vid_index") or it.get("chapter_index") or 0),
                    "position": int(it.get("current_play_position") or 0),
                    "updatedAt": int(it.get("read_timestamp_ms") or 0)}
    return {"ok": True, "episode": 0, "position": 0, "updatedAt": 0}


def remote_history(limit=30):
    if not is_logged_in():
        return {"ok": False, "error": "未登录红果账号"}
    r = _call("GET", "/reading/bookapi/read_history/list/v", extra={
        "book_type": "2", "offset": "0", "limit": str(int(limit)),
        "query_soft_deleted": "false", "is_first_load": "false",
        "last_min_read_timestamp_ms": "0", "full_field": "false"})
    j = _json(r)
    if j.get("code") not in (0, "0"):
        return {"ok": False, "code": j.get("code"), "error": j.get("message")}
    data = j.get("data") or {}
    items = []
    for it in (data.get("data_list") or []):
        items.append({
            "seriesId": str(it.get("book_id_str") or it.get("book_id") or ""),
            "title": it.get("book_name") or "",
            "cover": it.get("thumb_url") or "",
            "episode": int(it.get("vid_index") or it.get("chapter_index") or 1),
            "position": int(it.get("current_play_position") or 0),
            "total": int(it.get("episode_cnt") or 0),
            "updatedAt": int(it.get("read_timestamp_ms") or 0),
        })
    return {"ok": True, "total": int(data.get("total") or 0), "items": items}


# ---- 收藏（短剧书架） -----------------------------------------------------
def set_favorite(series_id, favorite):
    if not is_logged_in():
        return {"ok": False, "error": "未登录红果账号"}
    series_id = str(series_id)
    if not re.fullmatch(r"[0-9]{8,24}", series_id):
        return {"ok": False, "error": "剧集标识不合法"}
    body = {"update_bookshelf_video_list": [{
        "book_id": series_id,
        "book_type": 2,
        "video_shelf_operate_type": 0 if favorite else 1,
        "modify_time": int(time.time() * 1000),
        "group_name": "",
    }]}
    r = _call("POST", "/reading/bookapi/bookshelf/video/update/v", body=body)
    j = _json(r)
    if j.get("code") not in (0, "0"):
        return {"ok": False, "code": j.get("code"), "error": j.get("message")}
    return {"ok": True, "favorite": bool(favorite)}


def remote_favorites():
    if not is_logged_in():
        return {"ok": False, "error": "未登录红果账号"}
    r = _call("GET", "/reading/bookapi/bookshelf/video/list/v")
    j = _json(r)
    if j.get("code") not in (0, "0"):
        return {"ok": False, "code": j.get("code"), "error": j.get("message")}
    info = ((j.get("data") or {}).get("video_shelf_info") or [])
    items = []
    for it in info:
        items.append({
            "seriesId": str(it.get("series_id") or it.get("book_id") or ""),
            "contentType": int(it.get("content_type") or 0),
            "addedAt": int(it.get("collect_time") or it.get("modify_time") or 0),
        })
    # 书架接口只给 id，没有封面/标题。
    # 前端卡片必须要有 cover（还要能解析出封面地址），否则收藏页只有文字没有图。
    # 所以这里用剧集接口把封面和标题补齐。
    ids = [x["seriesId"] for x in items if x["seriesId"]]
    if ids:
        meta = _series_meta(ids)
        for x in items:
            m = meta.get(x["seriesId"]) or {}
            x["title"] = m.get("title") or ""
            x["cover"] = m.get("cover") or ""
            x["episodeCount"] = int(m.get("episodeCount") or 0)
    return {"ok": True, "items": items}


def _series_meta(series_ids):
    """批量取剧集的标题/封面/集数（收藏补封面用）。取不到就返回空，不影响主流程。"""
    out = {}
    ids = [str(s) for s in series_ids if re.fullmatch(r"[0-9]{8,24}", str(s or ""))]
    if not ids:
        return out
    try:
        # get_episodes_batch 返回 (剧集表, ...)，剧集表是 {series_id: 剧集信息}。
        batch = H.get_episodes_batch(ids)
        series_map = batch[0] if isinstance(batch, (list, tuple)) and batch else batch
        for sid, meta in (series_map or {}).items():
            if not isinstance(meta, dict):
                continue
            out[str(sid)] = {
                "title": meta.get("title") or "",
                "cover": meta.get("cover") or "",
                "episodeCount": int(meta.get("episode_cnt") or meta.get("episodeCount") or 0),
            }
    except Exception:
        pass
    return out

# ---- 从模拟器同步登录态 ---------------------------------------------------
def _adb():
    import subprocess
    adb = os.environ.get("ADB", r"D:\Tools\adb\adb.exe")
    dev = os.environ.get("ADB_DEVICE", "127.0.0.1:16448")
    return adb, dev


def _adb_shell(cmd, timeout=25):
    import subprocess
    adb, dev = _adb()
    try:
        out = subprocess.run([adb, "-s", dev, "shell", cmd],
                             capture_output=True, timeout=timeout)
    except Exception:
        return ""
    return out.stdout.decode("utf-8", "replace")


def sync_from_emulator():
    """在模拟器里已登录的前提下，把该会话同步到桌面端。

    只读 App 自己的会话数据（不改动 App、不注入进程）。
    """
    pkg = "com.phoenix.read"
    prefs = "/data/data/%s/shared_prefs" % pkg

    token = ""
    cookie = ""

    # 1) 会话数据可能落在若干 prefs 文件里，逐个找
    names = _adb_shell("ls %s 2>/dev/null" % prefs).split()
    for name in names:
        if not name.endswith(".xml"):
            continue
        low = name.lower()
        if not any(k in low for k in ("account", "passport", "session", "token",
                                      "login", "cookie", "sid", "user")):
            continue
        body = _adb_shell("cat %s/%s" % (prefs, name))
        if not body:
            continue
        if not token:
            for pat in (r'name="x-tt-token"[^>]*>([^<]+)<',
                        r'name="x_tt_token"[^>]*>([^<]+)<',
                        r'name="token"[^>]*>([^<]+)<'):
                m = re.search(pat, body)
                if m and len(m.group(1)) > 40:
                    token = m.group(1).strip()
                    break
        if not cookie:
            m = re.search(r'name="[^"]*cookie[^"]*"[^>]*>([^<]{40,})<', body, re.I)
            if m:
                cookie = m.group(1).strip()

    # 2) WebView 的 Cookie 持久化库（SQLite）：直接读字节再本地匹配，
    #    避免在 shell 里拼正则。
    if not cookie:
        import re as _re
        for cand in ("%s/../app_webview/Default/Cookies" % prefs,
                     "%s/../app_webview/Cookies" % prefs):
            blob = _adb_shell("cat %s 2>/dev/null" % cand, timeout=30)
            if not blob:
                continue
            m = _re.search(r"sessionid=([0-9a-f]{32})", blob)
            if m:
                sid = m.group(1)
                cookie = "sessionid=%s; sessionid_ss=%s; sid_tt=%s" % (sid, sid, sid)
                break

    if not (token or cookie):
        return {"ok": False, "error": "模拟器里没有可用的登录态；请先在模拟器里登录一次"}

    session = load_session() or {}
    if token:
        session["token"] = token
    if cookie:
        session["cookie"] = cookie
    session["saved_at"] = int(time.time())
    session["source"] = "emulator"
    save_session(session)

    # 立刻验一次，确认真的能用
    info = _json(_call("GET", "/reading/user/info/v", session=session))
    data = info.get("data") if isinstance(info.get("data"), dict) else {}
    if info.get("code") in (0, "0") and data:
        session["user_name"] = data.get("user_name") or ""
        session["uid"] = str(data.get("user_id") or "")
        save_session(session)
        log_event("sync_from_emulator", ok=True, has_token=bool(token),
                  has_cookie=bool(cookie), user=bool(session["user_name"]))
        return {"ok": True, "session": public_session(session)}
    log_event("sync_from_emulator", ok=False, has_token=bool(token),
              has_cookie=bool(cookie), code=info.get("code"))
    return {"ok": False, "error": "同步到的登录态无效，请在模拟器里重新登录"}
