#!/usr/bin/env groovy
/**
 * Unit tests for vars/shepherdTest.groovy.
 *
 * Tests the Groovy logic in isolation — no running service, no Docker required.
 * A mock 'sh' binding intercepts all curl calls and returns canned HTTP responses.
 *
 * Run from repo root:
 *   groovy tests/unit/jenkins_unit_test.groovy
 */
import groovy.json.JsonOutput
import groovy.transform.Field

// ── Shared test state (must be @Field so methods can access them) ─────────────

@Field int passed = 0
@Field int failed = 0
@Field List<String> failureNames = []

@Field File SCRIPT_FILE = new File("vars/shepherdTest.groovy")

// Session fixture — valid READY session with 2 adb servers
@Field Map SESSION = [
    id: "sess-42", status: "READY",
    allocatedDevices: 2, requestedDevices: 2,
    expiresAt: "2099-01-01T00:00:00Z",
    adbServers: [[host: "10.0.0.1", port: 7600], [host: "10.0.0.1", port: 7601]],
]
@Field Map RELEASE = [status: "released"]
@Field Map PARAMS  = [managerUrl: "http://mock:6037", devices: 2, api: "34", ttl: 60, deviceType: "emulator"]

// ── Test runner ───────────────────────────────────────────────────────────────

void check(String name, Closure body) {
    try {
        body()
        System.out.println("  [OK]   ${name}")
        passed++
    } catch (Throwable t) {
        String msg = t.message?.readLines()?.find { it?.trim() } ?: t.toString()
        System.out.println("  [FAIL] ${name}\n         ${msg}")
        failureNames << name
        failed++
    }
}

// ── Mock sh ───────────────────────────────────────────────────────────────────

/**
 * Routes sh(returnStdout: true) calls by matching the generated bash script text.
 * Routes are evaluated in order; first match wins.
 *
 * Special handling:
 *   sh(returnStatus: true)         → always returns 0  (command -v checks)
 *   sh() without return flags      → no-op, returns null
 *   script containing marathon-report → absent-report response
 */
class MockSh {
    final List<Map> routes = []
    final List<String> scripts = []

    MockSh on(Closure<Boolean> when, def then) {
        routes << [when: when, then: then]
        return this
    }

    def call(Map args) {
        String script = (args.script ?: "") as String
        scripts << script
        if (args.returnStatus) return 0
        if (!args.returnStdout) return null
        if (script.contains("marathon-report")) return "missing\n0\n0\nnot-created\n"
        for (Map r : routes) {
            if ((r.when as Closure).call(script)) {
                def v = r.then
                return v instanceof Closure ? (v as Closure).call() : v
            }
        }
        throw new AssertionError("No route matched sh(returnStdout):\n${script.take(200)}")
    }

    boolean calledWith(String fragment) { scripts.any { it.contains(fragment) } }
}

// ── Binding factory ───────────────────────────────────────────────────────────

Binding makeBinding(MockSh mockSh, List<String> capturedErrors = []) {
    Binding b = new Binding()
    b.setVariable("env", [:])
    b.setVariable("echo",  { String m -> /* suppress output in tests */ })
    b.setVariable("error", { String m ->
        capturedErrors << m
        throw new RuntimeException("error: ${m}")
    })
    b.setVariable("sh", { Map a -> mockSh.call(a) })
    return b
}

def load(MockSh mockSh, List<String> capturedErrors = []) {
    new GroovyShell(makeBinding(mockSh, capturedErrors)).parse(SCRIPT_FILE)
}

// ── Response helper ───────────────────────────────────────────────────────────

// Simulates curl output: JSON body + newline + HTTP status code.
// Matches exactly what buildCurlScript produces:
//   cat "$response_file"; printf '\n%s' "$status_code"
String curlResp(int status, Map body) { "${JsonOutput.toJson(body)}\n${status}" }

// ── Standard routes ───────────────────────────────────────────────────────────

// POST /api/v1/sessions  → URL ends with  sessions'  (the closing quote, no session id after)
// DELETE /api/v1/sessions/{id} → URL contains the session id
MockSh happySh() {
    new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'")  }, curlResp(201, SESSION))
        .on({ s -> s.contains("sess-42")             }, curlResp(200, RELEASE))
}

// ── Prerequisite check ────────────────────────────────────────────────────────

