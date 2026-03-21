/**
 * Marathon Shepherd Jenkins shared-library entry point.
 *
 * Usage:
 *   @Library('marathon-shepherd') _
 *   shepherdTest(devices: 5, appApk: 'app.apk', testApk: 'test.apk')
 *
 * Parameters:
 *   devices     - Number of devices to allocate (default: 1)
 *   appApk      - Path to application APK (auto-detected if omitted)
 *   testApk     - Path to test APK (auto-detected if omitted)
 *   api         - Android API level for emulator-backed providers (default: "34")
 *   ttl         - Session TTL in seconds (default: 3600)
 *   managerUrl  - Manager URL (default: env.MSH_URL, fallback http://localhost:6037)
 *
 * Important:
 *   The generated `marathonfilePath` must be visible on the Jenkins agent.
 *   If the manager runs in Docker, mount the same absolute host path into the
 *   manager container and run Jenkins on the same node or mount that path into
 *   the Jenkins agent too.
 */
def call(Map params = [:]) {
    String managerUrl = normalizeManagerUrl(params.managerUrl ?: env.MSH_URL ?: 'http://localhost:6037')
    int requestedDevices = (params.devices ?: 1) as int
    String requestedApiLevel = (params.api ?: '34') as String
    long ttlSeconds = (params.ttl ?: 3600) as long
    String appApk = params.appApk ?: findApk('debug', false)
    String testApk = params.testApk ?: findApk('debug', true)
    String sessionId = null

    if (!appApk || !testApk) {
        error "Could not resolve APK paths. Provide appApk and testApk explicitly."
    }
    ensureCommandAvailable('curl')
    ensureCommandAvailable('marathon')

    printSection('Session Request', [
        "Manager URL       : ${managerUrl}",
        "Requested devices : ${requestedDevices}",
        "API level         : ${requestedApiLevel}",
        "TTL seconds       : ${ttlSeconds}",
        "App APK           : ${appApk}",
        "Test APK          : ${testApk}"
    ])

    def requestBody = groovy.json.JsonOutput.toJson([
        devices   : requestedDevices,
        apiLevel  : requestedApiLevel,
        appApk    : appApk,
        testApk   : testApk,
        ttlSeconds: ttlSeconds
    ])

    def response = requestJson(
        method: 'POST',
        url: "${managerUrl}/api/v1/sessions",
        requestBody: requestBody,
        expectedStatusCodes: ['201']
    )
    def session = response.json
    sessionId = session.id as String
    int allocatedDevices = (session.allocatedDevices ?: 0) as int
    String marathonfilePath = session.marathonfilePath as String
    List<String> adbServers = ((session.adbServers ?: []) as List).collect { adbServer ->
        "${adbServer.host}:${adbServer.port}"
    }

    List<String> sessionLines = [
        "Session ID        : ${sessionId}",
        "Status            : ${session.status}",
        "Allocated devices : ${allocatedDevices}/${session.requestedDevices}",
        "Expires at        : ${session.expiresAt}",
        "Marathonfile      : ${marathonfilePath ?: 'not provided'}"
    ]
    if (adbServers.isEmpty()) {
        sessionLines << "ADB servers       : none"
    } else {
        sessionLines << "ADB servers       : ${adbServers.join(', ')}"
    }
    printSection('Session Ready', sessionLines)

    if (allocatedDevices < requestedDevices) {
        printWarning(
            "Requested ${requestedDevices} device(s) but received ${allocatedDevices}. " +
                "The run will continue with the allocated capacity."
        )
    }
    if (!marathonfilePath) {
        error "Session ${sessionId} does not include a Marathonfile path."
    }
    if (!isFileVisibleToAgent(marathonfilePath)) {
        printWarning(
            "Marathonfile path is not visible on this Jenkins agent: ${marathonfilePath}"
        )
        printWarning(
            "If the manager runs in Docker, mount the same absolute path into the container " +
                "and into the Jenkins agent."
        )
        error "Session ${sessionId} returned an inaccessible Marathonfile path."
    }

    Exception marathonError = null
    Exception releaseError = null
    try {
        printSection('Marathon Run', [
            "Command           : marathon --marathonfile ${marathonfilePath}",
            "Report directory  : marathon-report/**"
        ])
        sh(
            label: "Run Marathon for session ${sessionId}",
            script: "set -euo pipefail\nmarathon --marathonfile ${quoteShellArg(marathonfilePath)}"
        )
        printSection('Marathon Result', [
            "Session ID        : ${sessionId}",
            "Status            : success"
        ])
    } catch (Exception err) {
        marathonError = err
        printSection('Marathon Result', [
            "Session ID        : ${sessionId}",
            "Status            : failed",
            "Reason            : ${summarizeError(err)}"
        ])
        throw err
    } finally {
        if (sessionId) {
            try {
                def releaseResponse = requestJson(
                    method: 'DELETE',
                    url: "${managerUrl}/api/v1/sessions/${sessionId}",
                    expectedStatusCodes: ['200', '404']
                )
                String releaseStatus = "HTTP ${releaseResponse.statusCode}"
                if (releaseResponse.json?.status) {
                    releaseStatus = "${releaseResponse.json.status} (HTTP ${releaseResponse.statusCode})"
                }
                printSection('Session Cleanup', [
                    "Session ID        : ${sessionId}",
                    "Release response  : ${releaseStatus}"
                ])
            } catch (Exception err) {
                releaseError = err
                printWarning("Failed to release session ${sessionId}: ${summarizeError(err)}")
            }
        }
        printSection('Report Summary', collectReportLines())
        if (marathonError == null && releaseError != null) {
            throw releaseError
        }
    }
}

