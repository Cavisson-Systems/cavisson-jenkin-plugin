package com.cavisson.jenkins.env;

import hudson.model.Run;

import java.util.Map;

/**
 * Single entry point tasks use to expose their result values as environment variables to later
 * steps in the same build (Freestyle or Pipeline). See {@link CavissonEnvironmentAction} /
 * {@link CavissonEnvironmentContributor} for how this is actually wired into
 * {@code Run#getEnvironment}.
 */
public final class CavissonEnvironmentPublisher {

    private CavissonEnvironmentPublisher() {
    }

    public static void publish(Run<?, ?> run, Map<String, String> vars) {
        run.addAction(new CavissonEnvironmentAction(vars));
    }
}
