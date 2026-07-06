# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone Jenkins plugin (`cavisson-jenkin-plugin`, groupId `com.cavisson.jenkins.plugins`) that hosts Cavisson CI/CD execution tasks as both Freestyle build steps and Pipeline steps. It currently has one task, `CavissonRunTest` ("Cavisson - Run Test" / Pipeline step `cavissonRunTest`), which triggers a Cavisson TestSuite or Load Test scenario on a DashboardServer instance and polls until it finishes. It is a sibling of, but intentionally decoupled from, `cav-security-pipeline` (a separate Jenkins plugin at `prod-src/core/jenkins/cav-jenkins` that does SAST/SCA/DAST security scanning and owns its own `CavServiceConnection` credential type). This plugin's reference implementation is the Azure DevOps extension at `../cav-load-test-azure-devops-extension` (`task/index.js`) — treat that file as the source of truth when porting behavior, not its README/ARCHITECTURE.md, which describe older/aspirational designs. That repo evolves independently; `git pull` it and diff `task/index.js` periodically when debugging behavior mismatches, since server-side response shapes and polling logic have changed there before without notice here.

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
└── runtest.{CavissonRunTestExecutor, CavissonRunTestBuilder, CavissonRunTestStep}  — one task
```

Each task follows the same 3-class pattern, split because a classic `Builder` (needed for Freestyle jobs) cannot return a Pipeline value, and a `Step` (needed for a Pipeline return value) cannot appear in the Freestyle "Add build step" list:
- `<Task>Executor` — package-private, holds all the real logic (HTTP calls, polling, report handling). No Jenkins extension annotations.
- `<Task>Builder extends Builder implements SimpleBuildStep` — Freestyle UI. Deliberately has **no `@Symbol`**, so it doesn't also register a Pipeline DSL function under the same name as the Step.
- `<Task>Step extends Step`, with a `StepDescriptor.getFunctionName()` and a `SynchronousNonBlockingStepExecution<Map<String,Object>>` inner execution class — Pipeline function, returns a `Map` so scripts can do `def result = cavissonRunTest(...); echo result.testStatus`.

Both `Builder` and `Step` hold identical fields/getters/setters/descriptors and just delegate to the shared `Executor`. To add a new task, copy this triplet into a new package (`com.cavisson.jenkins.<newtask>`) and reuse `http.HttpUtil` + `connection.CavissonConnectionResolver` unchanged. The ADO extension has two more tasks not yet ported here (`CavissonStartCodeCoverage`, `CavissonStopCodeCoverage`) that are natural next candidates.

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
