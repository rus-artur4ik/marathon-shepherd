#!/usr/bin/env groovy

import groovy.json.JsonSlurperClassic

final class JenkinsRuntime {
    Map<String, String> env
    File workspace

    JenkinsRuntime(Map<String, String> env, File workspace) {
        this.env = env
        this.workspace = workspace
    }

    Object sh(Map args) {
        Object rawScript = args.script
        if (rawScript == null) {
            throw new IllegalArgumentException("sh step requires 'script'")
        }
        String script = rawScript.toString()
        boolean returnStatus = (args.returnStatus ?: false) as boolean
        boolean returnStdout = (args.returnStdout ?: false) as boolean

        Process process = new ProcessBuilder("bash", "-lc", script)
            .directory(workspace)
            .redirectErrorStream(false)
            .start()

        String stdout = process.inputStream.getText("UTF-8")
        String stderr = process.errorStream.getText("UTF-8")
        int exitCode = process.waitFor()

        if (returnStatus) {
            return exitCode
        }

        if (exitCode != 0) {
            throw new RuntimeException(
                "sh step failed with exit ${exitCode}\nSTDOUT:\n${stdout}\nSTDERR:\n${stderr}"
            )
        }

        if (returnStdout) {
            return stdout
        }

        if (stdout?.trim()) {
            println(stdout.trim())
        }
        return null
    }

    void echo(String message) {
        println(message)
    }

    void error(String message) {
        throw new RuntimeException(message)
    }
}

String managerUrl = (System.getenv("MSH_URL") ?: "http://localhost:6037").replaceAll("/+\$", "")
String workspacePath = System.getenv("MSH_HARNESS_WORKSPACE") ?: new File("build/integration/jenkins-workspace").absolutePath
File workspaceDir = new File(workspacePath)
if (!workspaceDir.exists()) {
    if (!workspaceDir.mkdirs()) {
        throw new RuntimeException("Failed to create workspace at ${workspaceDir.absolutePath}")
    }
}

JenkinsRuntime runtime = new JenkinsRuntime(
    [MSH_URL: managerUrl],
    workspaceDir
)

Binding binding = new Binding()
binding.setVariable("env", runtime.env)
binding.setVariable("sh", { Map args -> runtime.sh(args) })
binding.setVariable("echo", { String message -> runtime.echo(message) })
binding.setVariable("error", { String message -> runtime.error(message) })

File scriptFile = new File("vars/shepherdTest.groovy")
if (!scriptFile.exists()) {
    throw new RuntimeException("vars/shepherdTest.groovy was not found")
}

def script = new GroovyShell(binding).parse(scriptFile)

println("Running Jenkins harness against ${managerUrl}")
script.call(
    [
        managerUrl: managerUrl,
        devices: 1,
        api: "34",
        ttl: 120,
        deviceType: "emulator"
    ],
    { List<Map<String, Object>> adbServers, Map<String, Object> session ->
        if (adbServers.isEmpty()) {
            throw new RuntimeException("Closure received empty adbServers")
        }
        Map<String, Object> first = adbServers.first()
        if (first.host == null || first.port == null) {
            throw new RuntimeException("Closure received invalid adb server descriptor: ${first}")
        }
        runtime.echo("Harness closure: session=${session.id}, adbServers=${adbServers}")
        runtime.sh(
            script: """#!/usr/bin/env bash
set -euo pipefail
mkdir -p marathon-report/sample/junit-reports
printf '%s\\n' '<testsuite name="integration" tests="1" failures="0"><testcase classname="jenkins" name="harness"/></testsuite>' > marathon-report/sample/junit-reports/result.xml
"""
        )
    }
)

String sessionsBody = runtime.sh(
    script: "curl -sS ${managerUrl}/api/v1/sessions",
    returnStdout: true
) as String
List sessions = new JsonSlurperClassic().parseText(sessionsBody) as List
List activeSessions = sessions.findAll { it.status in ["READY", "PENDING"] }
if (!activeSessions.isEmpty()) {
    throw new RuntimeException("Expected no active sessions after cleanup, got: ${activeSessions}")
}
println("Harness assertions passed. Sessions are cleaned up.")
