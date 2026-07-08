package com.cavisson.jenkins.analysefailure;

/**
 * One testcase/test-run to analyse: either built directly from explicit task inputs (trNumber
 * path), or derived from a failing &lt;testcase&gt; in a TSR's JUnit report (tsrNumber path).
 */
final class AnalysisTarget {

    final String trNumber;
    final String scenario;
    final String project;
    final String subProject;
    final String userName;
    final String workProfileName;

    AnalysisTarget(String trNumber, String scenario, String project, String subProject,
                   String userName, String workProfileName) {
        this.trNumber = trNumber;
        this.scenario = scenario;
        this.project = project;
        this.subProject = subProject;
        this.userName = userName;
        this.workProfileName = workProfileName;
    }
}
