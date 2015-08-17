package org.jfrog.bamboo.bintray;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.jfrog.bamboo.admin.ServerConfig;
import org.jfrog.bamboo.bintray.client.JfClient;
import org.jfrog.bamboo.bintray.client.MavenCentralSyncModel;
import org.jfrog.bamboo.util.BambooBuildInfoLog;
import org.jfrog.build.api.release.BintrayUploadInfoOverride;
import org.jfrog.build.client.ArtifactoryVersion;
import org.jfrog.build.client.bintrayResponse.BintrayResponse;
import org.jfrog.build.client.bintrayResponse.BintraySuccess;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;

import java.util.List;


/**
 * Push to Bintray Runnable to pass in to a Thread that will preform this task on Bamboo
 *
 * @author Aviad Shikloshi
 */
public class PushToBintrayRunnable implements Runnable {

    private static final String MINIMAL_SUPPORTED_VERSION = "3.6";
    private Logger log = Logger.getLogger(PushToBintrayRunnable.class);

    private JfClient jfClient;
    private PushToBintrayAction action;
    private ServerConfig serverConfig;

    public PushToBintrayRunnable(PushToBintrayAction pushToBintrayAction, ServerConfig serverConfig, JfClient jfClient) {
        this.action = pushToBintrayAction;
        this.jfClient = jfClient;
        this.serverConfig = serverConfig;
    }

    /**
     * Run method to perform Push to Bintray action
     * This method sets the isSuccessfullyDone in the action object to use later in the action view
     */
    @Override
    public void run() {
        ArtifactoryBuildInfoClient artifactoryClient = null;
        try {
            logMessage("Starting Push to Bintray action.");
            PushToBintrayAction.context.getLock().lock();
            PushToBintrayAction.context.setDone(false);
            artifactoryClient = getArtifactoryBuildInfoClient(serverConfig);
            if (!isValidArtifactoryVersion(artifactoryClient)) {
                logError("Push to Bintray supported from Artifactory version " + MINIMAL_SUPPORTED_VERSION);
                PushToBintrayAction.context.setDone(true);
                return;
            }
            boolean successfulPush = performPushToBintray(artifactoryClient);
            if (successfulPush && action.isMavenSync()) {
                mavenCentralSync();
            }
        } catch (Exception e) {
            log.error("Error while trying to Push build to Bintray: " + e.getMessage());
        } finally {
            if (artifactoryClient != null) {
                artifactoryClient.shutdown();
            }
            PushToBintrayAction.context.setDone(true);
            PushToBintrayAction.context.getLock().unlock();
        }
    }

    /**
     * Create the relevant objects from input and send it to build info artifactoryClient that will preform the actual push
     * Set the result of the action to true if successful to use in the action view.
     */
    private boolean performPushToBintray(ArtifactoryBuildInfoClient artifactoryClient) {

        String buildName = PushToBintrayAction.context.getBuildKey();
        String buildNumber = Integer.toString(PushToBintrayAction.context.getBuildNumber());

        String subject = action.getSubject(),
                repoName = action.getRepository(),
                packageName = action.getPackageName(),
                versionName = action.getVersion(),
                vcsUrl = action.getVcsUrl(),
                signMethod = action.getSignMethod(),
                passphrase = action.getGpgPassphrase();

        List<String> licenses = createLicensesListFromString(action.getLicenses());

        BintrayUploadInfoOverride uploadInfoOverride = new BintrayUploadInfoOverride(subject, repoName, packageName,
                versionName, licenses, vcsUrl);

        try {
            BintrayResponse response =
                    artifactoryClient.pushToBintray(buildName, buildNumber, signMethod, passphrase, uploadInfoOverride);
            logMessage(response.toString());
            return response instanceof BintraySuccess; // todo: fix this
        } catch (Exception e) {
            logError("Push to Bintray Failed with Exception: ", e);
        }
        return false;
    }

    /**
     * Trigger's Bintray MavenCentralSync API
     */
    private void mavenCentralSync() {
        try {
            logMessage("Syncing build with Nexus.");
            String response = jfClient.mavenCentralSync(new MavenCentralSyncModel(serverConfig.getNexusUsername(), serverConfig.getNexusPassword(), "1"),
                    action.getSubject(), action.getRepository(), action.getPackageName(), action.getVersion());
            logMessage(response);
        } catch (Exception e) {
            logError("Error while trying to sync with Maven Central", e);
        }
    }

    private boolean isValidArtifactoryVersion(ArtifactoryBuildInfoClient client) {
        boolean validVersion = false;
        try {
            ArtifactoryVersion version = client.verifyCompatibleArtifactoryVersion();
            validVersion = version.isAtLeast(new ArtifactoryVersion(MINIMAL_SUPPORTED_VERSION));
        } catch (Exception e) {
            logError("Error while checking Artifactory version", e);
        }
        return validVersion;
    }

    private ArtifactoryBuildInfoClient getArtifactoryBuildInfoClient(ServerConfig serverConfig) {
        String username = serverConfig.getUsername();
        String password = serverConfig.getPassword();
        String artifactoryUrl = serverConfig.getUrl();
        return new ArtifactoryBuildInfoClient(artifactoryUrl, username, password, new BambooBuildInfoLog(log));
    }

    private List<String> createLicensesListFromString(String licenses) {
        String[] licensesArray = StringUtils.split(licenses, ",");
        for (int i = 0; i < licensesArray.length; i++) {
            licensesArray[i] = licensesArray[i].trim();
        }
        return Lists.newArrayList(licensesArray);
    }

    private void logError(String message, Exception e) {
        if (e != null) {
            message += " " + e.getMessage() + " <br>";
        }
        logError(message);
    }

    private void logError(String message) {
        log.error(message);
        PushToBintrayAction.context.getLog().add(message);
    }

    private void logMessage(String message) {
        log.info(message);
        PushToBintrayAction.context.getLog().add(message);
    }

}