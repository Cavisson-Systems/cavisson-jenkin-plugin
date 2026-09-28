package com.cavisson.jenkins.config;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Plugin-wide settings shown under Manage Jenkins -> System -> "Cavisson CICD".
 *
 * {@code allowInsecureSSL} defaults to off: TLS certificates and hostnames are verified on every
 * call to a Cavisson server. Administrators whose servers use self-signed certificates can turn
 * it on explicitly (preferably, import the certificate into the controller/agent JVM truststore
 * instead).
 */
@Extension
@Symbol("cavissonCicd")
public class CavissonGlobalConfiguration extends GlobalConfiguration {

    private boolean allowInsecureSSL;

    public CavissonGlobalConfiguration() {
        load();
    }

    public static CavissonGlobalConfiguration get() {
        return GlobalConfiguration.all().get(CavissonGlobalConfiguration.class);
    }

    /**
     * Whether trust-all SSL may be applied. Returns {@code false} whenever Jenkins isn't running
     * (plain unit tests) or the configuration can't be found.
     */
    public static boolean insecureSslAllowed() {
        try {
            CavissonGlobalConfiguration config = get();
            return config != null && config.isAllowInsecureSSL();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public boolean isAllowInsecureSSL() {
        return allowInsecureSSL;
    }

    @DataBoundSetter
    public void setAllowInsecureSSL(boolean allowInsecureSSL) {
        this.allowInsecureSSL = allowInsecureSSL;
        save();
    }
}
