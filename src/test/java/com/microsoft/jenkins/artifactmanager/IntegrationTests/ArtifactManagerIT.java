package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.jenkins.artifactmanager.AzureArtifactManager;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.ArtifactArchiver;
import jenkins.model.ArtifactManager;
import jenkins.model.StandardArtifactManager;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

public class ArtifactManagerIT extends IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(ArtifactManagerIT.class.getName());
    private String containerName;

    @Before
    public void setUp() {
        disableAI();

        containerName = "testArtifacts" + TestEnvironment.GenerateRandomString(15);
        try {
            testEnv = new TestEnvironment(containerName);
            File directory = new File(containerName);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            testEnv.account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(testEnv.azureStorageAccountName, testEnv.azureStorageAccountKey1));
            testEnv.blobClient = testEnv.account.createCloudBlobClient();
            testEnv.container = testEnv.blobClient.getContainerReference(containerName);
            testEnv.container.createIfNotExists();
            for (int i = 0; i < TestEnvironment.TOTAL_FILES; i++) {
                String tempContent = UUID.randomUUID().toString();
                File temp = new File(directory.getAbsolutePath(), "upload" + tempContent + ".txt");
                FileUtils.writeStringToFile(temp, tempContent);
                testEnv.uploadFileList.put(tempContent, temp);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    public void testArchive() {
        try {
            Run mockRun = mock(Run.class);
            Launcher mockLauncher = mock(Launcher.class);

            File workspaceDir = new File(containerName);
            FilePath workspace = new FilePath(mockLauncher.getChannel(), workspaceDir.getAbsolutePath());

            ArtifactManager artifactManager = new StandardArtifactManager(mockRun);
            artifactManager.archive(workspace, mockLauncher, null, null);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }
}
