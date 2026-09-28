package com.cavisson.jenkins.ai.testcase.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitMergeInfoTest {

    @Test
    void parsesGitLabMergeIdAndEpic() {
        GitMergeInfo info = GitMergeInfo.parse(
                "Merge branch 'feature/x' into 'Develop'\n\n"
                + "B-NA|A-Pradyumna | R - Amit Sharma | M-Some change | EM-527\n\n"
                + "See merge request build.cavisson/cavisson-jenkin-plugin!28");
        assertEquals("28", info.getMergeId());
        assertEquals("EM-527", info.getEpicId());
    }

    @Test
    void noEpicInMessage() {
        GitMergeInfo info = GitMergeInfo.parse(
                "Merge branch 'J_Plugin' into 'Develop'\n\n"
                + "A-Anjali|R-Anjali|B-0|M-installing JQ within plugin\n\n"
                + "See merge request build.cavisson/cavisson-jenkin-plugin!27");
        assertEquals("27", info.getMergeId());
        assertEquals("", info.getEpicId());
    }

    @Test
    void githubPullRequestAndNoTrailer() {
        GitMergeInfo info = GitMergeInfo.parse("Merge pull request #14 from org/feat\n\nFix | ABC-9");
        assertEquals("14", info.getMergeId());
        assertEquals("ABC-9", info.getEpicId());
    }

    @Test
    void emptyMessage() {
        GitMergeInfo info = GitMergeInfo.parse("");
        assertEquals("", info.getMergeId());
        assertEquals("", info.getEpicId());
    }

    @Test
    void githubMergeWithSpacedEpic() {
        GitMergeInfo info = GitMergeInfo.parse("Merge pull request #61 from Shiv0308/feature/Coupon-cart | DT -753");
        assertEquals("61", info.getMergeId());
        assertEquals("DT-753", info.getEpicId());
    }

    @Test
    void epicAfterPipeWinsOverBranchKey() {
        GitMergeInfo info = GitMergeInfo.parse("Merge pull request #5 from me/feature/AB-12 | DT-753");
        assertEquals("DT-753", info.getEpicId());
    }
}
