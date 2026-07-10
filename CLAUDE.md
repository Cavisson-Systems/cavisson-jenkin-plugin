# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone Jenkins plugin (`cavisson-jenkin-plugin`, groupId `com.cavisson.jenkins.plugins`) that hosts Cavisson CI/CD execution tasks as both Freestyle build steps and Pipeline steps. It is a sibling of, but intentionally decoupled from, `cav-security-pipeline` (a separate Jenkins plugin at `prod-src/core/jenkins/cav-jenkins` that does SAST/SCA/DAST security scanning and owns its own `CavServiceConnection` credential type).

Tasks so far:
- `CavissonRunTest` ("Cavisson - Run Test" / Pipeline step `cavissonRunTest`) — triggers a Cavisson TestSuite or Load Test scenario on a DashboardServer instance and polls until it finishes. Reference implementation: the Azure DevOps extension at `../cav-load-test-azure-devops-extension` (`task/index.js`) — treat that file as the source of truth when porting behavior, not its README/ARCHITECTURE.md, which describe older/aspirational designs. That repo evolves independently; `git pull` it and diff `task/index.js` periodically when debugging behavior mismatches, since server-side response shapes and polling logic have changed there before without notice here.
- `CreateTestSuite` ("Cavisson - Create Functional Test Suite" / Pipeline step `cavissonCreateTestSuite`) — calls the Scenario Service's single synchronous `createTestSuite` REST endpoint (no polling). Reference: the OpenAPI spec at `prod-src/web/scenarioservices/openapi-createTestSuite.yaml`.
- `AnalyseTestFailure` ("Cavisson - Analyse Test Failure" / Pipeline step `cavAnalyseTestFailure` — note: no `cavisson` prefix, unlike the other two, by explicit request) — triggers the "Cav Codefix Agent" LLM failure-analysis flow for either one explicit Test Run (`trNumber`) or every failing testcase inside a Test Suite Run (`tsrNumber`), the latter with configurable concurrency (default 1, max 8). Reference: `analyze-failure-api-reference.md` (given as a one-off PDF, not checked into any repo — re-request it from whoever owns the Cav Codefix Agent service if the API changes).

## Commands

