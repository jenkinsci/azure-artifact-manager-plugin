package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.microsoft.jenkins.artifactmanager.AzureArtifactConfig;
import com.microsoft.jenkins.artifactmanager.AzureArtifactManager;
import com.microsoft.jenkins.artifactmanager.AzureArtifactManagerFactory;
import com.microsoft.jenkins.artifactmanager.Constants;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.util.DescribableList;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArtifactManagerIT extends IntegrationTest {
    private AzureArtifactConfig config;
    private Run run;
    private Launcher mockLauncher;
    private FilePath workspace;

    private static final String CHILD = "artifact";
    private static final String FILE_EXTENSION = ".txt";
    private static final String PREFIX = "prefix/";
    private static final String JOB_NAME = "job";
    private static final Integer BUILD_NUMBER = 1;
    private static final String STASH_NAME = "stash";


    @Before
    public void setUp() throws IOException, ExecutionException, InterruptedException {
        config = new AzureArtifactConfig("storage");
        String containerName = "testartifacts" + TestEnvironment.GenerateRandomString(15);
        config.setContainer(containerName);
        config.setPrefix(PREFIX);
        AzureArtifactManagerFactory artifactManager = new AzureArtifactManagerFactory(config);

        ArtifactManagerConfiguration artifactManagerConfiguration = ArtifactManagerConfiguration.get();
        DescribableList<ArtifactManagerFactory, ArtifactManagerFactoryDescriptor> artifactManagerFactories =
                artifactManagerConfiguration.getArtifactManagerFactories();
        artifactManagerFactories.replace(artifactManager);

        File workspaceDir = new File(containerName);
        ByteArrayOutputStream listener = new ByteArrayOutputStream();
        BuildListener mockListener = new StreamBuildListener(listener, StandardCharsets.UTF_8);
        mockLauncher = new Launcher.DummyLauncher(mockListener);
        workspace = new FilePath(mockLauncher.getChannel(), workspaceDir.getAbsolutePath());

        FreeStyleProject freeStyleProject = j.createFreeStyleProject();
        run = freeStyleProject.scheduleBuild2(0).get();

        testEnv = new TestEnvironment(containerName);
        File directory = new File(containerName);
        if (!directory.exists()) {
            boolean mkdirs = directory.mkdirs();
            if (!mkdirs) {
                throw new IllegalStateException();
            }
        }

        testEnv.blobClient = new BlobServiceClientBuilder()
                .credential(new StorageSharedKeyCredential(testEnv.azureStorageAccountName, testEnv.azureStorageAccountKey1))
                .endpoint("https://" + testEnv.azureStorageAccountName + ".blob.core.windows.net")
                .buildClient();
        testEnv.container = testEnv.blobClient.getBlobContainerClient(containerName);
        if (!testEnv.container.exists()) {
            testEnv.container.create();
        }

        for (int i = 0; i < TestEnvironment.TOTAL_FILES; i++) {
            String tempContent = UUID.randomUUID().toString();
            File temp = new File(directory.getAbsolutePath(), CHILD + tempContent + FILE_EXTENSION);
            FileUtils.writeStringToFile(temp, tempContent, StandardCharsets.UTF_8);
            testEnv.uploadFileList.put(tempContent, temp);
        }
    }

    @After
    public void cleanUp() {
        try {
            workspace.deleteRecursive();
            if (testEnv.container.exists()) {
                testEnv.container.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testStash() throws IOException, InterruptedException {
        AzureArtifactManager artifactManager = new AzureArtifactManager(run, config);

        ByteArrayOutputStream listener = new ByteArrayOutputStream();
        BuildListener mockListener = new StreamBuildListener(listener, StandardCharsets.UTF_8);

        EnvVars envVars = new EnvVars();
        artifactManager.stash(STASH_NAME, workspace, mockLauncher, envVars, mockListener, "*", null, true, false);
        for (BlobItem blobItem : testEnv.container.listBlobsByHierarchy(String.format("%s%s/%d/%s", PREFIX, JOB_NAME, BUILD_NUMBER, Constants.STASHES_PATH))) {
            if (!Boolean.TRUE.equals(blobItem.isPrefix())) {
                String name = blobItem.getName();
                assertTrue(name.endsWith(STASH_NAME + Constants.TGZ_FILE_EXTENSION));
            }
        }

        workspace.deleteRecursive();
        artifactManager.unstash(STASH_NAME, workspace, mockLauncher, envVars, mockListener);
        artifactManager.clearAllStashes(mockListener);
        assertEquals(TestEnvironment.TOTAL_FILES, workspace.list().size());
    }

    @Test
    public void testArchive() throws IOException, InterruptedException {
        Map<String, String> artifacts = new HashMap<>();
        for (File file : testEnv.uploadFileList.values()) {
            artifacts.put(file.getName(), file.getName());
        }

        ByteArrayOutputStream listener = new ByteArrayOutputStream();
        BuildListener mockListener = new StreamBuildListener(listener, StandardCharsets.UTF_8);

        ArtifactManager artifactManager = new AzureArtifactManager(run, config);
        artifactManager.archive(workspace, mockLauncher, mockListener, artifacts);

        for (BlobItem blobItem : testEnv.container.listBlobsByHierarchy(String.format("%s%s/%d/%s", PREFIX, JOB_NAME, BUILD_NUMBER, Constants.ARTIFACTS_PATH))) {
            if (!Boolean.TRUE.equals(blobItem.isPrefix())) {
                BlobClient blobClient = testEnv.container.getBlobClient(blobItem.getName());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                blobClient.download(output);
                String downloadedContent = output.toString(StandardCharsets.UTF_8.name());
                File temp = testEnv.uploadFileList.get(downloadedContent);
                String tempContent = FileUtils.readFileToString(temp, "utf-8");
                //check for filenames
                assertEquals(tempContent, downloadedContent);
                //check for file contents
                assertEquals(CHILD + downloadedContent + FILE_EXTENSION, temp.getName());
            }
        }

    }

    @Test
    @Ignore("https://github.com/jenkinsci/azure-artifact-manager-plugin/pull/15/files#r627353801")
    public void artifactStash() throws Throwable {
        // TODO haven't managed to get weird characters test fully working, disabled for now
        ArtifactManagerTest.artifactStash(j, getArtifactManagerFactory(), false, null);
    }

    private ArtifactManagerFactory getArtifactManagerFactory() {
        return new AzureArtifactManagerFactory(this.config);
    }
}
