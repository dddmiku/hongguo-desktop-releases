# -*- coding: utf-8 -*-
"""本地维护: 红果账号同步的 HTTP 路由。

单独成文件，server.py 只需要两行（import + register），
这样上游换版本时补丁面最小、最容易自动重注入。
"""
import re

from fastapi import HTTPException, Query


def register(app):
    import desktop_account as A

    def _sid(value):
        text = str(value or "").strip()
        if not re.fullmatch(r"[0-9]{8,24}", text):
            raise HTTPException(400, "Invalid series id")
        return text

    @app.get("/desktop/account/status")
    def desktop_account_status():
        return A.public_session()

    @app.post("/desktop/account/send_code")
    def desktop_account_send_code(mobile: str = Query(..., min_length=11, max_length=11)):
        result = A.send_code(mobile)
        if not result.get("ok"):
            raise HTTPException(400, str(result.get("error") or "验证码发送失败"))
        return {"sent": True, "hasTicket": bool(result.get("hasTicket")),
                "retryTime": result.get("retryTime")}

    @app.post("/desktop/account/login")
    def desktop_account_login(mobile: str = Query(..., min_length=11, max_length=11),
                              code: str = Query(..., min_length=4, max_length=8)):
        result = A.sms_login(mobile, code)
        if not result.get("ok"):
            detail = str(result.get("error") or "登录失败")
            if result.get("error_code") is not None:
                detail = "%s（错误码 %s）" % (detail, result["error_code"])
            raise HTTPException(400, detail)
        return result["session"]

    @app.get("/desktop/account/log")
    def desktop_account_log(limit: int = Query(30, ge=1, le=200)):
        """排查用：返回最近的账号操作记录（不含任何凭据）。"""
        return {"path": A.LOG_PATH, "items": A.read_log(limit)}

    @app.post("/desktop/account/logout")
    def desktop_account_logout():
        A.clear_session()
        return {"loggedIn": False}

    @app.get("/desktop/account/remote")
    def desktop_account_remote(limit: int = Query(30, ge=1, le=200)):
        history = A.remote_history(limit=limit)
        favorites = A.remote_favorites()
        return {
            "history": history.get("items") or [],
            "historyTotal": history.get("total") or 0,
            "favorites": favorites.get("items") or [],
            "historyOk": bool(history.get("ok")),
            "favoritesOk": bool(favorites.get("ok")),
            "error": history.get("error") or favorites.get("error") or "",
        }

    @app.post("/desktop/account/progress")
    def desktop_account_progress(series_id: str = Query(...), ep: int = Query(..., ge=1, le=100000),
                                 total: int = Query(0, ge=0, le=100000),
                                 position: int = Query(0, ge=0),
                                 duration: int = Query(0, ge=0),
                                 title: str = Query("", max_length=120),
                                 cover: str = Query("", max_length=500)):
        series = {"title": title, "cover": cover} if (title or cover) else None
        result = A.sync_progress(_sid(series_id), ep, total=total, position=position,
                                 duration=duration, series=series)
        if not result.get("ok"):
            raise HTTPException(502, str(result.get("error") or "进度同步失败"))
        return {"synced": True, "episode": result.get("episode")}

    @app.post("/desktop/account/favorite")
    def desktop_account_favorite(series_id: str = Query(...), favorite: bool = Query(...)):
        result = A.set_favorite(_sid(series_id), favorite)
        if not result.get("ok"):
            raise HTTPException(502, str(result.get("error") or "收藏同步失败"))
        return {"synced": True, "favorite": bool(favorite)}
