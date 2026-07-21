package com.cavisson.jenkins.ai.testcase.source;

import com.cavisson.jenkins.log.CavLogger;
import hudson.FilePath;
import hudson.model.FileParameterValue;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * Acquires the PRD file from a browser upload or workspace file.
 *
 * =====================================================================
 * WHY parameters { file(...) } DOES NOT WORK IN PIPELINE
 * =====================================================================
 *
 * In a Jenkins FREESTYLE job, FileParameterValue.createBuildWrapper()
 * is called during build setup, which copies the uploaded file from the
 * HTTP request into {workspace}/{parameterName}. This is why it works
 * in freestyle jobs.
 *
 * In a Jenkins PIPELINE job, BuildWrapper is NEVER invoked. The pipeline
 * engine does not call createBuildWrapper() on parameter values. The
 * file is stored by Jenkins in:
 *
 *   {jenkins_home}/jobs/{job}/builds/{n}/fileParameters/{parameterName}
 *
 * but it is NEVER automatically copied to the workspace. This is a
 * known Jenkins pipeline limitation, not a bug in this plugin.
 *
 * Therefore fileExists("${WORKSPACE}/PRD_FILE_UPLOAD") always returns
 * false when called from a Declarative Pipeline.
 *
 * =====================================================================
 * WHERE JENKINS STORES THE UPLOADED FILE
 * =====================================================================
 *
 * Jenkins stores the uploaded file inside FileParameterValue.file which
 * is an org.apache.commons.fileupload.FileItem. This object holds the
 * file bytes either in memory (small files) or in a temporary disk file.
 *
 * The FileItem is accessible via the FileParameterValue instance that
 * is stored in the build's ParametersAction. The field is named "file"
 * and is package-private, so we access it via reflection.
 *
 * =====================================================================
 * THE CORRECT SOLUTION
 * =====================================================================
 *
 * 1. Find the FileParameterValue in the build's ParametersAction.
 * 2. Read the file bytes from FileParameterValue.file (FileItem) via
 *    reflection using getInputStream().
 * 3. Write the bytes into the Jenkins workspace as a proper FilePath.
 * 4. Return that FilePath to the caller.
 *
 * This is the same approach used by:
 *   - Jenkins File Parameters Plugin
 *   - Jenkins Copy Artifact Plugin
 *   - Jenkins Pipeline Utility Steps Plugin
 *
 * =====================================================================
 * FALLBACK (legacy backward compatibility)
 * =====================================================================
 *
 * If no FileParameterValue is found, fall back to:
 *   1. A workspace-relative path (prdFileFallback field)
 *   2. An absolute path (prdFileFallback field)
 *
 * This keeps backward compatibility with pipelines that still use
 * the old approach of copying files manually into the workspace.
 */
public class LocalFileSource implements PrdSource {

    private final String prdParameterName;
    private final String prdFileFallback;

    public LocalFileSource(String prdParameterName, String prdFileFallback) {
        this.prdParameterName = prdParameterName != null
                ? prdParameterName : "PRD_FILE_UPLOAD";
        this.prdFileFallback  = prdFileFallback != null
                ? prdFileFallback.trim() : "";
    }

