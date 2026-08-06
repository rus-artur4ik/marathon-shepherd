import groovy.json.JsonOutput
import groovy.transform.Field

@Field final long SESSION_TTL_SECONDS = 3600L
@Field final long WAIT_TIMEOUT_SECONDS = 20L
@Field final long DEFAULT_QUEUE_TIMEOUT_SECONDS = 900L
@Field final long ADB_PREFLIGHT_TIMEOUT_SECONDS = 30L
@Field final long ADB_PREFLIGHT_POLL_SECONDS = 2L
@Field final Set<String> SUPPORTED_DEVICE_TYPES = ['physical', 'emulator'] as Set<String>

def call(Map params = [:]) {
    String managerUrl = normalizeManagerUrl(params.managerUrl ?: env.MSH_URL ?: 'http://localhost:6037')
    int maxDevices = ((params.containsKey('maxDevices') ? params.maxDevices : 1) ?: 0) as int
    String apiSelector = normalizeOptionalText(params.api)
    String deviceType = normalizeDeviceType(params.deviceType)
    String task = normalizeTask(params.task ?: 'marathon')
    String marathonfile = normalizeOptionalText(params.marathonfile)
    long queueTimeoutSeconds = normalizePositiveLong(
        params.containsKey('queueTimeoutSeconds') ? params.queueTimeoutSeconds : DEFAULT_QUEUE_TIMEOUT_SECONDS,
        'queueTimeoutSeconds'
    )
    String sessionId = null

    requireArgument(maxDevices > 0, "maxDevices must be greater than zero")

    String workspacePath = pwd()
    String resolvedMarathonfile = resolveOptionalPath(workspacePath, marathonfile)

    ensureCommandAvailable('curl')
    ensureCommandAvailable('adb')
    ensureGradleWrapperExists()
    printSummary(
        'Shepherd Request',
        [
            "managerUrl=${managerUrl}",
            "maxDevices=${maxDevices}",
            "deviceType=${deviceType ?: 'all'}",
            "api=${apiSelector ?: 'any'}",
            "task=${task}",
            "marathonfile=${resolvedMarathonfile ?: 'project-default'}",
            "queueTimeoutSeconds=${queueTimeoutSeconds}",
        ]
    )

    Map<String, Object> createPayload = [
        maxDevices: maxDevices,
        ttlSeconds: SESSION_TTL_SECONDS
    ]
    if (apiSelector != null) {
        createPayload.api = apiSelector
    }
    if (deviceType != null) {
        createPayload.deviceType = deviceType
    }

    Exception taskError = null
    Exception releaseError = null
    try {
        Map session = requestJson(
            label: 'Shepherd / Queue Session',
            method: 'POST',
            url: "${managerUrl}/api/v1/sessions",
            requestBody: JsonOutput.toJson(createPayload),
            expectedStatusCodes: ['201']
        ).json as Map<String, Object>
        sessionId = session.id as String
        session = waitUntilReady(managerUrl, session, queueTimeoutSeconds)

        if ((session.status as String) != 'READY') {
            error "Session ${sessionId} finished in unexpected status ${session.status} (${summarizeSessionState(session)})"
        }

        List<Map<String, Object>> adbServers = parseAdbServers(session.adbServers)
        if (adbServers.isEmpty()) {
            error "Session ${sessionId} became READY without adbServers."
        }

        updateBuildDescription(session, maxDevices, deviceType, apiSelector)
        printSummary(
            'Shepherd Allocation',
            [
                "sessionId=${sessionId}",
                "status=${session.status}",
                "allocatedDevices=${session.allocatedDevices}/${session.requestedDevices}",
                "queuePosition=${session.queuePosition ?: 'n/a'}",
                "adbServers=${adbServers.collect { entry -> "${entry.host}:${entry.port}" }.join(', ')}",
            ]
        )
        if (((session.allocatedDevices ?: 0) as int) < ((session.requestedDevices ?: maxDevices) as int)) {
            printSummary(
                'Shepherd Partial Allocation',
                [
                    "requested=${session.requestedDevices}",
                    "allocated=${session.allocatedDevices}",
                    "note=continuing run with reduced parallelism",
                ]
            )
        }
        waitForAdbServersReady(adbServers, session.deviceType as String)

        Map<String, String> runtimeFiles = writeRuntimeFiles(
            workspacePath: workspacePath,
            sessionId: sessionId,
            adbServers: adbServers,
            marathonfile: resolvedMarathonfile
        )

        sh(
            label: 'Marathon / Run Task',
            script: buildGradleRunScript(
                task: task,
                sessionId: sessionId,
                adbServersJsonPath: runtimeFiles.adbServersJsonPath,
                initScriptPath: runtimeFiles.initScriptPath,
                marathonfile: resolvedMarathonfile,
                allocatedDevices: (session.allocatedDevices ?: 0) as int,
                maxDevices: maxDevices,
                deviceType: deviceType,
                apiSelector: apiSelector
            )
        )
    } catch (Exception err) {
        taskError = err
        throw err
    } finally {
        if (sessionId != null) {
            try {
                Map releaseResponse = requestJson(
                    label: 'Shepherd / Release Session',
                    method: 'DELETE',
                    url: "${managerUrl}/api/v1/sessions/${sessionId}",
                    expectedStatusCodes: ['200']
                )
                printSummary(
                    'Shepherd Release',
                    [
                        "sessionId=${sessionId}",
                        "status=${releaseResponse.json?.status ?: 'released'}",
                    ]
                )
            } catch (Exception err) {
                releaseError = err
                printSummary(
                    'Shepherd Release Failed',
                    [
                        "sessionId=${sessionId}",
                        "reason=${summarizeError(err)}",
                    ]
                )
            }
        }
        if (taskError == null && releaseError != null) {
            throw releaseError
        }
    }
}

