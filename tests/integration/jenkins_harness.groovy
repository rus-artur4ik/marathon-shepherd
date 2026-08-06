#!/usr/bin/env groovy

import groovy.json.JsonSlurperClassic

final class JenkinsRuntime {
    Map<String, String> env
    File workspace
    Map<String, String> writtenFiles = [:]
    Map<String, Object> currentBuild = [:]

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

    String pwd() {
        return workspace.absolutePath
    }

    void writeFile(Map args) {
        File target = new File(args.file.toString())
        target.parentFile?.mkdirs()
        target.text = args.text.toString()
        writtenFiles[target.absolutePath] = target.text
    }
}

String managerUrl = (System.getenv("MSH_URL") ?: "http://localhost:6037").replaceAll("/+\$", "")
String workspacePath = System.getenv("MSH_HARNESS_WORKSPACE") ?: new File("build/integration/jenkins-workspace").absolutePath
File workspaceDir = new File(workspacePath)
workspaceDir.mkdirs()

File gradleWrapper = new File(workspaceDir, "gradlew")
gradleWrapper.text = '''#!/usr/bin/env bash
set -euo pipefail
if [[ -z "${MSH_SESSION_ID:-}" ]]; then
  echo "MSH_SESSION_ID is missing" >&2
  exit 1
fi
if [[ -z "${MSH_ADB_SERVERS_JSON:-}" ]]; then
  echo "MSH_ADB_SERVERS_JSON is missing" >&2
  exit 1
fi
if [[ "$1" != "-I" ]]; then
  echo "Expected Gradle init script argument, got: $*" >&2
  exit 1
fi
if [[ ! -f "$2" ]]; then
  echo "Init script does not exist: $2" >&2
  exit 1
fi
mkdir -p marathon-report/sample/junit-reports
printf '%s\n' '<testsuite name="integration" tests="1" failures="0"><testcase classname="jenkins" name="runMarathonWithShepherd"/></testsuite>' > marathon-report/sample/junit-reports/result.xml
'''
gradleWrapper.setExecutable(true)

JenkinsRuntime runtime = new JenkinsRuntime([MSH_URL: managerUrl], workspaceDir)

Binding binding = new Binding()
binding.setVariable("env", runtime.env)
binding.setVariable("currentBuild", runtime.currentBuild)
binding.setVariable("pwd", { -> runtime.pwd() })
binding.setVariable("writeFile", { Map args -> runtime.writeFile(args) })
binding.setVariable("sh", { Map args -> runtime.sh(args) })
binding.setVariable("echo", { String message -> runtime.echo(message) })
binding.setVariable("error", { String message -> runtime.error(message) })

File scriptFile = new File("vars/runMarathonWithShepherd.groovy")
if (!scriptFile.exists()) {
    throw new RuntimeException("vars/runMarathonWithShepherd.groovy was not found")
}

def script = new GroovyShell(binding).parse(scriptFile)

println("Running Jenkins harness against ${managerUrl}")
script.call(
    [
        managerUrl : managerUrl,
        maxDevices : 1,
        api        : "34+",
        deviceType : "emulator",
        task       : "marathon",
        marathonfile: null,
    ]
)

File reportFile = new File(workspaceDir, "marathon-report/sample/junit-reports/result.xml")
if (!reportFile.exists()) {
    throw new RuntimeException("Expected harness gradle wrapper to create ${reportFile.absolutePath}")
}

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