    @Override
    public FilePath acquire(Run<?, ?> run,
                            FilePath  workspace,
                            CavLogger log)
            throws IOException, InterruptedException {

        // -- Strategy 1: Read from FileParameterValue (browser upload) ---------
        //
        // Jenkins stores the uploaded file in FileParameterValue.file (FileItem).
        // We read its bytes and write them into the workspace ourselves,
        // because Pipeline never calls createBuildWrapper() to do this for us.

        ParametersAction paramsAction = run.getAction(ParametersAction.class);
        if (paramsAction != null) {
            for (ParameterValue pv : paramsAction.getParameters()) {
                if (!pv.getName().equals(prdParameterName)) continue;
                if (!(pv instanceof FileParameterValue)) continue;

                FileParameterValue fpv = (FileParameterValue) pv;

                // Get the original filename the user selected on their computer.
                // e.g. "SauceDemoTEST.feature" or "MyPRD.docx"
                String originalName = getOriginalFileName(fpv);
                if (originalName == null || originalName.trim().isEmpty()) {
                    log.warn("FileParameterValue found but original filename is empty. "
                            + "The user may not have selected a file.");
                    break;
                }

                // Read the file bytes from the FileItem stored inside
                // FileParameterValue. The field is named "file" and holds a
                // org.apache.commons.fileupload.FileItem instance.
                byte[] fileBytes = readFileBytes(fpv);
                if (fileBytes == null || fileBytes.length == 0) {
                    log.warn("FileParameterValue found but file is empty. "
                            + "The user selected an empty file.");
                    break;
                }

                // Write the file into the Jenkins workspace.
                // We use the original filename so the Cavisson server receives
                // a file with the correct name (e.g. SauceDemoTEST.feature),
                // not the parameter name (PRD_FILE_UPLOAD).
                FilePath destination = workspace.child(originalName);
                destination.copyFrom(
                        new java.io.ByteArrayInputStream(fileBytes));

                log.info("PRD source: Local upload - " + originalName);
                log.debug("Written to workspace: " + destination.getRemote()
                        + "  Size: " + fileBytes.length + " bytes");

                return destination;
            }
        }

        // -- Strategy 2: Workspace already contains the file at parameterName --
        //
        // Some Jenkins configurations DO copy the file to the workspace
        // (e.g. when using a Freestyle project with pipeline, or when
        // using certain Jenkins versions that handle this differently).
        // Check for this as a secondary fallback.

        FilePath wsCopy = workspace.child(prdParameterName);
        if (wsCopy.exists() && wsCopy.length() > 0) {
            log.info("PRD source: Workspace copy at " + prdParameterName);
            return wsCopy;
        }

        // -- Strategy 3: Build archive directory -------------------------------
        //
        // Jenkins stores the FileParameterValue backup in the build archive:
        //   {build_root_dir}/fileParameters/{parameterName}
        // This is a last resort in case neither Strategy 1 nor 2 worked.

        File buildFileParam = new File(
                run.getRootDir(), "fileParameters/" + prdParameterName);
        if (buildFileParam.exists() && buildFileParam.length() > 0) {
            // Copy from build archive into workspace
            FilePath archived  = new FilePath(buildFileParam);
            FilePath wsTarget  = workspace.child(prdParameterName);
            archived.copyTo(wsTarget);
            log.info("PRD source: Build archive copy - " + prdParameterName);
            log.debug("Copied from: " + buildFileParam.getAbsolutePath());
            return wsTarget;
        }

        // -- Strategy 4: Legacy workspace path (prdFile fallback) -------------

        if (!prdFileFallback.isEmpty()) {
            FilePath candidate = workspace.child(prdFileFallback);
            if (candidate.exists()) {
                log.info("PRD source: Workspace file - " + prdFileFallback);
                return candidate;
            }
            FilePath abs = new FilePath(new File(prdFileFallback));
            if (abs.exists()) {
                log.info("PRD source: Absolute path - " + prdFileFallback);
                return abs;
            }
        }

        // -- Nothing found -----------------------------------------------------

        throw new IOException(
                "No PRD file found. "
                + "Please use Build with Parameters and select a file "
                + "using the Choose File button. "
                + "Parameter name: '" + prdParameterName + "'. "
                + "If the file was uploaded, verify the pipeline script declares: "
                + "file(name: '" + prdParameterName + "', ...)");
    }

    // -- Read file bytes from FileParameterValue -------------------------------

    /**
     * Reads the file bytes from the FileItem stored inside FileParameterValue.
     *
     * FileParameterValue has a package-private field named "file" of type
     * org.apache.commons.fileupload.FileItem. We access it via reflection
     * because there is no public API to read the bytes directly.
     *
     * This is the standard approach used by the Jenkins File Parameters Plugin.
     */
    private byte[] readFileBytes(FileParameterValue fpv) {
        // Try via the FileItem field (most reliable)
        try {
            Field fileField = FileParameterValue.class.getDeclaredField("file");
            fileField.setAccessible(true);
            Object fileItem = fileField.get(fpv);
            if (fileItem != null) {
                // Call get() on the FileItem to get the byte array
                java.lang.reflect.Method getMethod =
                        fileItem.getClass().getMethod("get");
                byte[] bytes = (byte[]) getMethod.invoke(fileItem);
                if (bytes != null && bytes.length > 0) return bytes;

                // Try getInputStream() if get() returns empty
                java.lang.reflect.Method streamMethod =
                        fileItem.getClass().getMethod("getInputStream");
                try (InputStream is = (InputStream) streamMethod.invoke(fileItem)) {
                    return readAllBytes(is);
                }
            }
        } catch (Exception e) {
            // Reflection failed - try the next approach
        }

        // Try via the FileParameterValue.getFile() method if it exists
        try {
            java.lang.reflect.Method getFile =
                    FileParameterValue.class.getMethod("getFile");
            Object file = getFile.invoke(fpv);
            if (file instanceof File) {
                java.nio.file.Path path = ((File) file).toPath();
                if (java.nio.file.Files.exists(path)) {
                    return java.nio.file.Files.readAllBytes(path);
                }
            }
        } catch (Exception e) {
            // Not available in this Jenkins version
        }

        return null;
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

    /**
     * Gets the original filename from FileParameterValue.
     * Tries the public API first, then falls back to reflection.
     */
    private String getOriginalFileName(FileParameterValue fpv) {
        // Public API (available in Jenkins 2.332+)
        try {
            String name = fpv.getOriginalFileName();
            if (name != null && !name.trim().isEmpty()) return name;
        } catch (Exception e) {
            // Not available
        }

        // Reflection fallback - field is named "originalFileName" or "filename"
        for (String fieldName : new String[]{"originalFileName", "filename", "name"}) {
            try {
                Field f = findField(FileParameterValue.class, fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(fpv);
                    if (val instanceof String && !((String) val).trim().isEmpty()) {
                        return (String) val;
                    }
                }
            } catch (Exception e) {
                // Try next field
            }
        }

        // Last resort: use parameter value as the name
        Object val = fpv.getValue();
        return val != null ? val.toString() : null;
    }

    private Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