private Map<String, Object> waitUntilReady(
    String managerUrl,
    Map<String, Object> initialSession,
    long queueTimeoutSeconds
) {
    Map<String, Object> session = initialSession
    long deadlineEpochMillis = nowMillis() + (queueTimeoutSeconds * 1000L)
    while ((session.status as String) == 'PENDING') {
        long remainingSeconds = Math.max(0L, ((deadlineEpochMillis - nowMillis()) / 1000L) as long)
        if (remainingSeconds <= 0L) {
            error "Session ${session.id} did not become READY within ${queueTimeoutSeconds}s (${summarizeSessionState(session)})"
        }
        printSummary(
            'Shepherd Waiting',
            [
                "sessionId=${session.id}",
                "queuePosition=${session.queuePosition ?: 'n/a'}",
                "requested=${session.requestedDevices}",
                "allocated=${session.allocatedDevices ?: 0}",
                "remainingWaitSeconds=${remainingSeconds}",
            ]
        )
        long perRequestTimeoutSeconds = Math.min(WAIT_TIMEOUT_SECONDS, remainingSeconds)
        Map waitResponse = requestJson(
            label: 'Shepherd / Wait For Devices',
            method: 'POST',
            url: "${managerUrl}/api/v1/sessions/${session.id}/wait",
            requestBody: JsonOutput.toJson([timeoutSeconds: perRequestTimeoutSeconds]),
            expectedStatusCodes: ['200']
        )
        session = waitResponse.json as Map<String, Object>
    }
    return session
}

