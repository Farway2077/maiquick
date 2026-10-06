import importlib.util
import json
from pathlib import Path

import httpx
import pytest

spec = importlib.util.spec_from_file_location("engine", Path(__file__).parents[1] / "mobile/engine.py")
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
TOKEN = "a" * 128
MUSIC = [
    {"id": "383", "title": "Link(CoF)", "type": "SD", "level": ["1", "2", "3", "4", "5"]},
    {"id": "10383", "title": "Link DX", "type": "DX", "level": ["1", "2", "3", "4"]},
]
RAW = [{"musicId": 383, "level": 3, "achievement": 1001234, "dx_score": 2200}]
OLD = [{"title": "Link(CoF)", "type": "SD", "level_index": 3, "achievements": 100.5, "dxScore": 2300, "fc": "app", "fs": "fsdp"}]


class Arcade:
    def __init__(self, raw=RAW):
        self.raw = raw
        self.bindings = []
        self.lookups = []

    async def get_uid_encrypted(self, qr):
        self.bindings.append(qr)
        return b"encrypted-stable-uid"

    async def get_user_scores(self, uid):
        self.lookups.append(uid)
        return self.raw


def transport(previous=OLD, redirect=False, receipt=None, username="player"):
    calls = []

    def handler(request):
        calls.append(request)
        assert request.url.host == "www.diving-fish.com"
        path = request.url.path.split("maimaidxprober")[-1]
        if path == "/music_data":
            assert "Import-Token" not in request.headers
            return httpx.Response(200, json=MUSIC)
        assert request.headers["Import-Token"] == TOKEN
        if path == "/player/validate":
            return httpx.Response(200, json={"message": "ok", "username": username})
        if path == "/player/records":
            if redirect:
                return httpx.Response(302, headers={"Location": "https://untrusted.invalid"})
            if previous is None:
                return httpx.Response(503)
            return httpx.Response(200, json={"records": previous})
        if path == "/player/update_records":
            return httpx.Response(200, json=receipt or {"message": "更新成功", "updates": 1, "creates": 0})
        raise AssertionError(path)

    return httpx.MockTransport(handler), calls


def test_mapping_keeps_flags_and_bests():
    records, skipped = engine.map_records(RAW, MUSIC, OLD)
    assert records == OLD
    assert skipped == 0


def test_dx_and_duplicate_records():
    raw = [{"musicId": 10383, "level": 3, "achievement": 999999, "dx_score": 1200},
           {"musicId": 10383, "level": 3, "achievement": 1000000, "dx_score": 1100}]
    records, _ = engine.map_records(raw, MUSIC, [])
    assert len(records) == 1
    assert records[0]["title"] == "Link DX"
    assert records[0]["achievements"] == 100.0
    assert records[0]["dxScore"] == 1200
    assert records[0]["fc"] == records[0]["fs"] == ""


def test_unknown_and_utage_are_counted():
    raw = RAW + [{"musicId": 199999, "level": 10, "achievement": 990000, "dx_score": 10},
                 {"musicId": 9999, "level": 3, "achievement": 990000, "dx_score": 10}]
    records, skipped = engine.map_records(raw, MUSIC, [])
    assert len(records) == 1 and skipped == 2


@pytest.mark.parametrize("data", [[], [{"musicId": 9999, "level": 3, "achievement": 1000000, "dx_score": 1}],
                                  [{"musicId": 383, "level": 3, "achievement": 2000000, "dx_score": 1}]])
def test_empty_or_invalid_scores_abort(data):
    with pytest.raises(engine.UserError):
        engine.map_records(data, MUSIC, [])


@pytest.mark.asyncio
async def test_binding_returns_stable_uid_without_upload():
    mock, calls = transport()
    arcade = Arcade()
    async with httpx.AsyncClient(transport=mock) as client:
        result = await engine.execute({"action": "bind", "token": TOKEN, "qr": "SGWCMAID1234567890"}, client, arcade)
    assert result["uid"] == "encrypted-stable-uid"
    assert arcade.bindings == ["SGWCMAID1234567890"]
    assert len(calls) == 1
    assert all(r.method == "GET" for r in calls)


@pytest.mark.asyncio
async def test_update_reuses_uid_and_does_not_request_qr():
    mock, calls = transport()
    arcade = Arcade()
    async with httpx.AsyncClient(transport=mock) as client:
        result = await engine.execute({"action": "update", "token": TOKEN, "uid": "saved-uid", "username": "player"}, client, arcade)
    assert result["count"] == 1
    assert arcade.lookups == [b"saved-uid"]
    assert arcade.bindings == []
    posts = [r for r in calls if r.method == "POST"]
    assert len(posts) == 1
    assert json.loads(posts[0].content) == OLD


@pytest.mark.asyncio
@pytest.mark.parametrize("previous,redirect", [(None, False), (OLD, True)])
async def test_failed_read_never_uploads(previous, redirect):
    mock, calls = transport(previous=previous, redirect=redirect)
    async with httpx.AsyncClient(transport=mock, follow_redirects=False) as client:
        with pytest.raises(engine.UserError):
            await engine.execute({"action": "update", "token": TOKEN, "uid": "saved-uid"}, client, Arcade())
    assert not any(r.method == "POST" for r in calls)
    assert all(r.url.host == "www.diving-fish.com" for r in calls)


@pytest.mark.asyncio
async def test_changed_fish_account_stops_before_arcade_or_upload():
    mock, calls = transport(username="someone-else")
    arcade = Arcade()
    async with httpx.AsyncClient(transport=mock) as client:
        with pytest.raises(engine.UserError):
            await engine.execute({"action": "update", "token": TOKEN, "uid": "saved-uid", "username": "player"}, client, arcade)
    assert not arcade.lookups
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_partial_receipt_is_not_reported_complete():
    mock, calls = transport(receipt={"message": "更新成功", "updates": 0, "creates": 0})
    async with httpx.AsyncClient(transport=mock) as client:
        with pytest.raises(engine.UserError, match="0/1"):
            await engine.execute({"action": "update", "token": TOKEN, "uid": "saved-uid"}, client, Arcade())


@pytest.mark.asyncio
async def test_invalid_token_never_contacts_network():
    mock, calls = transport()
    async with httpx.AsyncClient(transport=mock) as client:
        with pytest.raises(engine.UserError):
            await engine.execute({"action": "update", "token": "bad", "uid": "saved-uid"}, client, Arcade())
    assert not calls
