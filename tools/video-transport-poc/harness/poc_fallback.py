#!/usr/bin/env python3
"""폴백·장애 시험 보조 명령 (fallback_matrix.sh 가 호출한다). 가상 장면만 쓴다.

  ticket                         업로드 티켓 발급 시도 -> HTTP 상태·오류 코드
  probe-jpeg [--seconds 6]       JPEG 경로로 잠깐 송출하고 AI가 받은 프레임 수를 본다(폴백 후 기존 경로 동작 확인)
  ws-try                         티켓을 받아 WS 연결을 시도하고, 수립 여부·종료 코드·걸린 시간을 본다
  hold --seconds N [--out F]     H.264 로 N초 송출하며 종료 코드·AI 수신 프레임 수·간격을 기록한다(--out 에 JSON)
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time

import requests

import poc_client as c


def cmd_ticket() -> dict:
    state = c.load_state()
    response = requests.post(f"{c.BE}/api/ward/camera/{state['cameraId']}/upload-ticket", headers=c.auth_headers(), timeout=10)
    try:
        body = response.json()
    except ValueError:
        body = {}
    return {"status": response.status_code, "code": body.get("code"), "hasTicket": bool((body.get("data") or {}).get("ticket"))}


def cmd_probe_jpeg(seconds: int) -> dict:
    state = c.load_state()
    sources = c.load_source_frames("calm", 15)
    session_id = state["sessionId"]
    c.create_ai_session(session_id, state["deviceId"])
    c.record_start(session_id)
    sender = c.run_jpeg(session_id, sources, seconds, 15)
    time.sleep(1.0)
    received = c.record_stop(session_id)
    return {"sent": sender["sent"], "errors": sender["errors"], "aiFrames": received.get("frames"),
            "gapMaxMs": (received.get("gapMs") or {}).get("max")}


async def _ws_try() -> dict:
    import websockets

    started = time.monotonic()
    state = c.load_state()
    ticket_response = requests.post(f"{c.BE}/api/ward/camera/{state['cameraId']}/upload-ticket",
                                    headers=c.auth_headers(), timeout=10)
    if ticket_response.status_code != 200:
        return {"phase": "ticket", "status": ticket_response.status_code,
                "elapsedSec": round(time.monotonic() - started, 2)}
    ticket = (ticket_response.json().get("data") or ticket_response.json())["ticket"]
    url = f"{c.BE.replace('http', 'ws')}/ws/camera-upload?ticket={ticket}"
    config = json.dumps({"type": "config", "codec": "avc1.42E01F", "width": c.WIDTH, "height": c.HEIGHT,
                         "framerate": 15, "format": "annexb"})
    try:
        async with websockets.connect(url, max_size=None, ping_interval=None, open_timeout=10) as ws:
            await ws.send(config)
            try:
                await asyncio.wait_for(ws.wait_closed(), timeout=4)
            except asyncio.TimeoutError:
                return {"phase": "ws", "opened": True, "closed": False, "elapsedSec": round(time.monotonic() - started, 2)}
            return {"phase": "ws", "opened": True, "closed": True, "closeCode": ws.close_code,
                    "closeReason": ws.close_reason, "elapsedSec": round(time.monotonic() - started, 2)}
    except Exception as exc:  # 핸드셰이크 거절(HTTP 오류)
        status = getattr(getattr(exc, "response", None), "status_code", None)
        return {"phase": "handshake", "opened": False, "status": status, "error": type(exc).__name__,
                "elapsedSec": round(time.monotonic() - started, 2)}


async def _hold(seconds: int, fps: int = 15) -> dict:
    import websockets

    state = c.load_state()
    session_id = state["sessionId"]
    sources = c.load_source_frames("calm", fps)
    aus = c.encode_h264(sources, min(seconds, 20) * fps, fps)
    c.create_ai_session(session_id, state["deviceId"])
    c.record_start(session_id)
    ticket = c.issue_ticket(state["cameraId"])
    url = f"{c.BE.replace('http', 'ws')}/ws/camera-upload?ticket={ticket['ticket']}"
    config = json.dumps({"type": "config", "codec": "avc1.42E01F", "width": c.WIDTH, "height": c.HEIGHT,
                         "framerate": fps, "format": "annexb"})
    sent = 0
    close_code = close_reason = None
    closed_at = None
    started = time.monotonic()
    try:
        async with websockets.connect(url, max_size=None, ping_interval=None) as ws:
            await ws.send(config)
            index = 0
            interval = 1.0 / fps
            total = seconds * fps
            while index < total:
                delay = started + index * interval - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
                if ws.close_code is not None:
                    break
                is_key, au = aus[index % len(aus)]
                try:
                    await ws.send(int(index * interval * 1_000_000).to_bytes(8, "big") + (b"\x01" if is_key else b"\x00") + au)
                except Exception:
                    break
                sent += 1
                index += 1
            await asyncio.sleep(0.3)
            close_code, close_reason = ws.close_code, ws.close_reason
            if close_code is not None:
                closed_at = round(time.monotonic() - started, 1)
    except Exception as exc:
        close_reason = f"exception:{type(exc).__name__}"
    time.sleep(1.5)
    received = c.record_stop(session_id)
    return {"sent": sent, "elapsedSec": round(time.monotonic() - started, 1), "closeCode": close_code,
            "closeReason": close_reason, "closedAtSec": closed_at, "aiFrames": received.get("frames"),
            "gapMs": received.get("gapMs")}


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("ticket")
    p = sub.add_parser("probe-jpeg")
    p.add_argument("--seconds", type=int, default=6)
    sub.add_parser("ws-try")
    h = sub.add_parser("hold")
    h.add_argument("--seconds", type=int, default=20)
    h.add_argument("--out", default=None)
    args = parser.parse_args()
    if args.cmd == "ticket":
        result = cmd_ticket()
    elif args.cmd == "probe-jpeg":
        result = cmd_probe_jpeg(args.seconds)
    elif args.cmd == "ws-try":
        result = asyncio.run(_ws_try())
    else:
        result = asyncio.run(_hold(args.seconds))
        if args.out:
            open(args.out, "w").write(json.dumps(result))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