private Map<String, String> writeRuntimeFiles(
    Map args
) {
    String workspacePath = args.workspacePath as String
    String sessionId = args.sessionId as String
    List adbServers = args.adbServers as List
    String marathonfile = args.marathonfile as String
    String runtimeDir = "${workspacePath}/.msh/jenkins/${sessionId}"
    String adbServersJsonPath = "${runtimeDir}/adb-servers.json"
    String initScriptPath = "${runtimeDir}/shepherd.init.gradle"

    sh(label: 'Marathon / Prepare Runtime Directory', script: """#!/usr/bin/env bash
set -euo pipefail
mkdir -p ${quoteShellArg(runtimeDir)}
""")
    writeFile(file: adbServersJsonPath, text: JsonOutput.toJson(adbServers))
    writeFile(file: initScriptPath, text: buildInitScript())
    if (marathonfile != null) {
        writeFile(file: "${runtimeDir}/marathonfile.path", text: "${marathonfile}\n")
    }
    return [
        adbServersJsonPath: adbServersJsonPath,
        initScriptPath    : initScriptPath,
    ]
}

private void waitForAdbServersReady(List<Map<String, Object>> adbServers, String sessionDeviceType) {
    adbServers.each { Map<String, Object> server ->
        String host = server.host as String
        int port = (server.port as Number).intValue()
        if (!shouldPreflightAdbServer(sessionDeviceType, host, port)) {
            printSummary(
                'Shepherd ADB Preflight',
                [
                    "target=${host}:${port}",
                    "status=skipped",
                ]
            )
            return
        }
        printSummary(
            'Shepherd ADB Preflight',
            [
                "target=${host}:${port}",
                "timeoutSeconds=${ADB_PREFLIGHT_TIMEOUT_SECONDS}",
            ]
        )
        int status = sh(
            label: "Shepherd / Wait For ADB ${host}:${port}",
            script: buildAdbPreflightScript(host, port),
            returnStatus: true
        ) as int
        if (status != 0) {
            error "Lease-scoped ADB server ${host}:${port} did not become ready within ${ADB_PREFLIGHT_TIMEOUT_SECONDS}s"
        }
    }
}

private boolean shouldPreflightAdbServer(String sessionDeviceType, String host, int port) {
    String normalizedDeviceType = normalizeOptionalText(sessionDeviceType)?.toLowerCase(java.util.Locale.ROOT)
    if (normalizedDeviceType == 'physical') {
        return true
    }
    return port != 5037
}

private String buildAdbPreflightScript(String host, int port) {
    String portValue = port.toString()
    return """#!/usr/bin/env bash
set -euo pipefail
deadline=\$((SECONDS + ${ADB_PREFLIGHT_TIMEOUT_SECONDS}))
last_state=""
last_sys=""
last_dev=""
last_anim_exit=""
last_anim_svc=""
while (( SECONDS < deadline )); do
  last_state="\$(adb -H ${quoteShellArg(host)} -P ${quoteShellArg(portValue)} get-state 2>/dev/null | tr -d '\\r' || true)"
  last_sys="\$(adb -H ${quoteShellArg(host)} -P ${quoteShellArg(portValue)} shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r' || true)"
  last_dev="\$(adb -H ${quoteShellArg(host)} -P ${quoteShellArg(portValue)} shell getprop dev.bootcomplete 2>/dev/null | tr -d '\\r' || true)"
  last_anim_exit="\$(adb -H ${quoteShellArg(host)} -P ${quoteShellArg(portValue)} shell getprop service.bootanim.exit 2>/dev/null | tr -d '\\r' || true)"
  last_anim_svc="\$(adb -H ${quoteShellArg(host)} -P ${quoteShellArg(portValue)} shell getprop init.svc.bootanim 2>/dev/null | tr -d '\\r' || true)"
  if [[ "\$last_state" == "device" ]]; then
    if [[ "\$last_sys" == "1" || "\$last_dev" == "1" || "\$last_anim_exit" == "1" ]]; then
      exit 0
    fi
    if [[ "\${last_anim_svc,,}" == "stopped" ]]; then
      exit 0
    fi
    # No known boot signals present on this device — assume it is booted (matches server-side fallback)
    if [[ -z "\$last_sys" && -z "\$last_dev" && -z "\$last_anim_exit" && -z "\$last_anim_svc" ]]; then
      exit 0
    fi
  fi
  sleep ${ADB_PREFLIGHT_POLL_SECONDS}
done
printf 'ADB preflight failed for %s:%s: state=%s sys.boot_completed=%s dev.bootcomplete=%s service.bootanim.exit=%s init.svc.bootanim=%s\\n' \\
  ${quoteShellArg(host)} ${quoteShellArg(portValue)} \\
  "\${last_state:-empty}" "\${last_sys:-empty}" "\${last_dev:-empty}" "\${last_anim_exit:-empty}" "\${last_anim_svc:-empty}" >&2
exit 1
"""
}

