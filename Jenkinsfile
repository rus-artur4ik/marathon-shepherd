// CI pipeline for Marathon Shepherd itself, and its delivery from master.
//
// The tiers mirror what tests/run_tests.sh exposes. Docker- and device-backed tiers are
// opt-in because they need an agent with a Docker daemon or an attached Android device;
// the default run is the fast gate that works on any JDK 21 agent.
//
// On master, once the gate passes, the manager and shepherd-adb images are pushed to
// MSH_IMAGE_REGISTRY and the Helm releases installed in MSH_DEPLOY_NAMESPACE are upgraded to
// them (docs/kubernetes.md). Delivery needs the Docker socket on the agent, and is skipped
// while the registry credentials are missing.

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
    // Delivery from master.
    MSH_IMAGE_REGISTRY = 'ghcr.io/rus-artur4ik'
    // "Username with password" credentials: a GitHub user and a token with write:packages.
    MSH_REGISTRY_CREDENTIALS = 'ghcr-push'
    // What the agent's Docker daemon builds natively; more platforms need QEMU on its host.
    MSH_IMAGE_PLATFORMS = 'linux/arm64'
    MSH_DEPLOY_NAMESPACE = 'marathon-shepherd'
    // A kubeconfig on the Docker host, handed to the Helm container.
    MSH_KUBECONFIG_HOST_PATH = '/etc/rancher/k3s/k3s.yaml'
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
        sh './tests/run_tests.sh --only unit:kotlin,unit:python-client'
        // Not every agent image has Groovy: then the shared-library tests run in a container,
        // which sees this workspace through the agent's volumes.
        sh '''
          if command -v groovy >/dev/null 2>&1; then
            groovy tests/unit/jenkins_unit_test.groovy
          elif [ -f /.dockerenv ]; then
            docker run --rm --user "$(id -u):$(id -g)" --volumes-from "$(hostname)" -w "$PWD" \
              groovy:4.0-jdk21 groovy tests/unit/jenkins_unit_test.groovy
          else
            docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:$PWD" -w "$PWD" \
              groovy:4.0-jdk21 groovy tests/unit/jenkins_unit_test.groovy
          fi
        '''
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

    stage('Publish images') {
      when {
        branch 'master'
      }
      steps {
        script {
          if (!registryCredentialsConfigured()) {
            echo "Not publishing or deploying: add the Jenkins credentials '${env.MSH_REGISTRY_CREDENTIALS}' " +
              '(a GitHub user and a token with write:packages) to deliver master.'
            return
          }
          env.MSH_IMAGE_TAG = "sha-${env.GIT_COMMIT.substring(0, 7)}"
          withCredentials([
            usernamePassword(credentialsId: env.MSH_REGISTRY_CREDENTIALS, usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_TOKEN')
          ]) {
            sh './deploy/ci/publish-images.sh'
          }
          env.MSH_IMAGES_PUBLISHED = 'true'
        }
      }
    }

    stage('Deploy') {
      when {
        allOf {
          branch 'master'
          environment name: 'MSH_IMAGES_PUBLISHED', value: 'true'
        }
      }
      steps {
        sh './deploy/ci/deploy-helm.sh'
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: '**/build/reports/tests/**', allowEmptyArchive: true, fingerprint: false
    }
  }
}

/** Whether this Jenkins has the registry credentials that delivery logs in with. */
boolean registryCredentialsConfigured() {
  try {
    withCredentials([
      usernamePassword(credentialsId: env.MSH_REGISTRY_CREDENTIALS, usernameVariable: 'UNUSED_USER', passwordVariable: 'UNUSED_TOKEN')
    ]) {
      echo "Found the registry credentials '${env.MSH_REGISTRY_CREDENTIALS}'."
    }
    return true
  } catch (error) {
    echo "The registry credentials '${env.MSH_REGISTRY_CREDENTIALS}' are not available: ${error.message}"
    return false
  }
}
