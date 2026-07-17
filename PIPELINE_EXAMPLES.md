# Cavisson Jenkins Plugin — Pipeline Usage Examples

Every task is exposed as a Pipeline step that returns a `Map<String,Object>` (so you can do
`def result = cavissonRunTest(...); echo result.testStatus`) and also publishes its result values
as environment variables (`env.CAV_...`) for later stages/steps in the same run.

Two connection modes exist, selected via `connectionMode`:

- `direct` — `baseUrl` (plain string) + `apiTokenCredentialId` (a Jenkins "Secret text" credential ID).
- `serviceConnection` (default) — `cavServiceConnectionId` (a `CavServiceConnection` credential ID).
  `CavServiceConnection` is this plugin's own credential type (`com.cavisson.jenkins.connection`), not a
  dependency on the separate `cav-security-pipeline` plugin.

**`connectionMode` itself is never mandatory to write in a Pipeline script.** Every field backing it
has a plugin-provided default (`connectionMode = "serviceConnection"`), so if you just pass
`cavServiceConnectionId: '...'` and omit `connectionMode` entirely, the step still resolves via
Service Connection mode correctly:
```groovy
cavissonRunTest(
    cavServiceConnectionId: 'cav-service-connection-id',   // connectionMode omitted -> defaults to serviceConnection
    testType: 'TestSuite',
    testSuiteName: 'RegressionSuite'
)
```
You only need to write `connectionMode: 'direct'` explicitly when you want the Direct
(Base URL + API Token) path instead of the default. What *is* mandatory is supplying the fields for
whichever mode ends up active — either `cavServiceConnectionId` (default path), or `baseUrl` +
`apiTokenCredentialId` (only reached if you set `connectionMode: 'direct'`). This is enforced both by
the Freestyle UI's `doCheck*` validators and, for Pipeline (which skips form validation), by
`CavissonConnectionResolver.resolve(...)` at runtime — with neither mode's fields filled in, the build
aborts with a clear "X is required" error rather than silently doing nothing.

Only fill in the fields relevant to the mode you pick; the other mode's fields can simply be omitted.
In the Freestyle UI, `cavServiceConnectionId` (and its per-task equivalents below) is a dropdown
populated from every `CavServiceConnection` credential visible to the job, not a free-text field.

---

## 1. `cavissonRunTest`

### Direct mode
```groovy
pipeline {
    agent any
    stages {
        stage('Run Cavisson Test') {
            steps {
                script {
                    def result = cavissonRunTest(
                        connectionMode: 'direct',
                        baseUrl: 'https://dashboard.cavisson.com',
                        apiTokenCredentialId: 'cav-api-token',   // Secret text credential ID
                        testType: 'TestSuite',                  // or 'LoadTest'
                        project: 'default',
                        subProject: 'default',
                        username: 'Cavisson',
                        profile: 'default',
                        testSuiteName: 'RegressionSuite',
                        scenarioName: 'CheckoutFlow'
                    )
                    echo "Test status: ${result.testStatus}"
                    echo "Report URL: ${env.CAV_TSR_REPORT_URL}"
                }
            }
        }
    }
}
```

### Service connection mode
```groovy
cavissonRunTest(
    cavServiceConnectionId: 'cav-service-connection-id',
    testType: 'LoadTest',
    project: 'default',
    subProject: 'default',
    testSuiteName: 'RegressionSuite',
    scenarioName: 'CheckoutFlow'
)
```

Published env vars: `CAV_TSR_NUMBER`, `CAV_TSR_STATUS`, `CAV_TSR_REPORT_URL`.

---

## 2. `cavissonCreateTestSuite`

### Direct mode
```groovy
def result = cavissonCreateTestSuite(
    connectionMode: 'direct',
    baseUrl: 'https://dashboard.cavisson.com',
    apiTokenCredentialId: 'cav-api-token',
    project: 'default',
    subProject: 'default',
    workspace: 'admin',
    profile: 'system',
    name: 'NewFunctionalSuite',
    tags: 'smoke,regression',      // comma-separated, only truly required field besides connection
    automatedOnly: true,
    gitIntegration: 'github',
    commitId: env.GIT_COMMIT,
    codeMappingMode: 'matchPC'     // '', 'matchPCM', 'matchPC', or 'matchP'
)
echo "Created suite: ${env.CAV_NEW_TESTSUITE_NAME}"
```

### Service connection mode
```groovy
cavissonCreateTestSuite(
    cavServiceConnectionId: 'cav-service-connection-id',
    name: 'NewFunctionalSuite',
    tags: 'smoke,regression'
)
```

