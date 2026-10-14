#!/usr/bin/env python3
"""영상 전송 H.264 PoC 시험 하니스 - 같은 가상 장면을 JPEG 경로와 H.264(ws-h264) 경로로 각각 송출하고 측정한다.

가상 장면(ffmpeg lavfi)만 쓴다. 실제 가정 영상은 사용하지 않는다.

  prepare                          가상 장면 원본 생성(calm/busy)
  register                         시험 피보호자 토큰 발급 + 카메라 등록(상태는 state.json)
  run --mode jpeg|h264 --scene calm|busy --seconds N [--fps 15] [--reconnects K] [--name NAME]

측정(결과 JSON): 보낸 바이트, AI가 저장한 프레임 수·바이트·도착 간격(p50/p95/p99/max), 원본 대비 PSNR 요약,
H.264 경로의 인코더 쪽 지표. CPU 는 호스트의 docker stats 로 따로 잰다(measure.sh).

FE 의 실제 동작을 근사한다: JPEG = 캔버스 toBlob(0.8) ~ cv2 품질 80, 동시 2건·최신 1장. H.264 = Baseline, 1.5Mbps,
키프레임 2초, annexb, 프레임당 슬라이스 1개, 첫 메시지 config. 브라우저 하드웨어 인코더의 품질은 libx264 와 다르다 -
이 시험은 BE 중계·AI 변환 경로의 비용·지연·간격을 보는 것이다.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import statistics
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import imageio_ffmpeg
import numpy as np
import requests

BE = os.environ.get("POC_BE_URL", "http://be:6511")
AI = os.environ.get("POC_AI_URL", "http://ai:8000")
AI_KEY = os.environ.get("POC_AI_KEY", "")
JWT_SECRET = os.environ.get("POC_JWT_SECRET", "")
WORK = Path("/poc/work")
STATE = WORK / "state.json"
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
WIDTH, HEIGHT = 1280, 720
WARD_ID = "PW0001"

SCENES = {
    # calm: 움직임이 적은 테스트 패턴, busy: 전 화면이 계속 바뀌는 프랙탈(압축에 가장 불리)
    "calm": "testsrc2=size=1280x720:rate={fps}",
    "busy": "mandelbrot=size=1280x720:rate={fps}:maxiter=200",
}
SOURCE_SECONDS = 20


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mint_token(user_id: str, role: str = "WARD") -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = b64url(json.dumps({
        "sub": user_id, "jti": str(uuid.uuid4()), "email": "poc-ward@example.invalid", "role": role,
        "typ": "access", "iat": now, "exp": now + 3600,
    }).encode())
    signature = hmac.new(JWT_SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    return f"{header}.{payload}.{b64url(signature)}"


def auth_headers() -> dict:
    return {"Authorization": f"Bearer {mint_token(WARD_ID)}"}


def ai_headers() -> dict:
    return {"X-API-Key": AI_KEY}


def load_state() -> dict:
    return json.loads(STATE.read_text()) if STATE.exists() else {}


# ---------------------------------------------------------------- prepare

def source_path(scene: str, fps: int) -> Path:
    return WORK / f"source_{scene}_{fps}.mp4"


def prepare(fps: int) -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    for scene, lavfi in SCENES.items():
        out = source_path(scene, fps)
        if out.exists():
            continue
        subprocess.run([
            FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-i", lavfi.format(fps=fps), "-t", str(SOURCE_SECONDS),
            "-c:v", "libx264", "-crf", "10", "-pix_fmt", "yuv420p", str(out),
        ], check=True)
        print("원본 생성", out.name)


def load_source_frames(scene: str, fps: int) -> list[np.ndarray]:
    capture = cv2.VideoCapture(str(source_path(scene, fps)))
    frames = []
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        frames.append(frame)
    capture.release()
    if not frames:
        raise SystemExit("원본 프레임을 읽지 못했다(prepare 먼저)")
    return frames


# ---------------------------------------------------------------- register

def register() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    headers = auth_headers()
    response = requests.post(f"{BE}/api/ward/camera", json={"label": "거실"}, headers=headers, timeout=10)
    response.raise_for_status()
    data = response.json().get("data") or response.json()
    state = {"cameraId": data["id"], "sessionId": data["sessionId"], "deviceId": data["deviceId"],
             "transport": data.get("transport")}
    STATE.write_text(json.dumps(state))
    print(json.dumps(state, ensure_ascii=False))


# ---------------------------------------------------------------- 공통 송출 보조

def create_ai_session(session_id: str, camera_identifier: str) -> None:
    response = requests.post(f"{AI}/api/v1/stream-sessions", headers=ai_headers(), timeout=10, json={
        "sessionId": session_id, "cameraIdentifier": camera_identifier, "deviceType": "web"})
    response.raise_for_status()


def record_start(session_id: str) -> None:
    requests.post(f"{AI}/poc/record/{session_id}", headers=ai_headers(), timeout=10).raise_for_status()


def record_stop(session_id: str) -> dict:
    response = requests.post(f"{AI}/poc/stop/{session_id}", headers=ai_headers(), timeout=30)
    response.raise_for_status()
    return response.json()


def thumb(frame: np.ndarray) -> np.ndarray:
    return cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), (64, 36), interpolation=cv2.INTER_AREA).astype(np.float32)


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = float(np.mean((a.astype(np.float32) - b.astype(np.float32)) ** 2))
    return 99.0 if mse == 0 else 10 * np.log10(255.0 * 255.0 / mse)


def analyze_frames(directory: str, sources: list[np.ndarray], limit: int = 120) -> dict:
    """AI가 저장한 JPEG 를 원본 프레임에 맞춰(축소 썸네일 최근접) PSNR 을 잰다. 균등 간격으로 limit 장만 본다."""
    files = sorted(Path(directory).glob("*.jpg"))
    if not files:
        return {"compared": 0}
    thumbs = np.stack([thumb(f) for f in sources])
    step = max(1, len(files) // limit)
    values, indices = [], []
    for path in files[::step]:
        decoded = cv2.imread(str(path))
        if decoded is None:
            continue
        if decoded.shape[:2] != (HEIGHT, WIDTH):
            decoded = cv2.resize(decoded, (WIDTH, HEIGHT))
        t = thumb(decoded)
        index = int(np.argmin(np.mean((thumbs - t) ** 2, axis=(1, 2))))
        indices.append(index)
        values.append(psnr(decoded, sources[index]))
    return {
        "compared": len(values),
        "psnrMean": round(statistics.fmean(values), 2) if values else None,
        "psnrMin": round(min(values), 2) if values else None,
        "psnrP05": round(sorted(values)[int(len(values) * 0.05)], 2) if values else None,
    }


# ---------------------------------------------------------------- JPEG 경로

def run_jpeg(session_id: str, sources: list[np.ndarray], seconds: int, fps: int) -> dict:
    """FE 현행 JPEG 경로: 프레임마다 JPEG(0.8)를 /frame 에 POST. 동시 2건, 밀리면 건너뛴다."""
    pool = ThreadPoolExecutor(max_workers=2)
    in_flight = threading.Semaphore(2)
    lock = threading.Lock()
    stats = {"sent": 0, "bytes": 0, "skipped": 0, "errors": 0}
    http = requests.Session()

    def post(payload: bytes) -> None:
        try:
            response = http.post(f"{AI}/api/v1/stream-sessions/{session_id}/frame", headers=ai_headers(), timeout=10,
                                 files={"frame": ("frame.jpg", payload, "image/jpeg")})
            response.raise_for_status()
        except Exception:
            with lock:
                stats["errors"] += 1
        finally:
            in_flight.release()

    interval = 1.0 / fps
    start = time.monotonic()
    total = seconds * fps
    for i in range(total):
        target = start + i * interval
        delay = target - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        frame = sources[i % len(sources)]
        ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
        if not in_flight.acquire(blocking=False):
            stats["skipped"] += 1
            continue
        payload = buf.tobytes()
        stats["sent"] += 1
        stats["bytes"] += len(payload)
        pool.submit(post, payload)
    pool.shutdown(wait=True)
    return {"transport": "jpeg", **stats, "elapsedSec": round(time.monotonic() - start, 1)}


# ---------------------------------------------------------------- H.264 경로

def encode_h264(sources: list[np.ndarray], frames: int, fps: int) -> list[tuple[bool, bytes]]:
    """원본 프레임을 FE 와 같은 설정(Baseline, 1.5Mbps, 키프레임 2초, 슬라이스 1개, annexb)으로 인코딩해 액세스 유닛 목록으로."""
    cmd = [
        FFMPEG, "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{WIDTH}x{HEIGHT}", "-r", str(fps), "-i", "-",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline", "-preset", "ultrafast", "-tune", "zerolatency",
        "-b:v", "1500k", "-maxrate", "1500k", "-bufsize", "1500k", "-g", str(fps * 2), "-bf", "0", "-threads", "1",
        "-x264-params", "repeat-headers=1:sliced-threads=0:keyint=%d:min-keyint=%d:scenecut=0" % (fps * 2, fps * 2),
        "-f", "h264", "-",
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE)

    def feed() -> None:
        assert proc.stdin
        for i in range(frames):
            proc.stdin.write(np.ascontiguousarray(sources[i % len(sources)]).tobytes())
        proc.stdin.close()

    writer = threading.Thread(target=feed)
    writer.start()
    stream = proc.stdout.read()  # type: ignore[union-attr]
    writer.join()
    proc.wait()
    return split_access_units(stream)


def split_access_units(stream: bytes) -> list[tuple[bool, bytes]]:
    starts: list[int] = []
    i, n = 0, len(stream)
    while i + 3 <= n:
        j = stream.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        starts.append(j)
        i = j + 3
    units: list[tuple[bool, bytes]] = []
    current, key = 0, False
    for idx, pos in enumerate(starts):
        nal = stream[pos + 3] & 0x1F
        end = starts[idx + 1] if idx + 1 < len(starts) else n
        if nal == 5:
            key = True
        if nal in (1, 5):
            cut = end
            while cut > pos and idx + 1 < len(starts) and stream[cut - 1] == 0:
                cut -= 1
            units.append((key, stream[current:cut]))
            current, key = cut, False
    return units


def issue_ticket(camera_id: int) -> dict:
    response = requests.post(f"{BE}/api/ward/camera/{camera_id}/upload-ticket", headers=auth_headers(), timeout=10)
    if response.status_code != 200:
        raise SystemExit(f"티켓 발급 실패 {response.status_code}: {response.text[:200]}")
    body = response.json()
    return body.get("data") or body


async def run_h264(camera_id: int, aus: list[tuple[bool, bytes]], seconds: int, fps: int, reconnects: int) -> dict:
    import websockets

    total = seconds * fps
    interval = 1.0 / fps
    stats = {"sent": 0, "bytes": 0, "keyframes": 0, "keyframeRequests": 0, "reconnects": 0,
             "closeCodes": [], "ticketIssued": 0}
    config = json.dumps({"type": "config", "codec": "avc1.42E01F", "width": WIDTH, "height": HEIGHT,
                         "framerate": fps, "format": "annexb"})
    # 재연결 시험: 전체 구간을 (reconnects+1)등분해 경계마다 연결을 닫고 새 티켓으로 다시 붙인다
    boundaries = {int(total * (k + 1) / (reconnects + 1)) for k in range(reconnects)}
    start = time.monotonic()
    index = 0
    while index < total:
        ticket = issue_ticket(camera_id)
        stats["ticketIssued"] += 1
        url = f"{BE.replace('http', 'ws')}/ws/camera-upload?ticket={ticket['ticket']}"
        async with websockets.connect(url, max_size=None, ping_interval=None) as ws:
            await ws.send(config)
            # AI 쪽 새 디코더는 키프레임부터 시작해야 하므로 연결마다 키프레임이 있는 위치(GOP 경계)에서 시작한다
            index -= index % (fps * 2)

            async def reader() -> None:
                try:
                    async for message in ws:
                        if isinstance(message, str) and "keyframe_request" in message:
                            stats["keyframeRequests"] += 1
                except Exception:
                    pass

            read_task = asyncio.ensure_future(reader())
            try:
                while index < total:
                    delay = (start + index * interval) - time.monotonic()
                    if delay > 0:
                        await asyncio.sleep(delay)
                    is_key, au = aus[index % len(aus)]
                    ts_us = int(index * interval * 1_000_000)
                    await ws.send(ts_us.to_bytes(8, "big") + (b"\x01" if is_key else b"\x00") + au)
                    stats["sent"] += 1
                    stats["bytes"] += len(au) + 9
                    stats["keyframes"] += 1 if is_key else 0
                    index += 1
                    if index in boundaries:
                        boundaries.discard(index)
                        stats["reconnects"] += 1
                        break
            finally:
                read_task.cancel()
                code = ws.close_code
                await ws.close()
                stats["closeCodes"].append(ws.close_code if ws.close_code is not None else code)
    return {"transport": "h264", **stats, "elapsedSec": round(time.monotonic() - start, 1)}


# ---------------------------------------------------------------- run

def run(args: argparse.Namespace) -> None:
    state = load_state()
    if not state:
        raise SystemExit("register 먼저")
    sources = load_source_frames(args.scene, args.fps)
    session_id = state["sessionId"]
    create_ai_session(session_id, state["deviceId"])

    aus: list[tuple[bool, bytes]] = []
    if args.mode == "h264":
        encode_frames = min(args.seconds, 60) * args.fps
        aus = encode_h264(sources, encode_frames, args.fps)
        print(f"인코딩 완료 AU={len(aus)} bytes={sum(len(a) for _, a in aus)}", flush=True)

    record_start(session_id)
    marker = WORK / f"window_{args.name}.json"
    marker.write_text(json.dumps({"start": time.time()}))
    if args.mode == "jpeg":
        sender = run_jpeg(session_id, sources, args.seconds, args.fps)
    else:
        sender = asyncio.run(run_h264(state["cameraId"], aus, args.seconds, args.fps, args.reconnects))
    time.sleep(1.5)  # 마지막 프레임이 AI에서 처리되도록
    marker.write_text(json.dumps({"start": json.loads(marker.read_text())["start"], "end": time.time()}))
    received = record_stop(session_id)
    quality = analyze_frames(received.get("dir", ""), sources) if received.get("dir") else {}
    mapped_dir = received.get("dir", "")
    result = {
        "name": args.name, "mode": args.mode, "scene": args.scene, "fps": args.fps, "seconds": args.seconds,
        "sender": sender, "received": {k: v for k, v in received.items() if k != "dir"}, "quality": quality,
        "bytesPerSecondSent": round(sender["bytes"] / max(1.0, sender["elapsedSec"])),
        "frameDir": mapped_dir,
    }
    out = WORK / f"result_{args.name}.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--fps", type=int, default=15)
    sub.add_parser("register")
    r = sub.add_parser("run")
    r.add_argument("--mode", choices=["jpeg", "h264"], required=True)
    r.add_argument("--scene", choices=list(SCENES), default="busy")
    r.add_argument("--seconds", type=int, default=120)
    r.add_argument("--fps", type=int, default=15)
    r.add_argument("--reconnects", type=int, default=0)
    r.add_argument("--name", default=None)
    args = parser.parse_args()
    if args.cmd == "prepare":
        prepare(args.fps)
    elif args.cmd == "register":
        register()
    else:
        args.name = args.name or f"{args.mode}_{args.scene}_{args.seconds}s"
        run(args)


if __name__ == "__main__":
    sys.exit(main())