private String buildGradleRunScript(Map args) {
    String task = args.task as String
    String sessionId = args.sessionId as String
    String adbServersJsonPath = args.adbServersJsonPath as String
    String initScriptPath = args.initScriptPath as String
    String marathonfile = args.marathonfile as String
    int allocatedDevices = args.allocatedDevices as int
    int maxDevices = args.maxDevices as int
    String deviceType = args.deviceType as String
    String apiSelector = args.apiSelector as String

    String marathonfileExport = marathonfile == null
        ? "unset MSH_MARATHONFILE"
        : "export MSH_MARATHONFILE=${quoteShellArg(marathonfile)}"

    String deviceTypeExport = deviceType == null
        ? "unset MSH_DEVICE_TYPE"
        : "export MSH_DEVICE_TYPE=${quoteShellArg(deviceType)}"

    String apiExport = apiSelector == null
        ? "unset MSH_API"
        : "export MSH_API=${quoteShellArg(apiSelector)}"

    return """#!/usr/bin/env bash
set -euo pipefail
export MSH_SESSION_ID=${quoteShellArg(sessionId)}
export MSH_ADB_SERVERS_JSON="\$(cat ${quoteShellArg(adbServersJsonPath)})"
export MSH_ALLOCATED_DEVICES=${allocatedDevices}
export MSH_MAX_DEVICES=${maxDevices}
${deviceTypeExport}
${apiExport}
${marathonfileExport}
./gradlew -I ${quoteShellArg(initScriptPath)} ${quoteShellArg(task)}
"""
}

private String buildInitScript() {
    return '''import groovy.json.JsonSlurper

def mshAdbServersJson = System.getenv('MSH_ADB_SERVERS_JSON')
if (mshAdbServersJson == null || mshAdbServersJson.trim().isEmpty()) {
    throw new GradleException('MSH_ADB_SERVERS_JSON is not set')
}

def mshAdbServers = new JsonSlurper().parseText(mshAdbServersJson)
def mshMarathonfile = System.getenv('MSH_MARATHONFILE')

def loadMarathonClass(Object owner, String className) {
    try {
        return owner.getClass().classLoader.loadClass(className)
    } catch (ClassNotFoundException ex) {
        throw new GradleException("Marathon class is not available from plugin classloader: ${className}", ex)
    }
}

def newAdbEndpoint(Object marathonExtension, Map server) {
    def adbEndpointClass = loadMarathonClass(marathonExtension, 'com.malinskiy.marathon.config.vendor.android.AdbEndpoint')
    return adbEndpointClass.getDeclaredConstructor(String, Integer.TYPE).newInstance(
        server.host.toString(),
        (server.port as Number).intValue()
    )
}

gradle.afterProject { project ->
    project.pluginManager.withPlugin('com.malinskiy.marathon') {
        def marathonExtension = project.extensions.findByName('marathon')
        if (marathonExtension != null) {
            marathonExtension.adbServers = mshAdbServers.collect { server ->
                newAdbEndpoint(marathonExtension, server as Map)
            }
        }

        if (mshMarathonfile != null && !mshMarathonfile.trim().isEmpty()) {
            project.tasks.configureEach { task ->
                if (task.class.name == 'com.malinskiy.marathon.gradle.task.GenerateMarathonfileTask') {
                    task.enabled = false
                } else if (task.class.name == 'com.malinskiy.marathon.gradle.task.MarathonRunTask') {
                    task.marathonfile.set(project.layout.file(project.provider { project.file(mshMarathonfile) }))
                }
            }
        }
    }
}
'''
}

