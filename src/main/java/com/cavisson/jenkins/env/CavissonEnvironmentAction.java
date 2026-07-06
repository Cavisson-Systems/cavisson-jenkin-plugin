package com.cavisson.jenkins.env;

import hudson.model.InvisibleAction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries a task's result values (e.g. CAV_TSR_NUMBER, CAV_NEW_TESTSUITE_NAME) as an invisible
 * {@link hudson.model.Action} attached to the {@link hudson.model.Run}, so they can be exposed
 * as environment variables to later steps in the same build. Multiple instances can be attached
 * to the same run (one per task invocation); {@link CavissonEnvironmentContributor} merges all
 * of them. A plain {@code hudson.model.EnvironmentContributingAction} would only work for
 * Freestyle builds (its {@code buildEnvVars} takes an {@code AbstractBuild}); this action plus a
 * generic {@code EnvironmentContributor} works for both Freestyle and Pipeline runs.
 */
final class CavissonEnvironmentAction extends InvisibleAction {

    private final Map<String, String> vars;

    CavissonEnvironmentAction(Map<String, String> vars) {
        this.vars = new LinkedHashMap<>(vars);
    }

    Map<String, String> getVars() {
        return Collections.unmodifiableMap(vars);
    }
}
