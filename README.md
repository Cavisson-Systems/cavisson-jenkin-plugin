# Cavisson CICD

## Introduction

Cavisson CICD connects Jenkins to the [Cavisson](https://www.cavisson.com) platform. It lets
Freestyle and Pipeline jobs run load and functional tests, security scans, code coverage, quality
gates and AI-assisted test generation and failure analysis against a Cavisson server, and publish
the results back into the build.

## Features

| Build step (Freestyle) | Pipeline step | What it does |
|---|---|---|
| Cavisson - Run Test | `cavissonRunTest` | Starts a Test Suite or Load Test and waits for it to finish, then archives the HTML report and publishes JUnit results |
| Cavisson - Create Functional Test Suite | `cavissonCreateTestSuite` | Creates a functional test suite from tags |
| Cavisson - Analyse Test Failure | `cavAnalyseTestFailure` | Runs AI failure analysis for a test run, or for every failing test case in a test suite run |
| Cavisson - AI Test Case Generation | `cavAITestCaseGeneration` | Generates test cases from JIRA epics or Git-hosted PRDs |
| Cavisson - Start / Stop Code Coverage | `cavissonStartCodeCoverage` / `cavissonStopCodeCoverage` | Collects code coverage around a test run |
| Cavisson - Security Plugin | `cavSecurityPlugin` | Runs SAST, SCA and DAST scans |
| Cavisson - Quality Gate | `cavQualityGate` | Fails or passes the build against Cavisson quality-gate rules |
| Cavisson - Accessibility Scanner | `cavAccessibilityScanner` | Scans a web application for accessibility issues |
| Cavisson - Git Checkout / Discover / Build / Push Service | `cavGitCheckout`, `discoverServices`, `buildService`, `pushService` | Git checkout and service image build/push helpers |

Every Pipeline step returns a `Map` of result values. Every step also publishes its results as
`CAV_*` environment variables for later steps in the same build. When a step produces a report,
it adds a "View Report" link to the build description.

## Getting started

1. Install **Cavisson CICD** from **Manage Jenkins → Plugins → Available plugins**.
2. Add a credential of kind **Cavisson Service Connection** with your Cavisson server URL and
   API token. Alternatively, use a **Secret text** credential with `connectionMode: 'direct'` and
   `baseUrl`.
3. Add a build step, or call it from a Pipeline:

```groovy
pipeline {
    agent any
    stages {
        stage('Cavisson Test') {
            steps {
                script {
                    def result = cavissonRunTest(
                        cavServiceConnectionId: 'cavisson-connection',
                        testType: 'TestSuite',
                        testSuiteName: 'RegressionSuite'
                    )
                    echo "Status: ${result.testStatus}, report: ${env.CAV_TSR_REPORT_URL}"
                }
            }
        }
    }
}
```

For more Pipeline examples covering every step, see [PIPELINE_EXAMPLES.md](PIPELINE_EXAMPLES.md).

### Configuration

- **Log verbosity**: set the `LOG_LEVEL` environment variable to `ERROR`, `INFO` (the default)
  or `DEBUG`. `DEBUG` also logs request and response payloads.
- **Build description**: set `CAV_SKIP_REPORT_IN_DESC=true` to stop steps from adding report
  links to the build description.
- **Self-signed certificates**: TLS certificates are verified by default. The recommended fix is
  to import the Cavisson server's certificate into the Java truststore of the Jenkins controller
  and agents. If that isn't possible, an administrator can enable **Manage Jenkins → System →
  Cavisson CICD → Skip TLS certificate verification**. This is insecure; use it only on trusted
  networks.

## Issues

Report issues and enhancement requests in the
[GitHub issue tracker](https://github.com/jenkinsci/cavisson-cicd-plugin/issues).

## Contributing

Refer to the Jenkins [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

Build and test locally with JDK 21:

```bash
mvn verify        # full build and tests
mvn hpi:run       # start a local Jenkins with the plugin installed
```

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE).
