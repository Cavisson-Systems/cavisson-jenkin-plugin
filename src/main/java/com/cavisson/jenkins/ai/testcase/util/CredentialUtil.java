package com.cavisson.jenkins.ai.testcase.util;

import com.cavisson.jenkins.connection.CavServiceConnection;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.model.Item;
import hudson.security.ACL;

import java.util.Collections;
import java.util.List;

/**
 * Utility class that looks up a {@link CavServiceConnection} from the Jenkins
 * global credentials store by its credential ID.
 *
 * <p>Uses the standard Jenkins {@link CredentialsProvider} API so that the
 * credential lookup respects Jenkins security realms and folder-scoped credentials.
 */
public final class CredentialUtil {

    private CredentialUtil() { /* utility class */ }

    /**
     * Finds the {@link CavServiceConnection} registered under the given ID.
     *
     * @param credentialId the ID entered in the {@code cavServiceConnectionId} pipeline parameter
     * @param context      the Jenkins {@link Item} (job) context for scoped credentials;
     *                     may be {@code null} for globally-scoped lookups
     * @return the matching credential
     * @throws IllegalArgumentException if no credential with that ID is found
     */
    public static CavServiceConnection findById(String credentialId, Item context) {
        List<CavServiceConnection> all = CredentialsProvider.lookupCredentials(
                CavServiceConnection.class,
                context,
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList());

        CavServiceConnection credential =
                CredentialsMatchers.firstOrNull(all,
                        CredentialsMatchers.withId(credentialId));

        if (credential == null) {
            throw new IllegalArgumentException(
                    "No Cav AI Service Connection credential found with ID: '"
                    + credentialId + "'. "
                    + "Please add the credential under Manage Jenkins → Credentials.");
        }
        return credential;
    }
}
