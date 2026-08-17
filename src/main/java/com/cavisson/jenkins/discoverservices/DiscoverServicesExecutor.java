package com.cavisson.jenkins.discoverservices;

import com.cavisson.jenkins.log.CavLogger;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Auto-discovers service names from Dockerfile locations under {@code src/*} - the same glob and
 * exclusion the reference Jenkinsfile's Groovy {@code discoverServices()} helper used
 * ({@code findFiles(glob: "src/**Dockerfile")}, excluding {@code shoppingassistantservice}), now a
 * real Pipeline DSL step instead of an undefined Groovy function.
 */
final class DiscoverServicesExecutor {

    private static final String DOCKERFILE_GLOB = "src/**/Dockerfile";
    private static final String EXCLUDED_SERVICE = "shoppingassistantservice";

    private DiscoverServicesExecutor() {
    }

    static List<String> run(FilePath workspace, TaskListener listener, EnvVars env) throws IOException, InterruptedException {
        CavLogger log = new CavLogger(listener, env);

        try {
            FilePath[] dockerfiles = workspace.list(DOCKERFILE_GLOB);

            LinkedHashSet<String> services = new LinkedHashSet<>();
            for (FilePath dockerfile : dockerfiles) {
                String[] parts = relativePath(workspace, dockerfile).split("/");
                if (parts.length < 2) {
                    continue;
                }
                String service = parts[1];
                if (!EXCLUDED_SERVICE.equals(service)) {
                    services.add(service);
                }
            }

            List<String> result = new ArrayList<>(services);
            log.info("Discovered services: " + result);
            return result;
        } catch (IOException | InterruptedException e) {
            throw new AbortException("Error occurred while discovering Dockerfiles in src directory: " + e.getMessage());
        }
    }

    private static String relativePath(FilePath workspace, FilePath file) {
        String workspacePath = workspace.getRemote().replace('\\', '/');
        String filePath = file.getRemote().replace('\\', '/');
        if (filePath.startsWith(workspacePath)) {
            filePath = filePath.substring(workspacePath.length());
        }
        while (filePath.startsWith("/")) {
            filePath = filePath.substring(1);
        }
        return filePath;
    }
}
