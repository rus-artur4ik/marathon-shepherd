#!/usr/bin/env groovy
import groovy.json.JsonOutput
import groovy.transform.Field

@Field int passed = 0
@Field int failed = 0
@Field List<String> failureNames = []
@Field File SCRIPT_FILE = new File("vars/runMarathonWithShepherd.groovy")
@Field String WORKSPACE = "/tmp/jenkins-workspace"

@Field Map READY_SESSION = [
    id: "sess-42",
    status: "READY",
    requestedDevices: 2,
    allocatedDevices: 2,
    api: ">=34",
    deviceType: "emulator",
    adbServers: [[host: "10.0.0.1", port: 7600], [host: "10.0.0.1", port: 7601]],
    queuePosition: null,
    createdAt: "2099-01-01T00:00:00Z",
    expiresAt: "2099-01-01T01:00:00Z",
]

@Field Map PENDING_SESSION = READY_SESSION + [
    status: "PENDING",
    allocatedDevices: 0,
    adbServers: [],
    queuePosition: 1,
]

@Field Map RELEASE = [status: "released"]

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

class MockSh {
    final List<Map> routes = []
    final List<Map> calls = []

    MockSh on(Closure<Boolean> when, def then) {
        routes << [when: when, then: then]
        return this
    }

    def call(Map args) {
        calls << new LinkedHashMap(args)
        String script = (args.script ?: "") as String
        if (args.returnStatus) {
            return 0
        }
        for (Map route : routes) {
            if ((route.when as Closure).call(script)) {
                def value = route.then
                return value instanceof Closure ? (value as Closure).call(args) : value
            }
        }
        if (!args.returnStdout) {
            return null
        }
        throw new AssertionError("No route matched sh(returnStdout):\n${script.take(300)}")
    }

    Map lastCallWithLabel(String label) {
        return calls.reverse().find { it.label == label }
    }

    boolean anyScriptContains(String fragment) {
        return calls.any { ((it.script ?: "") as String).contains(fragment) }
    }
}

class MockCurrentBuild {
    String description
}

Binding makeBinding(
    MockSh mockSh,
    Map<String, String> files = [:],
    List<String> echoes = [],
    List<String> errors = [],
    Closure<Long> nowProvider = null
) {
    Binding binding = new Binding()
    binding.setVariable("env", [:])
    binding.setVariable("currentBuild", new MockCurrentBuild())
    binding.setVariable("pwd", { -> WORKSPACE })
    binding.setVariable("echo", { String message -> echoes << message })
    binding.setVariable("error", { String message ->
        errors << message
        throw new RuntimeException("error: ${message}")
    })
    binding.setVariable("sh", { Map args -> mockSh.call(args) })
    binding.setVariable("writeFile", { Map args -> files[args.file as String] = args.text as String })
    if (nowProvider != null) {
        binding.setVariable("mshNowMillis", nowProvider)
    }
    return binding
}

def load(
    MockSh mockSh,
    Map<String, String> files = [:],
    List<String> echoes = [],
    List<String> errors = [],
    Closure<Long> nowProvider = null
) {
    new GroovyShell(makeBinding(mockSh, files, echoes, errors, nowProvider)).parse(SCRIPT_FILE)
}

String curlResp(int status, Map body) {
    "${JsonOutput.toJson(body)}\n${status}"
}

MockSh happySh() {
    new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") && s.contains("'POST'") }, curlResp(201, READY_SESSION))
        .on({ s -> s.contains("DELETE") && s.contains("sess-42") }, curlResp(200, RELEASE))
}

if (!SCRIPT_FILE.exists()) {
    System.err.println("ERROR: vars/runMarathonWithShepherd.groovy not found — run from repo root.")
    System.exit(1)
}

System.out.println("\nvars/runMarathonWithShepherd.groovy — unit tests")
System.out.println("=".multiply(68))

