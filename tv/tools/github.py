"""Shared GitHub transport. Credentials stay in memory and never enter reports."""
import ctypes
from ctypes import wintypes
import json
import socket
import urllib.request
import urllib.error

def token():
    class Credential(ctypes.Structure):
        _fields_ = [("Flags", wintypes.DWORD), ("Type", wintypes.DWORD), ("TargetName", wintypes.LPWSTR),
            ("Comment", wintypes.LPWSTR), ("LastWritten", wintypes.FILETIME), ("CredentialBlobSize", wintypes.DWORD),
            ("CredentialBlob", ctypes.POINTER(ctypes.c_byte)), ("Persist", wintypes.DWORD),
            ("AttributeCount", wintypes.DWORD), ("Attributes", ctypes.c_void_p), ("TargetAlias", wintypes.LPWSTR), ("UserName", wintypes.LPWSTR)]
    native = ctypes.WinDLL("advapi32", use_last_error=True)
    native.CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.POINTER(ctypes.POINTER(Credential))]
    native.CredReadW.restype = wintypes.BOOL
    native.CredFree.argtypes = [ctypes.c_void_p]
    for name in ("git:https://github.com", "git:https://x-access-token@github.com"):
        ptr = ctypes.POINTER(Credential)()
        if native.CredReadW(name, 1, 0, ctypes.byref(ptr)):
            try:
                value = ctypes.string_at(ptr.contents.CredentialBlob, ptr.contents.CredentialBlobSize).decode("utf-16-le").strip()
                if ":" in value and not value.startswith(("ghp_", "github_pat_")):
                    value = value.rsplit(":", 1)[-1]
                if value:
                    return value
            finally:
                native.CredFree(ptr)
    raise RuntimeError("GitHub 凭据不存在")

def proxy():
    for port in (7897, 7890, 10809):
        try:
            with socket.create_connection(("127.0.0.1", port), .3):
                return "http://127.0.0.1:%d" % port
        except OSError:
            pass
    return None

def request(method, url, secret=None, data=None, raw=None, content_type=None):
    headers = {"User-Agent": "hongguo-desktop-publisher", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if secret:
        headers["Authorization"] = "Bearer " + secret
    body = raw if raw is not None else json.dumps(data).encode() if data is not None else None
    if body is not None:
        headers["Content-Type"] = content_type or "application/json"
    route = proxy()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": route, "https": route} if route else {}))
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with opener.open(req, timeout=240 if raw is not None else 25) as response:
            content = response.read()
            try:
                return response.status, json.loads(content)
            except ValueError:
                return response.status, content
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.loads(error.read())
        except ValueError:
            return error.code, {}
