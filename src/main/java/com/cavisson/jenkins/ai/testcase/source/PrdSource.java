package com.cavisson.jenkins.ai.testcase.source;

import com.cavisson.jenkins.log.CavLogger;
import hudson.FilePath;
import hudson.model.Run;

import java.io.IOException;

/**
 * Contract for all PRD acquisition strategies.
 *
 * Implementations:
 *   LocalFileSource  - reads a file uploaded via Jenkins Build with Parameters
 *   GitSource        - clones a Git repository and locates the PRD file
 *
 * Once acquire() returns a FilePath, the caller passes it directly to
 * the existing upload REST API. The acquisition strategy is completely
 * transparent to the rest of the plugin.
 */
public interface PrdSource {

    /**
     * Acquires the PRD file and returns its location in the Jenkins workspace.
     *
     * @param run        current Jenkins build
     * @param workspace  Jenkins workspace FilePath
     * @param log        shared build logger
     * @return           FilePath pointing to the PRD file ready for upload
     * @throws IOException          if the file cannot be located or copied
     * @throws InterruptedException if the build is aborted
     */
    FilePath acquire(Run<?, ?> run,
                     FilePath  workspace,
                     CavLogger log)
            throws IOException, InterruptedException;
}
