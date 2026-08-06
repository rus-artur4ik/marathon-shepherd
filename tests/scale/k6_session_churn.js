// k6 scale test — session create → wait → release churn against the manager.
//
// Usage:
//   k6 run tests/scale/k6_session_churn.js \
//     -e MSH_URL=http://localhost:16037 \
//     -e DEVICES_PER_SESSION=1 \
//     -e API_LEVEL=34 \
//     -e TTL_SECONDS=30 \
//     -e WAIT_TIMEOUT_SECONDS=5
//
// Profile stages are declared in `options.scenarios` below — adjust rate/duration
// through env (TARGET_RPS, DURATION) without editing the script.

import http from "k6/http";
import {check, sleep} from "k6";
import {Counter, Rate, Trend} from "k6/metrics";

const BASE_URL = __ENV.MSH_URL || "http://localhost:16037";
const DEVICES_PER_SESSION = parseInt(__ENV.DEVICES_PER_SESSION || "1", 10);
const API_LEVEL = __ENV.API_LEVEL || "34";
const DEVICE_TYPE = __ENV.DEVICE_TYPE || "";
const TTL_SECONDS = parseInt(__ENV.TTL_SECONDS || "30", 10);
const WAIT_TIMEOUT_SECONDS = parseInt(__ENV.WAIT_TIMEOUT_SECONDS || "5", 10);
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || "20", 10);
const DURATION = __ENV.DURATION || "30s";
const AUTH = __ENV.MSH_AUTH || ""; // Bearer <token> or empty

const createLatency = new Trend("create_latency_ms", true);
const waitLatency = new Trend("wait_latency_ms", true);
const releaseLatency = new Trend("release_latency_ms", true);
const allocationFailures = new Rate("allocation_failures");
const sessionsReady = new Counter("sessions_ready");
const sessionsPending = new Counter("sessions_pending");

export const options = {
  discardResponseBodies: false,
  thresholds: {
    "http_req_failed{endpoint:create}": ["rate<0.02"],
    "http_req_failed{endpoint:release}": ["rate<0.02"],
    "create_latency_ms": ["p(95)<500", "p(99)<1500"],
    "allocation_failures": ["rate<0.10"],
  },
  scenarios: {
    churn: {
      executor: "constant-arrival-rate",
      rate: TARGET_RPS,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(10, TARGET_RPS),
      maxVUs: Math.max(50, TARGET_RPS * 4),
    },
  },
};

function headers() {
  const h = { "Content-Type": "application/json" };
  if (AUTH) h["Authorization"] = AUTH;
  return h;
}

function createSession() {
  const payload = { maxDevices: DEVICES_PER_SESSION, api: API_LEVEL, ttlSeconds: TTL_SECONDS };
  if (DEVICE_TYPE) payload.deviceType = DEVICE_TYPE;
  const res = http.post(`${BASE_URL}/api/v1/sessions`, JSON.stringify(payload), {
    headers: headers(),
    tags: { endpoint: "create" },
  });
  createLatency.add(res.timings.duration);
  return res;
}

function waitForReady(sessionId) {
  const res = http.post(
    `${BASE_URL}/api/v1/sessions/${sessionId}/wait`,
    JSON.stringify({ timeoutSeconds: WAIT_TIMEOUT_SECONDS }),
    { headers: headers(), tags: { endpoint: "wait" } }
  );
  waitLatency.add(res.timings.duration);
  return res;
}

function releaseSession(sessionId) {
  const res = http.del(`${BASE_URL}/api/v1/sessions/${sessionId}`, null, {
    headers: headers(),
    tags: { endpoint: "release" },
  });
  releaseLatency.add(res.timings.duration);
  return res;
}

export default function () {
  const created = createSession();
  const createOk = check(created, { "create 200/201": r => r.status === 200 || r.status === 201 });
  if (!createOk) {
    allocationFailures.add(1);
    return;
  }
  let body;
  try {
    body = created.json();
  } catch (_) {
    allocationFailures.add(1);
    return;
  }
  const sessionId = body.id || body.sessionId;
  if (!sessionId) {
    allocationFailures.add(1);
    return;
  }

  const initialStatus = body.status;
  if (initialStatus !== "READY") {
    const waited = waitForReady(sessionId);
    check(waited, { "wait 200": r => r.status === 200 });
    let waitBody;
    try {
      waitBody = waited.json();
    } catch (_) {
      waitBody = {};
    }
    if (waitBody.status === "READY") {
      sessionsReady.add(1);
    } else {
      sessionsPending.add(1);
      allocationFailures.add(1);
    }
  } else {
    sessionsReady.add(1);
  }

  // Hold the session briefly, then release. Simulates a short marathon shard.
  sleep(0.2);
  const released = releaseSession(sessionId);
  check(released, { "release 200": r => r.status === 200 });
}