Note: a `"fail"` status here **aborts the build** (unlike RunTest's test-verdict handling) —
a failed test-suite-creation call is treated as a real error.

Published env var: `CAV_NEW_TESTSUITE_NAME`.

---

## 3. `cavAnalyseTestFailure`

Exactly one of `tsrNumber` or `trNumber` must be given.

### By Test Suite Run (tsrNumber) — direct mode
```groovy
def result = cavAnalyseTestFailure(
    connectionMode: 'direct',
    baseUrl: 'https://dashboard.cavisson.com',
    apiTokenCredentialId: 'cav-api-token',
    tsrNumber: '1059',
    concurrency: 4           // clamped server-side to [1,8]
)
echo "Analyzed: ${env.CAV_ANALYSIS_COUNT}, completed: ${env.CAV_ANALYSIS_COMPLETED_COUNT}"
```
With `tsrNumber`, `scenario`/`project`/`subProject`/`userName`/`workProfileName` are derived
automatically per failing testcase from that TSR's JUnit report — don't pass them.

### By single Test Run (trNumber) — service connection mode
```groovy
cavAnalyseTestFailure(
    cavServiceConnectionId: 'cav-service-connection-id',
    trNumber: '2201',
    scenario: 'CheckoutFlow',
    project: 'AI',
    subProject: 'demo',
    userName: 'admin',
    workProfileName: 'system'
)
```
With `trNumber`, the five identity fields above are all required explicitly.

Published env vars: `CAV_ANALYSIS_COUNT`, `CAV_ANALYSIS_COMPLETED_COUNT`,
`CAV_ANALYSIS_FAILED_COUNT`, `CAV_ANALYSIS_RESULTS_JSON` (full per-target results, JSON string).

An individual analysis ending `failed`/`timeout` does not fail the build — only infra failures
(bad connection, JUnit-fetch failure) throw `AbortException`.

---

## 4. `cavissonStartCodeCoverage` / `cavissonStopCodeCoverage`

Same connection-field shape as the tasks above, plus `applicationName`.

```groovy
cavissonStartCodeCoverage(
    connectionMode: 'direct',
    baseUrl: 'https://dashboard.cavisson.com',
    apiTokenCredentialId: 'cav-api-token',
    applicationName: 'CheckoutService'
)

// ... run tests ...

def result = cavissonStopCodeCoverage(
    cavServiceConnectionId: 'cav-service-connection-id',
    applicationName: 'CheckoutService'
)
echo "Coverage report: ${result.fullReportUrl}"
```

---

## Full example: chaining several tasks in one Pipeline

```groovy
pipeline {
    agent any
    environment {
        LOG_LEVEL = 'INFO'   // ERROR | INFO | DEBUG — controls Cavisson task log verbosity
    }
    stages {
        stage('Create Suite') {
            steps {
                cavissonCreateTestSuite(
                    cavServiceConnectionId: 'cav-service-connection-id',
                    name: 'NightlySuite',
                    tags: 'nightly'
                )
            }
        }
        stage('Run Test') {
            steps {
                script {
                    def result = cavissonRunTest(
                        cavServiceConnectionId: 'cav-service-connection-id',
                        testType: 'TestSuite',
                        testSuiteName: env.CAV_NEW_TESTSUITE_NAME
                    )
                    env.CAV_LAST_TSR = result.tsrNumber
                }
            }
        }
        stage('Analyse Failures') {
            when { expression { env.CAV_TSR_STATUS != 'success' } }
            steps {
                cavAnalyseTestFailure(
                    cavServiceConnectionId: 'cav-service-connection-id',
                    tsrNumber: env.CAV_LAST_TSR,
                    concurrency: 4
                )
            }
        }
    }
}
```

---

## 5. `cavSecurityPlugin` — SAST / SCA / DAST security scans

This step lives in this repo (`com.cavisson.jenkins.security`) and now supports the same
`connectionMode` (`direct` / `serviceConnection`, defaults to `serviceConnection`) as the tasks
above, using `cavScanServiceConnection` in place of `cavServiceConnectionId` for the service
connection field. Also takes a required `scanType`, accepting `SAST`/`static`, `SCA`/`container`,
or `DAST`/`dynamic` (normalized internally, case-insensitive).

### SAST (static code) scan — service connection mode
```groovy
cavSecurityPlugin(
    cavScanServiceConnection: 'cav-service-connection-id',
    scanType: 'SAST',
    project: 'CheckoutService',
    targetPath: '.',
    qualityGate: 'default-sast-gate',
    qualityGateTimeout: '300'
)
```

### SAST (static code) scan — direct mode
```groovy
cavSecurityPlugin(
    connectionMode: 'direct',
    baseUrl: 'https://dashboard.cavisson.com',
    apiTokenCredentialId: 'cav-api-token',
    scanType: 'SAST',
    project: 'CheckoutService',
    targetPath: '.',
    qualityGate: 'default-sast-gate',
    qualityGateTimeout: '300'
)
```

### SCA / container image scan (Trivy)
```groovy
cavSecurityPlugin(
    cavScanServiceConnection: 'cav-service-connection-id',
    scanType: 'SCA',
    project: 'CheckoutService',
    runMode: 'standalone',           // or 'cluster'
    trivyMode: 'image',              // Trivy target mode
    trivyTarget: 'myrepo/checkout-service:latest',
    trivyReportDir: 'trivy-reports',
    scaMode: '',
    scaRegistryUrl: 'https://registry.example.com',
    scaRepository: 'checkout-service',
    scaImageName: 'checkout-service',
    scaImageTag: 'latest',
    containerClusterName: 'prod-cluster',
    containerNamespace: 'default'
)
```

### DAST (dynamic) scan (OWASP ZAP)
```groovy
cavSecurityPlugin(
    cavScanServiceConnection: 'cav-service-connection-id',
    scanType: 'DAST',
    project: 'CheckoutService',
    zapMode: 'baseline',             // or 'full', 'apiScan', etc.
    zapTarget: 'https://staging.example.com',
    zapApiFormat: 'openapi',
    zapReportDir: 'zap-reports',
    dastMode: '',
    dastTarget: 'https://staging.example.com',
    dastApiFormat: 'openapi',
    dastReportDir: 'dast-reports',
    dynamicClusterName: 'staging-cluster',
    dynamicNamespace: 'default',
    targetUrl: 'https://staging.example.com'
)
```

Result is a `Map<String,Object>` like the other tasks; check `qualityGate`/`qualityGateTimeout`
per stage, or evaluate a single combined gate at the end of the pipeline with:

```groovy
cavQualityGate(
    cavConnection: 'cav-service-connection-id',
    qualityGateName: 'release-gate',
    qualityGateTimeout: '600'
)
```
`cavConnection` is required only when `connectionMode` is `serviceConnection` (the default);
`qualityGateName` is always required. Direct mode also works here:
```groovy
cavQualityGate(
    connectionMode: 'direct',
    baseUrl: 'https://dashboard.cavisson.com',
    apiTokenCredentialId: 'cav-api-token',
    qualityGateName: 'release-gate',
    qualityGateTimeout: '600'
)
```

---

## 6. `cavAITestCaseGeneration` — AI-generated test cases from a PRD

Service-connection mode only — there is no `direct`/`baseUrl`/`apiTokenCredentialId` option here,
because JIRA-publish and Git-clone credentials (`publishUserStories`, `integrationName`, `gitUsername`,
`gitCredential`, ...) live only on the `CavServiceConnection` credential object itself.
Only `cavServiceConnectionId` is required; everything else has a plugin-provided default.

### Minimal
```groovy
cavAITestCaseGeneration(
    cavServiceConnectionId: 'cav-service-connection-id'
)
```

### Full example — PRD pulled from a Git repo
```groovy
cavAITestCaseGeneration(
    cavServiceConnectionId: 'cav-service-connection-id',
    applicationUrl: 'https://staging.example.com',
    workspaceRoot: 'admin/system',
    project: 'CheckoutService',
    subProject: 'default',
    controllerName: 'default-controller',
    numberOfTestCases: 10,
    prdSourceType: 'GIT',                     // or 'LOCAL'
    gitRepoUrl: 'https://github.com/org/repo.git',
    gitBranch: 'main',
    gitPrdPath: 'docs/prd/checkout.md',
    prdParameterName: 'PRD_FILE_UPLOAD',
    jiraEpicPattern: 'CHK-\\d+',
    jiraIntegrationName: 'jira-checkout',
    sourceType: 'requirements',
    testSuiteName: 'AI-Generated-Checkout',
    username: 'svc-account',
    password: credentials('cav-app-password'),
    authenticationPrompt: 'Log in with the given username and password',
    tags: ['team=checkout', 'source=ai']
)
```

Note: `password`/`username` here are the *application-under-test* login credentials used to
teach the AI agent how to authenticate, not the Cavisson connection credential.

---

## Notes

- Free-text fields (e.g. `project`, `baseUrl`, `tags`) support `EnvVars` expansion:
  `baseUrl: 'https://${CAV_HOST}:4444'` resolves normally. `connectionMode`,
  `apiTokenCredentialId`, and `cavServiceConnectionId` are pickers, not expanded.
- Set `CAV_SKIP_REPORT_IN_DESC=true` as a build/Pipeline env var to skip the "View Report" row
  appended to the build description by tasks that produce a report URL.
- `apiTokenCredentialId` must reference a Jenkins **Secret text** credential
  (`org.jenkinsci.plugins.plaincredentials.StringCredentials`), created under
  Manage Jenkins → Credentials.
- `cavServiceConnectionId` (and its per-task equivalents `cavConnection`/`cavScanServiceConnection`)
  reference a `CavServiceConnection` credential, this plugin's own credential type
  (`com.cavisson.jenkins.connection.CavServiceConnection`) — no dependency on any other plugin.
  If the given ID doesn't resolve to an existing credential, the build aborts with a clear error.
