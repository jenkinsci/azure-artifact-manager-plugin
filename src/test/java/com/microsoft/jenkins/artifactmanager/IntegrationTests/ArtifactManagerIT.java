package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.jenkins.artifactmanager.AzureArtifactConfig;
import com.microsoft.jenkins.artifactmanager.AzureArtifactManager;
import com.microsoft.jenkins.artifactmanager.Utils;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.sun.akuma.CLibrary;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.ArtifactManager;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Jenkins.class, Utils.class})
public class ArtifactManagerIT extends IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(ArtifactManagerIT.class.getName());
    private String containerName;
    private static final String CHILD = "artifact";
    private static final String FILE_EXTENSION = ".txt";
    private static final String PREFIX = "prefix/";
    private static final String JOB_NAME = "job";
    private static final Integer BUILD_NUMBER = 1;

    @Rule
    JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        disableAI();

        containerName = "testartifacts" + TestEnvironment.GenerateRandomString(15);
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
    }

    @Test
    public void testArchive() {
        try {
            Run mockRun = mock(Run.class);
            Launcher mockLauncher = mock(Launcher.class);

            File workspaceDir = new File(containerName);
            when(mockRun.getArtifactsDir()).thenReturn(new File(workspaceDir, "archive"));
            when(mockRun.getNumber()).thenReturn(1);
            FreeStyleProject freeStyleProject = mock(FreeStyleProject.class);
            when(freeStyleProject.getFullName()).thenReturn(JOB_NAME);
            when(mockRun.getParent()).thenReturn(freeStyleProject);
            FilePath workspace = new FilePath(mockLauncher.getChannel(), workspaceDir.getAbsolutePath());
            Map<String, String> artifacts = new HashMap<>();
            for (File file : testEnv.uploadFileList.values()) {
                artifacts.put(file.getName(), file.getName());
            }

            StorageAccountInfo accountInfo = new StorageAccountInfo(testEnv.azureStorageAccountName, testEnv.azureStorageAccountKey1, null);
            PowerMockito.mockStatic(Utils.class);
            when(Utils.getStorageAccount(any())).thenReturn(accountInfo);
            AzureArtifactConfig config = new AzureArtifactConfig();
            config.setContainer(containerName);
            config.setPrefix(PREFIX);
            ArtifactManager artifactManager = new AzureArtifactManager(mockRun, config);
            BuildListener mockListener = mock(BuildListener.class);
            PrintStream printStream = mock(PrintStream.class);
            when(mockListener.getLogger()).thenReturn(printStream);
            artifactManager.archive(workspace, mockLauncher, mockListener, artifacts);


            for (ListBlobItem blobItem : testEnv.container.listBlobs(String.format("%s%s/%d/", PREFIX, JOB_NAME, BUILD_NUMBER))) {
                if (blobItem instanceof CloudBlockBlob) {
                    CloudBlockBlob downloadedBlob = (CloudBlockBlob) blobItem;
                    String downloadedContent = downloadedBlob.downloadText("utf-8", null, null, null);
                    File temp = testEnv.uploadFileList.get(downloadedContent);
                    String tempContent = FileUtils.readFileToString(temp, "utf-8");
                    //check for filenames
                    assertEquals(tempContent, downloadedContent);
                    //check for file contents
                    assertEquals(CHILD + downloadedContent + FILE_EXTENSION, temp.getName());
                    temp.delete();
                }
            }
            testEnv.container.deleteIfExists();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }
}
