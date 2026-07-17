package com.cavisson.jenkins.security;

import com.cavisson.jenkins.scriptlog.CavLogger;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the bundled scripts (cav_scanner.sh, trivy-standalone-wrapper.sh,
 * zap-standalone-wrapper.sh, trivy_json_to_html.py -- copied verbatim from the ADO extension,
 * they contain no ADO-specific logic) onto the build node and runs them through the Jenkins
 * {@link Launcher}, mirroring execFileSync("bash", [scriptPath, ...args]) from index.js.
 */
final class ScriptRunner {

    private static final String SCRIPTS_DIR = ".cav-security-pipeline";

    private ScriptRunner() {
    }

    static FilePath materializeScript(FilePath workspace, String resourceName) throws IOException, InterruptedException {
        FilePath scriptsDir = workspace.child(SCRIPTS_DIR);
        scriptsDir.mkdirs();

        FilePath target = scriptsDir.child(resourceName);

        try (InputStream resourceStream = ScriptRunner.class.getResourceAsStream("/scripts/" + resourceName)) {
            if (resourceStream == null) {
                throw new IOException("Bundled script not found in plugin resources: scripts/" + resourceName);
            }
            target.copyFrom(resourceStream);
        }

        target.chmod(0755);
        return target;
    }

    static int runBashScript(Launcher launcher,
                              TaskListener listener,
                              FilePath workspace,
                              EnvVars env,
                              String resourceName,
                              List<String> args,
                              String title) throws IOException, InterruptedException {
        FilePath script = materializeScript(workspace, resourceName);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.getRemote());
        command.addAll(args);

        CavLogger.debug(listener, "========== " + title + " ==========");
        CavLogger.debug(listener, "Script: " + script.getRemote());
        CavLogger.debug(listener, "Args  : " + String.join(" ", args));
        CavLogger.debug(listener, "================================");

        return launcher.launch()
                .cmds(command)
                .envs(env)
                .pwd(workspace)
                .stdout(listener)
                .quiet(true)
                .join();
    }

    static int runPythonConverter(Launcher launcher,
                                   TaskListener listener,
                                   FilePath workspace,
                                   EnvVars env,
                                   String jsonFileRemotePath,
                                   String htmlFileRemotePath) throws IOException, InterruptedException {
        FilePath script = materializeScript(workspace, "trivy_json_to_html.py");

        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(script.getRemote());
        command.add(jsonFileRemotePath);
        command.add(htmlFileRemotePath);

        CavLogger.debug(listener, "========== Convert Trivy JSON To HTML ==========");
        CavLogger.debug(listener, "Converter : " + script.getRemote());
        CavLogger.debug(listener, "Input     : " + jsonFileRemotePath);
        CavLogger.debug(listener, "Output    : " + htmlFileRemotePath);
        CavLogger.debug(listener, "===============================================");

        return launcher.launch()
                .cmds(command)
                .envs(env)
                .pwd(workspace)
                .quiet(true)
                .stdout(listener)
                .join();
    }
}
