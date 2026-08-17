package com.cavisson.jenkins.pushservice;

import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;

import java.io.IOException;

/**
 * Pushes a service's Docker image and removes the local copy, matching the reference
 * Jenkinsfile's Groovy {@code pushService(service)} helper: a failed local {@code docker rmi} is
 * logged and ignored (matching the original's {@code || echo "Failed to remove local image,
 * skipping..."}), but a failed {@code docker push} aborts the build.
 */
final class PushServiceExecutor {

    private PushServiceExecutor() {
    }

    static void run(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars env,
                     String service) throws IOException, InterruptedException {

        CavLogger log = new CavLogger(listener, env);

        if (service == null || service.trim().isEmpty()) {
            throw new AbortException("service is required.");
        }

        String image = env.get("REGISTRY") + "/" + env.get("NAMESPACE") + "/" + service + ":" + env.get("IMAGE_TAG");

        log.info("Pushing Docker image: " + image);

        int pushExit = launcher.launch()
                .cmds("docker", "push", image)
                .pwd(workspace)
                .envs(env)
                .stdout(listener)
                .join();

        if (pushExit != 0) {
            throw new AbortException("Failed to push Docker image for service '" + service
                    + "'. Error: docker push exited with code " + pushExit);
        }

        int rmiExit = launcher.launch()
                .cmds("docker", "rmi", image)
                .pwd(workspace)
                .envs(env)
                .stdout(listener)
                .join();

        if (rmiExit != 0) {
            log.info("Failed to remove local image, skipping...");
        }
    }
}
