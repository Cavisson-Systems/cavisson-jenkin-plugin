package com.cavisson.jenkins.env;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * Merges every {@link CavissonEnvironmentAction} attached to a run into that run's environment,
 * so values published via {@link CavissonEnvironmentPublisher#publish} are visible to later
 * build/Pipeline steps as plain environment variables. Works for both Freestyle and Pipeline
 * runs, unlike {@code hudson.model.EnvironmentContributingAction} (Freestyle-only).
 */
@Extension
public class CavissonEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(Run run, EnvVars envs, TaskListener listener) {
        for (CavissonEnvironmentAction action : run.getActions(CavissonEnvironmentAction.class)) {
            envs.putAll(action.getVars());
        }
    }
}
