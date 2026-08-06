// CI pipeline for Marathon Shepherd itself.
//
// The tiers mirror what tests/run_tests.sh exposes. Docker- and device-backed tiers are
// opt-in because they need an agent with a Docker daemon or an attached Android device;
// the default run is the fast gate that works on any JDK 21 agent.

pipeline {
  agent any

  options {
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
    timeout(time: 90, unit: 'MINUTES')
  }

  parameters {
    booleanParam(
      name: 'RUN_DOCKER_TIERS',
      defaultValue: false,
      description: 'Run the Docker-backed component, integration and e2e tiers. Requires a Docker-capable agent.'
    )
    booleanParam(
      name: 'RUN_DEVICE_TIERS',
      defaultValue: false,
      description: 'Run the device-backed tiers. Requires an Android device or emulator visible to `adb devices` on the agent.'
    )
  }

  environment {
    // Keep Gradle off the shared daemon on CI agents.
    GRADLE_OPTS = '-Dorg.gradle.daemon=false'
  }

  stages {
    stage('Build') {
      steps {
        sh './gradlew --no-daemon build -x test --console=plain'
      }
    }

    stage('Lint') {
      steps {
        sh './gradlew --no-daemon ktlintCheck --console=plain'
      }
    }

    stage('Unit tests') {
      steps {
        sh './tests/run_tests.sh --only unit:kotlin,unit:jenkins'
      }
      post {
        always {
          junit testResults: '**/build/test-results/test/*.xml', allowEmptyResults: true
        }
      }
    }

    stage('Docker tiers') {
      when {
        beforeAgent true
        expression { params.RUN_DOCKER_TIERS }
      }
      steps {
        sh './tests/run_tests.sh --skip-real-device --skip-apk-e2e'
      }
    }

    stage('Device tiers') {
      when {
        beforeAgent true
        expression { params.RUN_DEVICE_TIERS }
      }
      steps {
        sh './tests/run_tests.sh --only e2e:apk-instrumentation'
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: '**/build/reports/tests/**', allowEmptyArchive: true, fingerprint: false
    }
  }
}
