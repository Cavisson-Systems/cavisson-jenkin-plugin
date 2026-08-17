package com.cavisson.jenkins.buildservice;

import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.IOException;

/**
 * Builds a service's Docker image, matching the reference Jenkinsfile's Groovy
 * {@code buildService(service)} helper exactly: locates the service's Dockerfile under
 * {@code src/<service>/**}, builds from that directory with {@code --no-cache --network host},
 * tagged {@code $REGISTRY/$NAMESPACE/<service>:$IMAGE_TAG}.
 */
final class BuildServiceExecutor {

    private BuildServiceExecutor() {
    }

    static void run(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars env,
                     String service) throws IOException, InterruptedException {

        CavLogger log = new CavLogger(listener, env);

        if (service == null || service.trim().isEmpty()) {
            throw new AbortException("service is required.");
        }

        FilePath[] dockerfiles;
        try {
            dockerfiles = workspace.list("src/" + service + "/**/Dockerfile");
        } catch (IOException | InterruptedException e) {
            throw new AbortException("Failed to build Docker image for service '" + service + "'. Error: " + e.getMessage());
        }
        if (dockerfiles.length == 0) {
            throw new AbortException("No Dockerfile found for " + service);
        }

        FilePath buildDir = dockerfiles[0].getParent();
        String image = env.get("REGISTRY") + "/" + env.get("NAMESPACE") + "/" + service + ":" + env.get("IMAGE_TAG");

        log.info("Building Docker image: " + image);

        ArgumentListBuilder args = new ArgumentListBuilder(
                "docker", "build", "--no-cache", "--network", "host", "-t", image, ".");

        int exitCode = launcher.launch()
                .cmds(args)
                .pwd(buildDir)
                .envs(env)
                .stdout(listener)
                .join();

        if (exitCode != 0) {
            throw new AbortException("Failed to build Docker image for service '" + service
                    + "'. Error: docker build exited with code " + exitCode);
        }
    }
}