check("runs gradle task with init script and exported runtime env") {
    MockSh sh = happySh()
    Map<String, String> files = [:]
    List<String> echoes = []
    def script = load(sh, files, echoes)

    script.call([maxDevices: 2, api: ">=34", deviceType: "EMULATOR", task: ":app:marathon"])

    Map runTaskCall = sh.lastCallWithLabel("Marathon / Run Task")
    assert runTaskCall != null
    assert (runTaskCall.script as String).contains("./gradlew -I")
    assert (runTaskCall.script as String).contains(":app:marathon")
    assert (runTaskCall.script as String).contains("export MSH_SESSION_ID='sess-42'")
    assert files.keySet().any { it.endsWith("/adb-servers.json") }
    assert files.keySet().any { it.endsWith("/shepherd.init.gradle") }
    String initScript = files.find { it.key.endsWith("/shepherd.init.gradle") }?.value
    assert initScript != null
    assert initScript.contains("loadMarathonClass(Object owner, String className)")
    assert initScript.contains("'com.malinskiy.marathon.config.vendor.android.AdbEndpoint'")
    assert initScript.contains("task.class.name == 'com.malinskiy.marathon.gradle.task.MarathonRunTask'")
    assert !initScript.contains("new com.malinskiy.marathon.config.vendor.android.AdbEndpoint")
    assert !initScript.contains("project.tasks.withType(com.malinskiy.marathon.gradle.task.MarathonRunTask)")
    assert ((script.binding.getVariable("currentBuild") as MockCurrentBuild).description).contains("MSH 2/2")
}

check("waits through pending state before running gradle task") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") && s.contains("'POST'") }, curlResp(201, PENDING_SESSION))
        .on({ s -> s.contains("/wait'") }, curlResp(200, READY_SESSION))
        .on({ s -> s.contains("DELETE") && s.contains("sess-42") }, curlResp(200, RELEASE))
    List<String> echoes = []

    load(sh, [:], echoes).call([maxDevices: 2, api: ">=34"])

    assert sh.anyScriptContains("/wait")
    assert echoes.any { it.contains("Shepherd Waiting") }
}

check("fails after queue timeout instead of waiting forever") {
    long[] nowMillis = [0L]
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") && s.contains("'POST'") }, curlResp(201, PENDING_SESSION))
        .on({ s -> s.contains("/wait'") }, {
            nowMillis[0] += 20_000L
            curlResp(200, PENDING_SESSION)
        })
        .on({ s -> s.contains("DELETE") && s.contains("sess-42") }, curlResp(200, RELEASE))
    boolean threw = false

    try {
        load(sh, [:], [], [], { -> nowMillis[0] }).call([queueTimeoutSeconds: 30])
    } catch (RuntimeException err) {
        threw = err.message.contains("did not become READY within 30s")
    }

    assert threw
    assert sh.anyScriptContains("DELETE")
}

check("continues on partial allocation and logs summary") {
    Map partialReady = READY_SESSION + [allocatedDevices: 1, requestedDevices: 4]
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") && s.contains("'POST'") }, curlResp(201, partialReady))
        .on({ s -> s.contains("DELETE") && s.contains("sess-42") }, curlResp(200, RELEASE))
    List<String> echoes = []

    load(sh, [:], echoes).call([maxDevices: 4, api: "34"])

    assert echoes.any { it.contains("Shepherd Partial Allocation") }
    assert echoes.any { it.contains("allocated=1") }
}

check("explicit marathonfile path is exported to gradle runtime") {
    MockSh sh = happySh()
    Map<String, String> files = [:]

    load(sh, files).call([maxDevices: 1, marathonfile: "ci/Marathonfile.yaml"])

    Map runTaskCall = sh.lastCallWithLabel("Marathon / Run Task")
    assert (runTaskCall.script as String).contains("export MSH_MARATHONFILE='${WORKSPACE}/ci/Marathonfile.yaml'")
    assert files["${WORKSPACE}/.msh/jenkins/sess-42/marathonfile.path"]?.trim() == "${WORKSPACE}/ci/Marathonfile.yaml"
}

check("release is still called when gradle task fails") {
    MockSh sh = happySh()
        .on({ s -> s.contains("./gradlew -I") }, { throw new RuntimeException("gradle failed") })
    boolean threw = false
    try {
        load(sh).call([:])
    } catch (RuntimeException err) {
        threw = err.message.contains("gradle failed")
    }
    assert threw
    assert sh.anyScriptContains("DELETE")
}

check("release failure is propagated after successful task") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") && s.contains("'POST'") }, curlResp(201, READY_SESSION))
        .on({ s -> s.contains("DELETE") && s.contains("sess-42") }, curlResp(503, [error: "release failed"]))
    boolean threw = false
    try {
        load(sh).call([:])
    } catch (RuntimeException err) {
        threw = err.message.contains("503")
    }
    assert threw
}