if (!SCRIPT_FILE.exists()) {
    System.err.println("ERROR: vars/shepherdTest.groovy not found — run from repo root.")
    System.exit(1)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

System.out.println("\nvars/shepherdTest.groovy — unit tests")
System.out.println("=".multiply(56))

// ── Closure dispatch ──────────────────────────────────────────────────────────

check("arity-1 closure receives parsed adbServers list") {
    List<Map> got = []
    load(happySh()).call(PARAMS, { List<Map> s -> got.addAll(s) })
    assert got.size() == 2
    assert got[0].host == "10.0.0.1" && got[0].port == 7600
    assert got[1].port == 7601
}

check("arity-2 closure receives adbServers and full session map") {
    Map gotSession = [:]
    load(happySh()).call(PARAMS, { List<Map> s, Map sess -> gotSession.putAll(sess) })
    assert gotSession.id == "sess-42"
    assert gotSession.status == "READY"
    assert gotSession.allocatedDevices == 2
}

check("arity-0 closure is called without arguments") {
    boolean ran = false
    load(happySh()).call(PARAMS, { -> ran = true })
    assert ran
}

// ── Session lifecycle ─────────────────────────────────────────────────────────

check("DELETE /sessions/{id} is called after successful closure") {
    MockSh sh = happySh()
    load(sh).call(PARAMS, { List<Map> s -> /* no-op */ })
    assert sh.calledWith("'DELETE'"), "Expected DELETE request for session release"
}

check("DELETE is still called when closure throws (finally block)") {
    MockSh sh = happySh()
    try {
        load(sh).call(PARAMS, { List<Map> s -> throw new RuntimeException("closure blew up") })
    } catch (RuntimeException ignored) {}
    assert sh.calledWith("'DELETE'"), "Expected DELETE even after closure failure"
}

check("closure exception is propagated after cleanup") {
    boolean threw = false
    try {
        load(happySh()).call(PARAMS, { List<Map> s -> throw new RuntimeException("deliberate failure") })
    } catch (RuntimeException e) {
        threw = e.message.contains("deliberate failure")
    }
    assert threw, "Expected the original closure exception to be re-thrown"
}

// ── Error handling ────────────────────────────────────────────────────────────

check("POST 503 → error() called, closure never runs") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, curlResp(503, [error: "no capacity"]))
    List<String> errors = []
    boolean closureRan = false
    try {
        load(sh, errors).call(PARAMS, { List<Map> s -> closureRan = true })
    } catch (RuntimeException ignored) {}
    assert !closureRan,                          "Closure must not run when session creation fails"
    assert errors.any { it.contains("503") },    "Expected error mentioning HTTP 503"
}

check("session with empty adbServers → error() called") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, curlResp(201, SESSION + [adbServers: []]))
        .on({ s -> s.contains("sess-42")            }, curlResp(200, RELEASE))
    List<String> errors = []
    try {
        load(sh, errors).call(PARAMS, { List<Map> s -> })
    } catch (RuntimeException ignored) {}
    assert errors.any { it.toLowerCase().contains("adbservers") || it.contains("sess-42") },
        "Expected error about missing adbServers"
}

check("malformed curl response (no status line) → error() called") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, "response-without-newline")
    List<String> errors = []
    try {
        load(sh, errors).call(PARAMS, { List<Map> s -> })
    } catch (RuntimeException ignored) {}
    assert errors.size() > 0, "Expected error() to be called for unparseable response"
}

// ── normalizeManagerUrl ───────────────────────────────────────────────────────

check("trailing slashes in managerUrl are stripped before building request URLs") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, curlResp(201, SESSION))
        .on({ s -> s.contains("sess-42")            }, curlResp(200, RELEASE))
    load(sh).call(PARAMS + [managerUrl: "http://mock:6037///"], { List<Map> s -> })
    assert !sh.scripts.any { it.contains("///") },
        "Expected trailing slashes to be stripped from the URL"
}

// ── normalizeDeviceType ───────────────────────────────────────────────────────

check("deviceType is normalised to lower case in the POST request body") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, curlResp(201, SESSION))
        .on({ s -> s.contains("sess-42")            }, curlResp(200, RELEASE))
    load(sh).call(PARAMS + [deviceType: "EMULATOR"], { List<Map> s -> })
    assert sh.scripts.any { it.contains('"deviceType":"emulator"') },
        "Expected deviceType to be normalised to lowercase"
}

check("null deviceType omits the field from the POST request body") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, curlResp(201, SESSION))
        .on({ s -> s.contains("sess-42")            }, curlResp(200, RELEASE))
    Map paramsNoType = new HashMap(PARAMS)
    paramsNoType.remove("deviceType")
    load(sh).call(paramsNoType, { List<Map> s -> })
    assert !sh.scripts.any { it.contains('"deviceType"') },
        "Expected no deviceType field when not specified"
}

// ── parseAdbServers ───────────────────────────────────────────────────────────

check("adbServer entries with null host or port are filtered out") {
    Map sessionBadServers = SESSION + [adbServers: [
        [host: "10.0.0.1", port: 7600],  // valid
        [host: null,        port: 7601],  // null host → filtered
        [host: "10.0.0.2",  port: null],  // null port → filtered
    ]]
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") }, curlResp(201, sessionBadServers))
        .on({ s -> s.contains("sess-42")            }, curlResp(200, RELEASE))
    List<Map> got = []
    load(sh).call(PARAMS, { List<Map> s -> got.addAll(s) })
    assert got.size() == 1,             "Expected only the valid adbServer entry"
    assert got[0].host == "10.0.0.1"
    assert got[0].port == 7600
}

// ── Summary ───────────────────────────────────────────────────────────────────

System.out.println()
System.out.println("=".multiply(56))
if (failed == 0) {
    System.out.println("All ${passed} test(s) passed.")
} else {
    System.out.println("${failed} of ${passed + failed} test(s) FAILED:")
    failureNames.each { System.out.println("  - ${it}") }
    System.exit(1)
}
