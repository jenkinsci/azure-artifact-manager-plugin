package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.jenkins.artifactmanager.AzureArtifactConfig;
import com.microsoft.jenkins.artifactmanager.AzureArtifactManager;
import com.microsoft.jenkins.artifactmanager.Constants;
import com.microsoft.jenkins.artifactmanager.Utils;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.ArtifactManager;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Jenkins.class, Utils.class})
@PowerMockIgnore({"javax.management.*", "javax.crypto.*"})
public class ArtifactManagerIT extends IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(ArtifactManagerIT.class.getName());
    private String containerName;
    private AzureArtifactConfig config;
    private FreeStyleProject freeStyleProject;
    private Run run;
    private File workspaceDir;
    private Launcher mockLauncher;
    private FilePath workspace;

    private static final String CHILD = "artifact";
    private static final String FILE_EXTENSION = ".txt";
    private static final String PREFIX = "prefix/";
    private static final String JOB_NAME = "job";
    private static final Integer BUILD_NUMBER = 1;
    private static final String STASH_NAME = "stash";

    @Rule
    JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        disableAI();
        freeStyleProject = mock(FreeStyleProject.class);
        when(freeStyleProject.getFullName()).thenReturn(JOB_NAME);

        containerName = "testartifacts" + TestEnvironment.GenerateRandomString(15);

        workspaceDir = new File(containerName);
        mockLauncher = mock(Launcher.class);
        workspace = new FilePath(mockLauncher.getChannel(), workspaceDir.getAbsolutePath());

        config = new AzureArtifactConfig();
        config.setContainer(containerName);
        config.setPrefix(PREFIX);

        run = mock(Run.class);
        when(run.getArtifactsDir()).thenReturn(new File(workspaceDir, "archive"));
        when(run.getNumber()).thenReturn(BUILD_NUMBER);
        when(run.getParent()).thenReturn(freeStyleProject);

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
                File temp = new File(directory.getAbsolutePath(), CHILD + tempContent + FILE_EXTENSION);
                FileUtils.writeStringToFile(temp, tempContent);
                testEnv.uploadFileList.put(tempContent, temp);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }

        StorageAccountInfo accountInfo = new StorageAccountInfo(testEnv.azureStorageAccountName, testEnv.azureStorageAccountKey1, null);
        PowerMockito.mockStatic(Utils.class);
        when(Utils.getStorageAccount(any())).thenReturn(accountInfo);
    }

    @After
    public void cleanUp() {
        try {
            workspace.deleteRecursive();
            testEnv.container.deleteIfExists();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void TestStash() {
        TaskListener taskListener = mock(TaskListener.class);
        PrintStream printStream = mock(PrintStream.class);
        when(taskListener.getLogger()).thenReturn(printStream);

        AzureArtifactManager artifactManager = new AzureArtifactManager(run, config);
        EnvVars envVars = mock(EnvVars.class);
        try {
            artifactManager.stash(STASH_NAME, workspace, mockLauncher, envVars, taskListener, "*", null, true, false);
            for (ListBlobItem blobItem : testEnv.container.listBlobs(String.format("%s%s/%d/%s", PREFIX, JOB_NAME, BUILD_NUMBER, Constants.STASHES_PATH))) {
                if (blobItem instanceof CloudBlockBlob) {
                    CloudBlockBlob blockBlob = (CloudBlockBlob) blobItem;
                    String name = blockBlob.getName();
                    assertTrue(name.endsWith(STASH_NAME + Constants.TGZ_FILE_EXTENSION));
                }
            }

            workspace.deleteRecursive();
            artifactManager.unstash(STASH_NAME, workspace, mockLauncher, envVars, taskListener);
            assertEquals(TestEnvironment.TOTAL_FILES, workspace.list().size());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    public void testArchive() {
        try {
            PrintStream printStream = mock(PrintStream.class);

            Map<String, String> artifacts = new HashMap<>();
            for (File file : testEnv.uploadFileList.values()) {
                artifacts.put(file.getName(), file.getName());
            }

            BuildListener mockListener = mock(BuildListener.class);
            when(mockListener.getLogger()).thenReturn(printStream);

            ArtifactManager artifactManager = new AzureArtifactManager(run, config);
            artifactManager.archive(workspace, mockLauncher, mockListener, artifacts);

            for (ListBlobItem blobItem : testEnv.container.listBlobs(String.format("%s%s/%d/%s", PREFIX, JOB_NAME, BUILD_NUMBER, Constants.ARTIFACTS_PATH))) {
                if (blobItem instanceof CloudBlockBlob) {
                    CloudBlockBlob downloadedBlob = (CloudBlockBlob) blobItem;
                    String downloadedContent = downloadedBlob.downloadText("utf-8", null, null, null);
                    File temp = testEnv.uploadFileList.get(downloadedContent);
                    String tempContent = FileUtils.readFileToString(temp, "utf-8");
                    //check for filenames
                    assertEquals(tempContent, downloadedContent);
                    //check for file contents
                    assertEquals(CHILD + downloadedContent + FILE_EXTENSION, temp.getName());
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }
}