check("invalid maxDevices fails before any network request") {
    MockSh sh = new MockSh()
    boolean threw = false
    try {
        load(sh).call([maxDevices: 0])
    } catch (IllegalArgumentException err) {
        threw = err.message.contains("maxDevices")
    }
    assert threw
    assert !sh.anyScriptContains("/api/v1/sessions")
}

check("unsupported deviceType fails fast") {
    MockSh sh = new MockSh()
    boolean threw = false
    try {
        load(sh).call([deviceType: "tablet"])
    } catch (IllegalArgumentException err) {
        threw = err.message.contains("Unsupported deviceType")
    }
    assert threw
}

check("ready session without adbServers fails") {
    MockSh sh = new MockSh()
        .on({ s -> s.contains("/api/v1/sessions'") && s.contains("'POST'") }, curlResp(201, READY_SESSION + [adbServers: []]))
        .on({ s -> s.contains("DELETE") && s.contains("sess-42") }, curlResp(200, RELEASE))
    boolean threw = false
    try {
        load(sh).call([:])
    } catch (RuntimeException err) {
        threw = err.message.contains("adbServers")
    }
    assert threw
}

check("preflight script checks service.bootanim.exit") {
    MockSh sh = happySh()
    load(sh).call([maxDevices: 1, deviceType: "physical"])
    Map preflightCall = sh.calls.find { it.label?.startsWith("Shepherd / Wait For ADB") }
    assert preflightCall != null
    String script = preflightCall.script as String
    assert script.contains("service.bootanim.exit"), "Missing service.bootanim.exit check"
    assert script.contains('last_anim_exit'), "Missing last_anim_exit variable"
    assert script.contains('"1"'), "Missing value check for bootanim.exit"
}

check("preflight script checks init.svc.bootanim stopped") {
    MockSh sh = happySh()
    load(sh).call([maxDevices: 1, deviceType: "physical"])
    Map preflightCall = sh.calls.find { it.label?.startsWith("Shepherd / Wait For ADB") }
    assert preflightCall != null
    String script = preflightCall.script as String
    assert script.contains("init.svc.bootanim"), "Missing init.svc.bootanim check"
    assert script.contains('last_anim_svc'), "Missing last_anim_svc variable"
    assert script.contains("stopped"), "Missing 'stopped' value check for init.svc.bootanim"
}

check("preflight script has no-known-signals fallback matching server-side logic") {
    MockSh sh = happySh()
    load(sh).call([maxDevices: 1, deviceType: "physical"])
    Map preflightCall = sh.calls.find { it.label?.startsWith("Shepherd / Wait For ADB") }
    assert preflightCall != null
    String script = preflightCall.script as String
    // Fallback: if all 4 signals are absent, consider device booted
    assert script.contains('-z "$last_sys"') || script.contains('-z "\$last_sys"'), "Missing no-known-signals fallback"
    assert script.contains('-z "$last_dev"') || script.contains('-z "\$last_dev"'), "Missing no-known-signals fallback for dev.bootcomplete"
    assert script.contains('-z "$last_anim_exit"') || script.contains('-z "\$last_anim_exit"'), "Missing no-known-signals fallback for bootanim.exit"
    assert script.contains('-z "$last_anim_svc"') || script.contains('-z "\$last_anim_svc"'), "Missing no-known-signals fallback for init.svc.bootanim"
}

check("preflight error message includes all 4 boot signal values") {
    MockSh sh = happySh()
    load(sh).call([maxDevices: 1, deviceType: "physical"])
    Map preflightCall = sh.calls.find { it.label?.startsWith("Shepherd / Wait For ADB") }
    assert preflightCall != null
    String script = preflightCall.script as String
    assert script.contains("service.bootanim.exit="), "Error message missing service.bootanim.exit"
    assert script.contains("init.svc.bootanim="), "Error message missing init.svc.bootanim"
}

System.out.println()
System.out.println("=".multiply(68))
if (failed == 0) {
    System.out.println("All ${passed} test(s) passed.")
} else {
    System.out.println("${failed} of ${passed + failed} test(s) FAILED:")
    failureNames.each { System.out.println("  - ${it}") }
    System.exit(1)
}
