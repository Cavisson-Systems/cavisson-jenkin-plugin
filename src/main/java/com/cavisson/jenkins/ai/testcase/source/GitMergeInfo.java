package com.cavisson.jenkins.ai.testcase.source;

import com.cavisson.jenkins.log.CavLogger;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merge ID and Epic ID extracted from the latest merge commit in the job workspace.
 *
 * Expected GitLab merge commit message shape:
 * <pre>
 * Merge branch 'feature/x' into 'Develop'
 *
 * B-NA|A-Author | R - Reviewer | M-Message | EM-527
 *
 * See merge request group/project!28
 * </pre>
 * gives mergeId = "28", epicId = "EM-527". GitHub's "Merge pull request #NN" is also accepted.
 *
 * Reading is best-effort: a workspace that is not a git repo, or has no merge commits,
 * yields an empty result rather than failing the build.
 */
public final class GitMergeInfo {

    private static final Pattern GITLAB_MR   = Pattern.compile("See merge request \\S+!(\\d+)");
    private static final Pattern GITHUB_PR   = Pattern.compile("Merge pull request #(\\d+)");
    // Tolerates spaces around the dash ("DT -753", "DT - 753"); normalised to "DT-753".
    private static final Pattern EPIC_KEY    = Pattern.compile("\\b([A-Z][A-Z0-9]+)\\s*-\\s*(\\d+)\\b");

    private static final GitMergeInfo EMPTY = new GitMergeInfo("", "", "");

    private final String mergeId;
    private final String epicId;
    private final String message;

    private GitMergeInfo(String mergeId, String epicId, String message) {
        this.mergeId = mergeId;
        this.epicId  = epicId;
        this.message = message;
    }

    public String getMergeId() { return mergeId; }
    public String getEpicId()  { return epicId; }
    public String getMessage() { return message; }

    /** Runs {@code git log --merges -1 --format=%B} in the workspace and parses the result. */
    public static GitMergeInfo fetchLatest(FilePath workspace, Launcher launcher,
                                           EnvVars env, CavLogger log) {
        try {
            if (workspace == null || !workspace.exists()) {
                log.info("Could not read latest merge commit: workspace not available.");
                return EMPTY;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int rc = launcher.launch()
                    .cmds("git", "log", "--merges", "-1", "--format=%B")
                    .pwd(workspace)
                    .envs(env)
                    .stdout(out)
                    .stderr(new ByteArrayOutputStream())
                    .quiet(true)
                    .join();
            String text = out.toString(StandardCharsets.UTF_8.name()).trim();
            if (rc != 0 || text.isEmpty()) {
                log.info("Could not read latest merge commit (git exit code " + rc
                        + ", or no merge commits in workspace).");
                return EMPTY;
            }
            log.debug("Latest merge commit message:\n" + text);
            return parse(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Could not read latest merge commit: interrupted.");
            return EMPTY;
        } catch (Exception e) {
            log.info("Could not read latest merge commit: " + e.getMessage());
            return EMPTY;
        }
    }

    /** Parses a merge commit message; package-visible logic is pure for unit testing. */
    public static GitMergeInfo parse(String message) {
        if (message == null || message.trim().isEmpty()) return EMPTY;

        String mergeId = "";
        Matcher m = GITLAB_MR.matcher(message);
        if (m.find()) {
            mergeId = m.group(1);
        } else {
            m = GITHUB_PR.matcher(message);
            if (m.find()) mergeId = m.group(1);
        }

        // Search for the epic key everywhere except the "See merge request" trailer,
        // whose project path could otherwise produce a false match.
        StringBuilder body = new StringBuilder();
        for (String line : message.split("\\r?\\n")) {
            if (!line.trim().startsWith("See merge request")) body.append(line).append('\n');
        }
        // Prefer the "| EPIC-ID" segments (last first) so a key inside a branch name
        // ("from user/feature/AB-1 | DT-753") doesn't win; fall back to anywhere in the message.
        String epicId = "";
        String[] segments = body.toString().split("\\|");
        for (int i = segments.length - 1; i >= 1 && epicId.isEmpty(); i--) {
            epicId = findEpic(segments[i]);
        }
        if (epicId.isEmpty()) epicId = findEpic(body);

        return new GitMergeInfo(mergeId, epicId, message.trim());
    }

    private static String findEpic(CharSequence text) {
        Matcher e = EPIC_KEY.matcher(text);
        return e.find() ? e.group(1) + "-" + e.group(2) : "";
    }
}
