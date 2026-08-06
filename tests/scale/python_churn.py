#!/usr/bin/env python3
"""
Lightweight load driver used when k6 is not available.

Opens N worker threads, each in a loop creates a session, (optionally waits),
and releases it. Reports p50/p95/p99 latency and error rate.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


@dataclass
class Stats:
    create_ms: list[float] = field(default_factory=list)
    wait_ms: list[float] = field(default_factory=list)
    release_ms: list[float] = field(default_factory=list)
    create_errors: int = 0
    release_errors: int = 0
    sessions_ready: int = 0
    sessions_pending: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def record_create(self, duration_ms: float, ok: bool) -> None:
        with self.lock:
            self.create_ms.append(duration_ms)
            if not ok:
                self.create_errors += 1

    def record_wait(self, duration_ms: float) -> None:
        with self.lock:
            self.wait_ms.append(duration_ms)

    def record_release(self, duration_ms: float, ok: bool) -> None:
        with self.lock:
            self.release_ms.append(duration_ms)
            if not ok:
                self.release_errors += 1

    def mark_ready(self) -> None:
        with self.lock:
            self.sessions_ready += 1

    def mark_pending(self) -> None:
        with self.lock:
            self.sessions_pending += 1


def _percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round(pct / 100.0 * (len(ordered) - 1)))))
    return ordered[index]


def _http_json(method: str, url: str, body: dict | None, timeout: float = 10.0) -> tuple[int, dict | None, float]:
    started = time.monotonic()
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url=url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload_bytes = response.read()
            elapsed_ms = (time.monotonic() - started) * 1000.0
            if not payload_bytes:
                return response.status, None, elapsed_ms
            return response.status, json.loads(payload_bytes.decode("utf-8")), elapsed_ms
    except urllib.error.HTTPError as error:
        elapsed_ms = (time.monotonic() - started) * 1000.0
        try:
            payload = json.loads(error.read().decode("utf-8"))
        except Exception:
            payload = None
        return error.code, payload, elapsed_ms
    except Exception:
        elapsed_ms = (time.monotonic() - started) * 1000.0
        return 0, None, elapsed_ms


def worker(args: argparse.Namespace, stats: Stats, stop_event: threading.Event) -> None:
    while not stop_event.is_set():
        status, body, elapsed = _http_json(
            method="POST",
            url=f"{args.url}/api/v1/sessions",
            body={"maxDevices": 1, "api": args.api_level, "ttlSeconds": 30}
        )
        ok = status in (200, 201) and body is not None and (body.get("id") or body.get("sessionId"))
        stats.record_create(elapsed, ok)
        if not ok:
            continue
        session_id = body.get("id") or body["sessionId"]
        session_status = body.get("status", "UNKNOWN")
        if session_status != "READY":
            wait_status, wait_body, wait_ms = _http_json(
                method="POST",
                url=f"{args.url}/api/v1/sessions/{session_id}/wait",
                body={"timeoutSeconds": 5}
            )
            stats.record_wait(wait_ms)
            final_status = (wait_body or {}).get("status", "UNKNOWN")
            if final_status == "READY":
                stats.mark_ready()
            else:
                stats.mark_pending()
        else:
            stats.mark_ready()
        rel_status, _rel_body, rel_ms = _http_json(
            method="DELETE",
            url=f"{args.url}/api/v1/sessions/{session_id}",
            body=None
        )
        stats.record_release(rel_ms, rel_status == 200)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:16037")
    parser.add_argument("--duration-seconds", type=int, default=30)
    parser.add_argument("--target-rps", type=int, default=20)
    parser.add_argument("--api-level", default="34")
    parser.add_argument("--workers", type=int, default=None, help="override auto-sized worker count")
    args = parser.parse_args()

    worker_count = args.workers or max(4, args.target_rps * 2)
    stop_event = threading.Event()
    stats = Stats()

    print(
        f">> python_churn starting: url={args.url} duration={args.duration_seconds}s "
        f"target_rps={args.target_rps} workers={worker_count}"
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count) as executor:
        for _ in range(worker_count):
            executor.submit(worker, args, stats, stop_event)

        # Live progress reporter — ticks every 2 seconds. Prints a human line
        # (picked up by the dashboard's Output tail) plus a machine marker
        # (`MSH_TEST_PROGRESS: elapsed/duration`) so the stage row shows a
        # moving counter instead of a stuck "testing [1/1]".
        start_monotonic = time.monotonic()
        deadline = start_monotonic + args.duration_seconds
        tick_interval = 2.0
        next_tick = start_monotonic + tick_interval
        print("MSH_PHASE: testing", flush=True)
        while time.monotonic() < deadline:
            sleep_for = min(deadline - time.monotonic(), next_tick - time.monotonic())
            if sleep_for > 0:
                time.sleep(sleep_for)
            if time.monotonic() < next_tick:
                continue
            next_tick += tick_interval
            elapsed = int(time.monotonic() - start_monotonic)
            if elapsed >= args.duration_seconds:
                break
            with stats.lock:
                sessions_total = len(stats.create_ms)
                sessions_ok = stats.sessions_ready
                create_errors = stats.create_errors
            rps = sessions_total / elapsed if elapsed > 0 else 0.0
            timestamp = time.strftime("%H:%M:%S")
            print(
                f"[{timestamp}] churn · {elapsed:>3}s/{args.duration_seconds}s"
                f"  ·  {sessions_total} sessions ({sessions_ok} ready)"
                f"  ·  {rps:.1f} RPS"
                f"  ·  {create_errors} errors",
                flush=True,
            )
            # Marker uses time-based ratio so the runner can render
            # `testing [elapsed/duration]` with a monotonic counter.
            print(f"MSH_TEST_PROGRESS: {elapsed}/{args.duration_seconds}", flush=True)
        stop_event.set()

    def report(bucket_name: str, values: list[float]) -> None:
        if not values:
            print(f"   {bucket_name}: no samples")
            return
        print(
            f"   {bucket_name}: samples={len(values)} "
            f"mean={statistics.mean(values):.1f}ms "
            f"p50={_percentile(values, 50):.1f}ms "
            f"p95={_percentile(values, 95):.1f}ms "
            f"p99={_percentile(values, 99):.1f}ms"
        )

    total_sessions = len(stats.create_ms)
    create_error_rate = stats.create_errors / max(1, total_sessions)
    release_error_rate = stats.release_errors / max(1, len(stats.release_ms))

    print(">> Results")
    report("create_latency", stats.create_ms)
    report("wait_latency", stats.wait_ms)
    report("release_latency", stats.release_ms)
    print(
        f"   sessions: total={total_sessions} ready={stats.sessions_ready} "
        f"pending={stats.sessions_pending}"
    )
    print(
        f"   errors: create_rate={create_error_rate:.3f} release_rate={release_error_rate:.3f}"
    )

    # CI gate — fail if create errors exceed 2% or p95 latency is pathological.
    thresholds_failed: list[str] = []
    if create_error_rate > 0.02:
        thresholds_failed.append(f"create error rate {create_error_rate:.3f} > 0.02")
    p95_create = _percentile(stats.create_ms, 95)
    if p95_create > 2000:
        thresholds_failed.append(f"create p95 {p95_create:.1f}ms > 2000ms")
    if thresholds_failed:
        print("!! Threshold violations:", "; ".join(thresholds_failed))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