private void updateBuildDescription(Map<String, Object> session, int maxDevices, String deviceType, String apiSelector) {
    if (!binding.hasVariable('currentBuild')) {
        return
    }
    def current = binding.getVariable('currentBuild')
    current.description = "MSH ${(session.allocatedDevices ?: 0)}/${maxDevices} ${deviceType ?: 'devices'} ${apiSelector ?: 'any api'}"
}

private void ensureGradleWrapperExists() {
    int status = sh(
        label: 'Marathon / Check Gradle Wrapper',
        script: 'test -f ./gradlew',
        returnStatus: true
    )
    if (status != 0) {
        error "runMarathonWithShepherd requires ./gradlew in the workspace root."
    }
}

private void ensureCommandAvailable(String command) {
    int status = sh(
        label: 'Shepherd / Check Prerequisites',
        script: "command -v ${quoteShellArg(command)} >/dev/null 2>&1",
        returnStatus: true
    )
    if (status != 0) {
        error "Required command is not available on the Jenkins agent: ${command}"
    }
}

private Map requestJson(Map args) {
    String label = args.label as String
    String method = args.method as String
    String url = args.url as String
    String requestBody = args.requestBody as String
    List<String> expectedStatusCodes = (args.expectedStatusCodes ?: ['200']) as List<String>
    boolean expectJson = args.containsKey('expectJson') ? (args.expectJson as boolean) : true
    String rawResponse = sh(
        label: label,
        script: buildCurlScript(method, url, requestBody),
        returnStdout: true
    ) as String
    Map httpResponse = splitHttpResponse(rawResponse)
    if (!expectedStatusCodes.contains(httpResponse.statusCode)) {
        String errorMessage = extractErrorMessage(httpResponse.body)
        error "${method} ${url} failed with HTTP ${httpResponse.statusCode}: ${errorMessage}"
    }
    Object parsedBody = tryParseJsonText(httpResponse.body)
    if (expectJson && parsedBody == null) {
        error "${method} ${url} succeeded with HTTP ${httpResponse.statusCode}, but returned invalid JSON."
    }
    return [
        statusCode: httpResponse.statusCode,
        body      : httpResponse.body,
        json      : parsedBody
    ]
}

private String buildCurlScript(String method, String url, String requestBody) {
    String requestFileBlock = ''
    String requestFlags = ''
    String cleanupTarget = '"$response_file"'
    if (requestBody != null) {
        requestFileBlock = """
request_file="\$(mktemp)"
cat > "\$request_file" <<'__MSH_REQUEST_BODY__'
${requestBody}
__MSH_REQUEST_BODY__
"""
        requestFlags = "-H 'Content-Type: application/json' --data-binary @\"\$request_file\""
        cleanupTarget = '"$response_file" "$request_file"'
    }
    return """#!/usr/bin/env bash
set -euo pipefail
response_file="\$(mktemp)"
cleanup() {
  rm -f ${cleanupTarget}
}
trap cleanup EXIT
${requestFileBlock}
status_code=\$(curl -sS -X ${quoteShellArg(method)} ${requestFlags} -o "\$response_file" -w '%{http_code}' ${quoteShellArg(url)})
cat "\$response_file"
printf '\\n%s' "\$status_code"
"""
}

private Map splitHttpResponse(String rawResponse) {
    String normalized = rawResponse.replaceFirst('[\\r\\n]+$', '')
    int separatorIndex = normalized.lastIndexOf('\n')
    if (separatorIndex < 0) {
        error "Unable to parse HTTP response from manager."
    }
    return [
        body      : normalized.substring(0, separatorIndex),
        statusCode: normalized.substring(separatorIndex + 1)
    ]
}

