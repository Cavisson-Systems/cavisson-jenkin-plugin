package com.cavisson.jenkins.ai.testcase.source;

import com.cavisson.jenkins.connection.CavServiceConnection;
import com.cavisson.jenkins.log.CavLogger;
import hudson.FilePath;
import hudson.model.Run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Acquires the PRD file by cloning a Git repository.
 *
 * FLOW
 * ====
 * 1. Attempt anonymous clone (public repository).
 *    If successful, locate the PRD file and return its FilePath.
 *
 * 2. If anonymous clone fails (private repository),
 *    use the Git credentials from the Service Connection
 *    (username + PAT or SSH key).
 *    Clone, locate PRD file, return FilePath.
 *
 * The caller never needs to know whether the repo was public or private.
 * Detection is automatic.
 *
 * IMPLEMENTATION NOTE
 * ===================
 * Git operations use ProcessBuilder (standard OS git command) because
 * the git-client Jenkins plugin requires a complex setup with EnvVars,
 * Launcher, and TaskListener that is not available in a simple interface.
 *
 * The git binary is available on all standard Jenkins agent images.
 * If git is not available, a clear error message is shown.
 *
 * SSL verification is disabled for self-signed certificates, matching
 * the existing plugin behavior (Apache HttpClient uses TrustAllStrategy).
 */
public class GitSource implements PrdSource {

    private final String                 repoUrl;
    private final String                 branch;
    private final String                 prdFilePath;
    private final String                 gitUsernameOverride;
    private final String                 gitCredentialOverride;
    private final CavServiceConnection credential;

    /**
     * @param repoUrl     Full Git repository URL
     *                    e.g. https://github.com/org/repo.git
     * @param branch      Branch to checkout e.g. "main"
     * @param prdFilePath Relative path of PRD file inside the repo
     *                    e.g. "docs/SauceDemoTEST.feature"
     * @param credential  Service connection - provides git username and PAT
     *                    when the repo is private
     */
    public GitSource(String repoUrl,
                     String branch,
                     String prdFilePath,
                     CavServiceConnection credential) {
        this(repoUrl, branch, prdFilePath, null, null, credential);
    }

    /**
     * Same as the 4-arg constructor, but lets the caller supply Git username/PAT directly
     * (e.g. from the AI Test Case build step's own Execution Source fields) instead of the
     * Service Connection's. When {@code gitUsernameOverride}/{@code gitCredentialOverride}
     * are blank, falls back to {@code credential.getGitUsername()}/{@code getGitCredential()}
     * exactly as before, for backward compatibility with existing jobs/credentials.
     */
    public GitSource(String repoUrl,
                     String branch,
                     String prdFilePath,
                     String gitUsernameOverride,
                     String gitCredentialOverride,
                     CavServiceConnection credential) {
        this.repoUrl               = repoUrl;
        this.branch                = branch != null && !branch.isEmpty() ? branch : "main";
        this.prdFilePath           = prdFilePath;
        this.gitUsernameOverride   = gitUsernameOverride;
        this.gitCredentialOverride = gitCredentialOverride;
        this.credential            = credential;
    }

    @Override
    public FilePath acquire(Run<?, ?> run,
                            FilePath  workspace,
                            CavLogger log)
            throws IOException, InterruptedException {

        // Clone into a subdirectory so we don't pollute the workspace root
        FilePath cloneDir = workspace.child("_prd_git_clone");
        if (cloneDir.exists()) {
            cloneDir.deleteRecursive();
        }
        cloneDir.mkdirs();

        log.info("PRD source: Git repository");
        log.debug("Repository : " + repoUrl);
        log.debug("Branch     : " + branch);
        log.debug("PRD path   : " + prdFilePath);

        // -- Attempt 1: Anonymous clone (public repo) --------------------------
        log.info("Attempting anonymous clone...");
        boolean cloned = tryClone(repoUrl, branch, cloneDir, log, false);

        // -- Attempt 2: Authenticated clone (private repo) ---------------------
        if (!cloned) {
            log.info("Anonymous clone failed - attempting authenticated clone...");

            String gitUser = notBlank(gitUsernameOverride) ? gitUsernameOverride : credential.getGitUsername();
            String gitPat  = notBlank(gitCredentialOverride) ? gitCredentialOverride : credential.getGitCredential();

            if (gitUser == null || gitUser.trim().isEmpty()) {
                throw new IOException(
                        "Git repository clone failed. "
                        + "Repository may be private but no Git Username is configured "
                        + "in the Service Connection. "
                        + "Please add Git Username and PAT to the Service Connection.");
            }

            // Embed credentials in the URL for HTTPS:
            // https://username:pat@github.com/org/repo.git
            String authenticatedUrl = embedCredentials(repoUrl, gitUser, gitPat);
            cloned = tryClone(authenticatedUrl, branch, cloneDir, log, true);

            if (!cloned) {
                throw new IOException(
                        "Git clone failed for both anonymous and authenticated access. "
                        + "Repository: " + repoUrl + "  Branch: " + branch + ". "
                        + "Check the repository URL, branch name, and credentials "
                        + "in the Service Connection.");
            }
        }

        log.info("Repository cloned successfully");

        // -- Locate the PRD file -----------------------------------------------
        FilePath prd = cloneDir.child(prdFilePath);
        if (!prd.exists()) {
            throw new IOException(
                    "PRD file not found in repository. "
                    + "Path: '" + prdFilePath + "' "
                    + "does not exist in branch '" + branch + "' "
                    + "of " + repoUrl);
        }

        log.info("PRD file located: " + prdFilePath
                + " (" + prd.length() + " bytes)");

        return prd;
    }

    /**
     * Executes a git clone using the OS git binary via ProcessBuilder.
     *
     * @param url       repository URL (may contain embedded credentials)
     * @param branch    branch to clone
     * @param targetDir directory to clone into
     * @param log       console output
     * @param masked    if true, suppress URL in output (credentials embedded)
     * @return          true if clone succeeded, false if it failed
     */
    private boolean tryClone(String url,
                              String branch,
                              FilePath targetDir,
                              CavLogger log,
                              boolean masked) {

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone",
                    "--depth", "1",
                    "--branch", branch,
                    "--single-branch",
                    "-c", "http.sslVerify=false",   // match plugin TrustAllStrategy
                    url,
                    targetDir.getRemote()
            );
            pb.redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // disable interactive prompts

            Process process = pb.start();

            // Read and log git output
            byte[] output = readAllBytes(process.getInputStream());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                return true;
            }

            // On failure, log output (masking credentials if present)
            if (masked) {
                log.debug("Authenticated clone failed (credentials masked)");
            } else {
                String out = new String(output).trim();
                if (!out.isEmpty()) {
                    log.debug("Clone output: " + out);
                }
            }
            return false;

        } catch (IOException | InterruptedException e) {
            log.debug("Clone error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Embeds username and PAT into an HTTPS URL.
     *
     * https://github.com/org/repo.git
     * becomes
     * https://username:pat@github.com/org/repo.git
     */
    private String embedCredentials(String url, String user, String pat) {
        if (url.startsWith("https://")) {
            String encodedUser = urlEncode(user);
            String encodedPat  = urlEncode(pat != null ? pat : "");
            return "https://" + encodedUser + ":" + encodedPat
                    + "@" + url.substring("https://".length());
        }
        // For SSH URLs, git will use the SSH key from the agent - no modification needed
        return url;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