- Run all tests: `mvn test` (requires network access the first time — the parent POM and Jenkins test harness aren't cached for a fresh checkout; `-o` offline mode will fail until then).
- Run a single test class: `mvn test -Dtest=CavissonRunTestBuilderConfigRoundTripTest`
- Build the plugin without running tests: `mvn package -DskipTests` — produces `target/cavisson-jenkin-plugin.hpi`.
- Install manually: Jenkins → Manage Jenkins → Plugins → Advanced settings → Deploy Plugin → upload the `.hpi`.
- The `JenkinsRule`-based tests (`InjectedTest`, `CavissonRunTestBuilderConfigRoundTripTest`) boot an in-process Jenkins and take 10-60s each; this is normal, not a hang.

## Architecture

### Package layout — the pattern for adding a new task

```
com.cavisson.jenkins
├── http.HttpUtil                          — shared HTTP client (postJson/getJson, trust-all SSL), reused by every task
├── connection.{CavissonConnection, CavissonConnectionResolver}  — shared connection resolution, reused by every task
├── runtest.{CavissonRunTestExecutor, CavissonRunTestBuilder, CavissonRunTestStep}  — polling task
├── createtestsuite.{CreateTestSuiteExecutor, CreateTestSuiteBuilder, CreateTestSuiteStep}  — single-call task
└── analysefailure.{AnalyseTestFailureExecutor, AnalyseTestFailureBuilder, AnalyseTestFailureStep,
                     AnalysisTarget, JunitFailureParser}  — fan-out task (N concurrent 3-call flows)
```

Each task follows the same 3-class pattern, split because a classic `Builder` (needed for Freestyle jobs) cannot return a Pipeline value, and a `Step` (needed for a Pipeline return value) cannot appear in the Freestyle "Add build step" list:
- `<Task>Executor` — package-private, holds all the real logic (HTTP calls, polling if needed, report handling). No Jenkins extension annotations.
- `<Task>Builder extends Builder implements SimpleBuildStep` — Freestyle UI. Deliberately has **no `@Symbol`**, so it doesn't also register a Pipeline DSL function under the same name as the Step.
- `<Task>Step extends Step`, with a `StepDescriptor.getFunctionName()` and a `SynchronousNonBlockingStepExecution<Map<String,Object>>` inner execution class — Pipeline function, returns a `Map` so scripts can do `def result = cavissonRunTest(...); echo result.testStatus`.

Both `Builder` and `Step` hold identical fields/getters/setters/descriptors and just delegate to the shared `Executor`. To add a new task, copy this triplet into a new package (`com.cavisson.jenkins.<newtask>`) and reuse `http.HttpUtil` + `connection.CavissonConnectionResolver` unchanged. `CreateTestSuite` is the simpler template to copy from if the new task is a single request/response call with no polling (it doesn't need `Run`/`FilePath`/`Launcher` in its `Executor` signature or `Step`'s `getRequiredContext()` at all — just `Run`, `TaskListener`, `EnvVars`). `CavissonRunTest` is the template for anything that polls and/or archives artifacts. The ADO extension has two more not-yet-ported tasks (`CavissonStartCodeCoverage`, `CavissonStopCodeCoverage`) that are natural next candidates.

### Connection model

This plugin does **not** define its own credential type. `connectionMode` (a field, not a `radioBlock` — see gotcha below) selects between:
- **`direct`**: `baseUrl` (plain string) + `apiTokenCredentialId` (a Jenkins "Secret text" credential ID, i.e. `org.jenkinsci.plugins.plaincredentials.StringCredentials`, resolved via `CredentialsProvider.findCredentialById`).
- **`serviceConnection`**: `cavServiceConnectionId`, resolved via **pure reflection** against `com.cavisson.jenkins.security.CavServiceConnection` (the credential type owned by the separate `cav-security-pipeline` plugin). This has zero compile-time or Jenkins plugin-manifest dependency on that plugin — `Class.forName` is wrapped in a `ClassNotFoundException` catch that throws a clear `AbortException` if that plugin isn't installed. Do not add a Maven dependency on `cav-security-pipeline` to "simplify" this; the whole point is that this plugin stays installable standalone.

All of this lives in `CavissonConnectionResolver.resolve(...)`, which always returns a plain `CavissonConnection(baseUrl, apiToken)` regardless of which mode was used — `<Task>Executor` classes never need to know which mode was in play.

### Cavisson DashboardServer REST API (used by CavissonRunTest)

Base path: `{baseUrl}/DashboardServer/v2/scenario/cicd`. All requests carry a `cavToken` header (not `Authorization: Bearer`), and SSL verification is deliberately disabled (`allowInsecureSSL = true`, matching the ADO extension's `rejectUnauthorized: false` — self-signed certs are expected in the field).
- `POST /startTest` — triggers the run. **Do not trust the top-level `status` field to mean "trigger succeeded"** — a real server response has been observed as `{"success":true,"run":1059,"status":"PASS",...}` where `status` reflects something else entirely at this stage. Use the boolean `success` field (`CavissonRunTestExecutor.isStartSuccessful`), falling back to `status=="success"` only if `success` is absent.
- `GET /checkConnectionStatus?testRun=&testmode=&scenarioName=&replaceTR=false` — polled every 60s while `running=true`. Use the **`effectiveTestMode`** field from the `/startTest` response (`resolveEffectiveMode`) for the `testmode` param here, not the originally-requested mode — the server can normalize/override it, and polling with a stale mode can misbehave. Both `"fail"` and `"failed"` are terminal failure statuses (`isTerminalStatus`); anything else unrecognized aborts the build.
- `POST /getHtmlReport` — downloaded/archived only for Load Test (`effectiveMode == "N"`) runs, to `workspace/cavisson-report/TestSuiteReport_<runNo>.html` via `hudson.tasks.ArtifactArchiver`.
- `POST /getJunitReport` — downloaded and published (regardless of pass/fail/error outcome) whenever `effectiveMode == "T"` or the server's own `testType` field says `"functional"`, to `workspace/cavisson-test-results/junit-<reportRunNo>.xml` via `hudson.tasks.junit.JUnitResultArchiver`. Note `reportRunNo` is extracted from the `tsr` query parameter of the `reportUrl`/`HtmlReport` fields (`extractTsrFromUrl`), falling back to the original `runNo` — it can legitimately differ. `JUnitResultArchiver` will mark the Jenkins build UNSTABLE by default if the XML has failing tests, independent of `testStatus`.

**Deliberate behavior**: a completed test with `testStatus == "fail"` does **not** fail the Jenkins build/step — only `"error"` or an unrecognized status does. This matches the ADO extension exactly; don't "fix" it without checking with whoever owns this requirement.

### Scenario Service API (used by CreateTestSuite)

`POST {baseUrl}/DashboardServer/v2/scenario/data/createTestSuite` — always answers HTTP 200; the actual outcome is the `status` field (`"success"`/`"fail"`, unlike CavissonRunTest's `/startTest` this one is not misleading). `"fail"` here **does** abort the build (`CreateTestSuiteExecutor` throws `AbortException`) — unlike CavissonRunTest's test-verdict semantics, a failed test-suite-creation call is a real, actionable error, not a legitimate outcome to tolerate. `tags` is the only truly required field (comma-separated string in the Jenkins UI/Pipeline call, split into a JSON array before sending); everything else has server-side defaults documented in the OpenAPI spec. Full request/response schema: `prod-src/web/scenarioservices/openapi-createTestSuite.yaml`.

### Cav Codefix Agent API (used by AnalyseTestFailure)

Base path: `{baseUrl}/tomcat/master/DashboardServer/v2/web/cavOpenhands` — note this is a
**different** base path from the other two tasks' `/DashboardServer/v2/scenario/...`; same
`cavToken` header convention regardless. Three calls per test run analysed:
- `POST /prepareAnalysisContext` — body `{scenario, projectName, subProjectName, userName,
  workProfileName, trNumber}`. Response `{conversationId, sessionKey, conversationUrl, promptText,
  gitconfig, error}`. Non-null/non-empty `error` → that target's analysis is recorded as `error`,
  Call 2/3 are skipped for it (`AnalyseTestFailureExecutor.errorMessage`).
- `POST /runAnalysisAsCli` — body = the 6 original input fields merged into the *full* Call 1
  response object (including `gitconfig` verbatim — never add `token`/`apiKey` fields, the server
  re-fetches secrets itself). Same response shape/error handling as Call 1.
- `GET /shellStatus?conversationId=...` — poll every **30s**, up to **20 attempts (10 min)** —
  different cadence from `CavissonRunTest`'s 60s test-status poll, per the reference doc.
  `running`/`unknown` (record not committed to MongoDB yet) → keep polling; `completed` → success;
  `failed` → record its `error` field; no terminal state after 20 attempts → recorded as `timeout`.

**`tsrNumber` vs `trNumber` input**: exactly one must be given (validated in the Executor, not
just at the form level, since Pipeline calls skip form validation). With a bare `trNumber`,
`scenario`/`project`/`subProject`/`userName`/`workProfileName` must be supplied explicitly as
task inputs. With `tsrNumber`, none of those are needed — `JunitFailureParser` derives them per
failing testcase straight from that TSR's JUnit report: testsuite-level `started_by` →
`userName`, `workspace` (e.g. `"admin/system"`) second segment → `workProfileName`; each failing
`<testcase>`'s own `tr_number` property and its `name` attribute (e.g. `"AI/demo/testcaseName"`)
→ `trNumber` and `project`/`subProject`/`scenario`. The JUnit report itself is reused from
`workspace/cavisson-test-results/junit-<tsrNumber>.xml` if `CavissonRunTest` already fetched it
earlier in the same build; otherwise fetched fresh via the same `getJunitReport` endpoint
`CavissonRunTestExecutor` uses, and parsed **in memory only** — nothing new written to disk.

**Deliberate behavior**: an individual analysis ending in `failed`/`timeout` does **not** fail the
build — same "report, don't abort" philosophy as `CavissonRunTest`'s test-verdict handling, since
with `tsrNumber` there can be several concurrent analyses and one bad outcome shouldn't discard
the rest. Real infrastructure failures (bad connection, the JUnit-fetch call itself failing) still
throw `AbortException` as usual — this carve-out is only for per-target analysis outcomes.

**Concurrency**: `Executors.newFixedThreadPool(clampConcurrency(requested))`, one `Callable` per
failing testcase, silently clamped to `[1,8]` (`AnalyseTestFailureExecutor.clampConcurrency`) — no
validation error for out-of-range values, unlike most other required fields in this plugin.

### Result values as environment variables

Every task publishes its result values as plain environment variables, visible to later steps in the *same* build/Pipeline run (in addition to the `Map` a Pipeline step call returns directly):
- `CavissonRunTest` → `CAV_TSR_NUMBER`, `CAV_TSR_STATUS`, `CAV_TSR_REPORT_URL` ("TSR" = Test Suite Run).
- `CreateTestSuite` → `CAV_NEW_TESTSUITE_NAME`.
- `AnalyseTestFailure` → `CAV_ANALYSIS_COUNT`, `CAV_ANALYSIS_COMPLETED_COUNT`,
  `CAV_ANALYSIS_FAILED_COUNT`, `CAV_ANALYSIS_RESULTS_JSON` (the full per-target results list,
  serialized as JSON — this is the *only* way a Freestyle build can see per-target detail, since
  Freestyle can't read a Pipeline step's return value and a single scalar env var doesn't fit a
  variable-length result list).

This is done via `com.cavisson.jenkins.env`: each `<Task>Executor` calls `CavissonEnvironmentPublisher.publish(run, Map<String,String>)` once it has its result. That attaches a small internal `CavissonEnvironmentAction` (an `InvisibleAction`) to the `Run`; a single global `@Extension CavissonEnvironmentContributor extends EnvironmentContributor` merges every such action's vars into `Run#getEnvironment(...)` whenever anything asks for the build's environment. **Do not use `hudson.model.EnvironmentContributingAction` for this** — its `buildEnvVars(AbstractBuild, EnvVars)` signature only fires for Freestyle builds; `WorkflowRun` (Pipeline) isn't an `AbstractBuild`, so Pipeline runs would silently never see the vars. `EnvironmentContributor` is the one extension point that's generic over `Run` and works for both. A new task just needs to build its own vars map and call `CavissonEnvironmentPublisher.publish(run, vars)` — no other wiring required.

### Log verbosity via `LOG_LEVEL`

Every task's `Executor` builds a `com.cavisson.jenkins.log.CavLogger` (`new CavLogger(listener, env)`) at the top of its `run(...)` and uses it instead of calling `listener.getLogger().println(...)` directly. The build/Pipeline environment variable `LOG_LEVEL` (`ERROR`|`INFO`|`DEBUG`, unset or unrecognized falls back to `INFO`, matching the old unconditional-logging behavior) controls what prints: `log.error(...)` always prints (banner-less warnings and messages that precede an `AbortException`), `log.info(...)` is the normal per-task narration (banners, triggered/completed messages, polling status) and only prints at `INFO`/`DEBUG`, `log.debug(...)` is raw request/response JSON payloads (`startTest`/`codeCovStart`/`codeCovStop`/`createTestSuite` responses, the three-call Cav Codefix Agent request/response bodies) and only prints at `LOG_LEVEL=DEBUG`. A new task should do the same — construct one `CavLogger` per `run(...)` call and thread it through any private helper methods instead of `TaskListener`, reserving `TaskListener` itself only for APIs that require it verbatim (e.g. `ArtifactArchiver`/`JUnitResultArchiver#perform`).

### Report URL in the build description

Any task that produces a report URL (`CavissonRunTest`'s `reportUrl`, `CavissonStopCodeCoverage`'s `fullReportUrl`, `AnalyseTestFailure`'s constructed `.../share.html?open=analysisfailure-test-report&pipelineId=...&pipelineRunId=...` link) calls `com.cavisson.jenkins.env.CavissonDescriptionPublisher.appendReportRow(run, env, reportUrl)` after logging/publishing it. This appends a `<table><tr><td><b>${STAGE_NAME}</b></td><td><a href='...'>📊 View Report</a></td></tr></table>` row to `Run#setDescription(...)`, inserting new rows before the closing `</table>` on repeat calls so multiple tasks/stages in the same build each get their own row instead of clobbering each other. `CreateTestSuite` and `CavissonStartCodeCoverage` don't call this — they don't produce a viewable report. Unset (the default) means the row is always appended; set the build/Pipeline env var `CAV_SKIP_REPORT_IN_DESC=true` (`1`/`yes` also accepted, case-insensitive) to opt out of the description write entirely; the report URL is still logged and published as an env var regardless. `IOException` from `Run#setDescription` is non-fatal — caught and logged via `log.error(...)`, matching this plugin's "report, don't abort" philosophy for cosmetic failures.

### Free-text input expansion

Every free-text field (not a `f:select` dropdown or credential picker) is expanded against the build's environment variables via `EnvVars#expand(...)`, so a value like `https://${CAV_HOST}:4444` resolves normally. Each `<Task>Executor` already expands its own plain fields (`project`, `tags`, etc.) — `baseUrl` and `cavServiceConnectionId` are the two connection-related exceptions, expanded centrally inside `CavissonConnectionResolver.resolve(...)` (which now takes an `EnvVars` parameter) since both tasks share that call. `apiTokenCredentialId` and `connectionMode` are deliberately **not** expanded — they're pickers, not free text; there's no realistic use case for templating a credential ID or a fixed-choice mode string.

### Jelly form gotcha (cost two rounds of debugging in production)

`f:radioBlock` elements sharing a `name` submit a **bundled JSON object** (`{"value": "direct", ...sibling fields}`) so Stapler can tell which block was active. That bundled shape does **not** bind to a plain `String` property — not via `@DataBoundConstructor`, and not via `@DataBoundSetter` either. Both were tried and both crashed real Freestyle job saves with `NoStaplerConstructorException` / `IllegalArgumentException`. The fix: use `<f:select>` for any mode/discriminator field (`connectionMode`, `testType`) with all dependent fields rendered flat/always-visible, not conditionally hidden via `radioBlock`. `doCheck*` methods still gate *requiredness* per mode via a `@QueryParameter String connectionMode` sibling parameter — only the visual conditional-hide is what was given up.

Because this class of bug only shows up on a real form **submit**, not on Jelly parsing, `CavissonRunTestBuilderConfigRoundTripTest` uses `JenkinsRule#configRoundtrip` to actually render the config page and submit it through a headless browser — this is the only test type that would have caught it. When adding fields to a Builder/Step, add or extend a round-trip test rather than trusting the built-in Jelly-parse-only test suite.

### Dependency version pins (don't bump casually — each was forced by `RequireUpperBoundDeps`)

`jenkins.version` is pinned to `2.346.3`. Versions in `pom.xml` were chosen as "highest release whose `requiredCore` is ≤ 2.346.3", then adjusted when the enforcer plugin found conflicts:
- `workflow-step-api:639.v6eca_cd8c04a_a_` — needed by the `junit` plugin's own transitive requirement (a lower version conflicts).
- `structs:308.v852b473a2b8c` — required transitively by `workflow-step-api`.
- `plain-credentials:1.8` — a newer release (`139.x`) pulls a `credentials` transitive version far too new for this `jenkins.version` baseline; 1.8 is the one that resolves cleanly.
- `junit:1150.v5c2848328b_60` plus a `<dependencyManagement>` override pinning `io.jenkins.plugins:font-awesome-api:6.1.1-1` — the `junit` plugin's own two transitive deps (`echarts-api`, `bootstrap5-api`) pull two different, conflicting `font-awesome-api` versions internally.

If you bump any of these, re-run `mvn test` fully (not just compile) — the enforcer runs in an early phase and will fail loudly with the exact conflicting paths if something regresses.

### Known test flakiness

The `JenkinsRule#configRoundtrip`-based tests occasionally fail with `FailingHttpStatusCodeException: 404 Not Found` for a static JS resource (e.g. `autocomplete-debug.js`) — this is an HtmlUnit/test-harness timing flake related to the growing set of installed plugins' bundled UI resources (first seen after adding the `junit` plugin), not a real binding/functional bug. If a round-trip test fails, re-run just that class in isolation (`mvn test -Dtest=<ClassName>`) before assuming a regression — it has cleared on retry every time so far.