private Object tryParseJsonText(String body) {
    if (body == null || body.trim().isEmpty()) {
        return null
    }
    try {
        return new groovy.json.JsonSlurperClassic().parseText(body)
    } catch (Exception ignored) {
        return null
    }
}

private String extractErrorMessage(String body) {
    Object parsed = tryParseJsonText(body)
    if (parsed instanceof Map && parsed.error != null) {
        return parsed.error.toString()
    }
    return body?.trim() ?: 'empty response body'
}

private List<Map<String, Object>> parseAdbServers(Object rawAdbServers) {
    List input = (rawAdbServers ?: []) as List
    return input.collect { entry ->
        Map<String, Object> item = entry as Map<String, Object>
        Number port = item.port as Number
        return [
            host: item.host?.toString(),
            port: port?.intValue()
        ]
    }.findAll { item ->
        item.host != null && item.port != null
    }
}

private String normalizeManagerUrl(String managerUrl) {
    return managerUrl.replaceAll('/+$', '')
}

private String normalizeOptionalText(Object rawValue) {
    if (rawValue == null) {
        return null
    }
    String normalized = rawValue.toString().trim()
    return normalized.isEmpty() ? null : normalized
}

private String normalizeDeviceType(Object rawDeviceType) {
    String normalized = normalizeOptionalText(rawDeviceType)?.toLowerCase(java.util.Locale.ROOT)
    requireArgument(normalized == null || SUPPORTED_DEVICE_TYPES.contains(normalized)) {
        "Unsupported deviceType '${rawDeviceType}'. Supported values: ${SUPPORTED_DEVICE_TYPES.join(', ')}"
    }
    return normalized
}

private String normalizeTask(Object rawTask) {
    String task = normalizeOptionalText(rawTask)
    requireArgument(task != null) { "task must not be blank" }
    return task
}

private long normalizePositiveLong(Object rawValue, String fieldName) {
    long parsedValue
    try {
        Object normalizedValue = rawValue ?: 0
        parsedValue = normalizedValue instanceof Number
            ? (normalizedValue as Number).longValue()
            : Long.parseLong(normalizedValue.toString().trim())
    } catch (Exception ignored) {
        throw new IllegalArgumentException("${fieldName} must be a positive integer")
    }
    requireArgument(parsedValue > 0L, "${fieldName} must be greater than zero")
    return parsedValue
}

private String resolveOptionalPath(String workspacePath, String pathValue) {
    if (pathValue == null) {
        return null
    }
    File path = new File(pathValue)
    return path.isAbsolute() ? path.absolutePath : new File(workspacePath, pathValue).absolutePath
}

private void requireArgument(boolean condition, Closure<String> messageSupplier) {
    if (!condition) {
        throw new IllegalArgumentException(messageSupplier.call())
    }
}

private void requireArgument(boolean condition, String message) {
    requireArgument(condition) { message }
}

private void printSummary(String title, List<String> lines) {
    echo(([title] + lines.collect { line -> "  - ${line}" }).join('\n'))
}

private String summarizeError(Exception err) {
    String message = err.message?.trim()
    return message?.isEmpty() ? err.class.simpleName : (message ?: err.class.simpleName)
}

private long nowMillis() {
    if (binding.hasVariable('mshNowMillis')) {
        def provider = binding.getVariable('mshNowMillis')
        return (provider as Closure<Long>).call()
    }
    return System.currentTimeMillis()
}

private String summarizeSessionState(Map<String, Object> session) {
    return [
        "status=${session.status}",
        "queuePosition=${session.queuePosition ?: 'n/a'}",
        "requested=${session.requestedDevices ?: 'n/a'}",
        "allocated=${session.allocatedDevices ?: 0}",
    ].join(', ')
}

private String quoteShellArg(String value) {
    return "'${value.replace("'", "'\"'\"'")}'"
}
