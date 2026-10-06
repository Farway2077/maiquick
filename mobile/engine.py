"""Phone-local score updater. JSON in/out; credentials never passed in argv."""
import asyncio
import json
import math
import re
import sys
from datetime import datetime, timezone

import httpx

FISH = "https://www.diving-fish.com/api/maimaidxprober"


class UserError(Exception):
    pass


def emit(kind, **data):
    print(json.dumps({"event": kind, **data}, ensure_ascii=False), flush=True)


def qr_string(text):
    match = re.search(r"SGWCMAID[0-9A-Za-z]+", text.strip())
    if not match or len(match[0]) < 12 or len(match[0]) > 1024:
        raise UserError("需要以 SGWCMAID 开头的玩家登录二维码字符串。请复制新生成的二维码内容。")
    return match[0]


def token_string(text):
    text = text.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{128}", text):
        raise UserError("水鱼 Token 格式不正确，请复制完整的 128 位成绩导入 Token。")
    return text


async def fish_request(client, method, path, token=None, **kwargs):
    headers = {"Import-Token": token} if token else {}
    try:
        response = await client.request(method, FISH + path, headers=headers, **kwargs)
    except httpx.HTTPError:
        raise UserError("连接水鱼失败，请检查网络后再试。") from None
    if response.is_redirect:
        raise UserError("水鱼接口发生重定向，已停止以保护 Token。")
    if response.status_code in (400, 401, 403):
        raise UserError("水鱼 Token 无效或未同意用户协议，请在水鱼官网检查后重新设置。")
    if response.status_code == 429:
        raise UserError("水鱼请求次数达到限制，请稍后再试。")
    if response.status_code >= 400:
        raise UserError(f"水鱼服务暂时不可用（HTTP {response.status_code}），请稍后再试。")
    try:
        return response.json()
    except ValueError:
        raise UserError("水鱼返回了无法识别的数据，更新未完成。") from None


def map_records(raw, music, previous):
    """Map arcade IDs, retain flags and bests; never manufacture missing flags."""
    songs = {int(song["id"]): song for song in music}
    old = {(str(r["title"]), str(r["type"]), int(r["level_index"])): r for r in previous}
    mapped = {}
    skipped = 0
    for item in raw:
        try:
            music_id = int(item["musicId"])
            level = int(item["level"])
            achievement = int(item["achievement"]) / 10000
            dx = int(item["dx_score"])
        except (ValueError, KeyError, TypeError):
            raise UserError("机台成绩数据格式发生变化，已停止上传。") from None
        if music_id >= 100000 or level not in range(5):
            skipped += 1
            continue
        if not math.isfinite(achievement) or not 0 <= achievement <= 101 or dx < 0:
            raise UserError("机台返回了异常分数，已停止上传。")
        song = songs.get(music_id)
        expected_type = "DX" if music_id >= 10000 else "SD"
        if not song or song.get("type") != expected_type or level >= len(song.get("level", [])):
            skipped += 1
            continue
        key = (song["title"], expected_type, level)
        baseline = old.get(key, {})
        prior = mapped.get(key, {})
        mapped[key] = {
            "title": song["title"], "type": expected_type, "level_index": level,
            "achievements": max(achievement, float(baseline.get("achievements", 0)), prior.get("achievements", 0)),
            "dxScore": max(dx, int(baseline.get("dxScore", 0)), prior.get("dxScore", 0)),
            "fc": baseline.get("fc", ""), "fs": baseline.get("fs", ""),
        }
    if not mapped:
        raise UserError("没有可上传的常规谱面成绩，未修改水鱼数据。")
    return list(mapped.values()), skipped


async def execute(request, client=None, arcade=None):
    if client is None:
        async with httpx.AsyncClient(timeout=45, follow_redirects=False) as owned:
            return await execute(request, owned, arcade)
    if request.get("action") == "selftest":
        import platform
        from maimai_ffi import arcade as local_arcade
        music = await fish_request(client, "GET", "/music_data")
        return {"python": platform.python_version(), "arch": platform.machine(), "ffi": callable(local_arcade.get_user_scores), "songs": len(music)}
    token = token_string(request.get("token", ""))
    action = request.get("action")
    emit("progress", message="正在验证水鱼账号…")
    account = await fish_request(client, "GET", "/player/validate", token)
    username = account.get("username", "")
    if not username:
        raise UserError("无法确认水鱼账号，已停止。")
    if action == "validate":
        return {"username": username}
    if arcade is None:
        from maimai_ffi import arcade
    if action == "bind":
        qr = qr_string(request.get("qr", ""))
        emit("progress", message="正在将二维码换成长期 UID…")
        try:
            credential = await asyncio.wait_for(arcade.get_uid_encrypted(qr), 60)
        except Exception:
            raise UserError("绑定失败：二维码可能已过期，或机台接口暂时不可用。请生成新二维码后再试。") from None
        uid = credential.decode()
        if not uid or len(uid) > 4096:
            raise UserError("接口未返回有效 UID，未保存绑定。")
        return {"username": username, "uid": uid}
    if action != "update":
        raise UserError("不支持的操作。")
    uid = request.get("uid", "")
    if not isinstance(uid, str) or not uid or len(uid) > 4096:
        raise UserError("请先绑定一次二维码字符串。")
    if request.get("username") and request["username"] != username:
        raise UserError("水鱼账号已变化，请重新绑定，避免上传到错误账号。")
    emit("progress", message="正在读取机台最新成绩…")
    try:
        raw = await asyncio.wait_for(arcade.get_user_scores(uid.encode()), 90)
    except Exception:
        raise UserError("机台取分失败，请检查网络后重试。通常无需重新复制二维码。") from None
    if not isinstance(raw, list) or not raw:
        raise UserError("机台返回空成绩，未修改水鱼数据。")
    emit("progress", message="正在保留已有 FC/FS 标记并匹配曲目…")
    # A failed previous-record read must abort, otherwise flags would be erased.
    previous = await fish_request(client, "GET", "/player/records", token)
    if not isinstance(previous, dict) or not isinstance(previous.get("records"), list):
        raise UserError("无法读取水鱼旧成绩，已停止以保留 FC/FS 标记。")
    music = await fish_request(client, "GET", "/music_data")
    if not isinstance(music, list):
        raise UserError("水鱼曲目表格式发生变化，已停止。")
    records, skipped = map_records(raw, music, previous["records"])
    emit("progress", message=f"正在上传 {len(records)} 条成绩…")
    receipt = await fish_request(client, "POST", "/player/update_records", token, json=records)
    if receipt.get("message") != "更新成功" or "updates" not in receipt or "creates" not in receipt:
        raise UserError("水鱼没有返回完整成功回执，请查看水鱼成绩后再重试。")
    changed = int(receipt["updates"]) + int(receipt["creates"])
    if changed != len(records):
        raise UserError(f"水鱼仅接受了 {changed}/{len(records)} 条成绩。部分曲目未匹配，请稍后重试。")
    return {"username": username, "count": len(records), "updates": int(receipt["updates"]), "creates": int(receipt["creates"]), "skipped": skipped, "time": datetime.now(timezone.utc).isoformat()}


def main():
    try:
        request = json.loads(sys.stdin.readline(32768))
        result = asyncio.run(asyncio.wait_for(execute(request), 180))
        emit("result", **result)
    except UserError as error:
        emit("error", message=str(error))
        return 1
    except TimeoutError:
        emit("error", message="更新超时，请保持网络连接后重试。")
        return 1
    except Exception:
        emit("error", message="运行失败，请检查网络或重新打开工具。账户凭据未输出到日志。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