private String findApk(String buildType, boolean isTest) {
    String pattern = isTest
        ? "*/build/outputs/apk/androidTest/${buildType}/*.apk"
        : "*/build/outputs/apk/${buildType}/*.apk"
    String exclude = isTest ? '' : '! -name "*androidTest*"'
    String result = sh(
        script: "find . -path '${pattern}' ${exclude} | head -1",
        returnStdout: true
    ).trim()
    return result ?: null
}

private String normalizeManagerUrl(String managerUrl) {
    return managerUrl.replaceAll('/+$', '')
}

private void ensureCommandAvailable(String command) {
    int status = sh(
        script: "command -v ${quoteShellArg(command)} >/dev/null 2>&1",
        returnStatus: true
    )
    if (status != 0) {
        error "Required command is not available on the Jenkins agent: ${command}"
    }
}

private boolean isFileVisibleToAgent(String path) {
    int status = sh(
        script: "test -f ${quoteShellArg(path)}",
        returnStatus: true
    )
    return status == 0
}

private Map requestJson(Map args) {
    String method = args.method as String
    String url = args.url as String
    String requestBody = args.requestBody as String
    List<String> expectedStatusCodes = (args.expectedStatusCodes ?: ['200']) as List<String>
    boolean expectJson = args.containsKey('expectJson') ? (args.expectJson as boolean) : true
    String rawResponse = sh(
        script: buildCurlScript(method, url, requestBody),
        returnStdout: true
    )
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

private Object tryParseJsonText(String text) {
    if (!text?.trim()) {
        return null
    }
    try {
        return new groovy.json.JsonSlurperClassic().parseText(text)
    } catch (Exception ignored) {
        return null
    }
}

private String extractErrorMessage(String responseBody) {
    Object parsedBody = tryParseJsonText(responseBody)
    if (parsedBody instanceof Map && parsedBody.error) {
        return parsedBody.error as String
    }
    if (responseBody?.trim()) {
        return responseBody.trim()
    }
    return 'No response body returned.'
}

private List<String> collectReportLines() {
    List<String> summary = sh(
        script: '''#!/usr/bin/env bash
set -euo pipefail
if [ -d marathon-report ]; then
  junit_count=$(find marathon-report -path '*/junit-reports/*.xml' | wc -l | tr -d ' ')
  file_count=$(find marathon-report -type f | wc -l | tr -d ' ')
  archive_path="not-created"
  if command -v tar >/dev/null 2>&1; then
    rm -f marathon-report.tar.gz
    if tar -czf marathon-report.tar.gz marathon-report >/dev/null 2>&1; then
      archive_path="$PWD/marathon-report.tar.gz"
    else
      archive_path="tar-failed"
    fi
  fi
  printf 'present\n%s\n%s\n%s\n' "$junit_count" "$file_count" "$archive_path"
else
  printf 'missing\n0\n0\nnot-created\n'
fi
''',
        returnStdout: true
    ).readLines()
    boolean reportPresent = summary[0] == 'present'
    if (!reportPresent) {
        return [
            'Report directory  : not found',
            'JUnit XML files   : 0',
            'Packed archive    : not created'
        ]
    }
    return [
        'Report directory  : marathon-report',
        "JUnit XML files   : ${summary[1]}",
        "Total report files: ${summary[2]}",
        "Packed archive    : ${summary[3]}"
    ]
}

private String quoteShellArg(String value) {
    return "'${value.replace("'", "'\"'\"'")}'"
}

private String summarizeError(Exception err) {
    String message = err.getMessage() ?: err.toString()
    return message.readLines().find { line -> line?.trim() } ?: err.toString()
}

private void printSection(String title, List<String> lines) {
    String divider = '=' * 72
    echo divider
    echo "Marathon Shepherd | ${title}"
    echo divider
    lines.each { line ->
        echo line
    }
}

private void printWarning(String message) {
    echo "WARNING: ${message}"
}
