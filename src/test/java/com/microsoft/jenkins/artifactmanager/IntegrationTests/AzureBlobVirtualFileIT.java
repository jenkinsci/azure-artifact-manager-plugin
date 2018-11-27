package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.jenkins.artifactmanager.AzureArtifactConfig;
import com.microsoft.jenkins.artifactmanager.AzureBlobVirtualFile;
import com.microsoft.jenkins.artifactmanager.Utils;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Jenkins.class, Utils.class})
@PowerMockIgnore({"javax.management.*", "javax.crypto.*"})
public class AzureBlobVirtualFileIT extends IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(AzureBlobVirtualFileIT.class.getName());
    private AzureBlobVirtualFile root, subDir, temp, subTemp, missing;
    private File workspaceDir;

    private static final String JOB_NAME = "job";
    private static final Integer BUILD_NUMBER = 1;
    private static final String TEMP_FILE_RELATIVE_PATH = "test/test.txt";
    private static final String TEMP_FILE_NAME = "test.txt";
    private static final String TEMP_FILE_CONTENT = "test";
    private static final String SUB_TEMP_FILE_RELATIVE_PATH = "test/sub/sub.txt";
    private static final String SUB_TEMP_FILE_NAME = "sub.txt";
    private static final String SUB_FOLDER_NAME = "sub";
    private static final String SUB_FOLDER_RELATIVE_PATH = "test/sub";
    private static final String ROOT_FOLDER_NAME = "test";

    @Before
    public void setUp() throws Exception {
        disableAI();
        FreeStyleProject freeStyleProject = mock(FreeStyleProject.class);
        when(freeStyleProject.getFullName()).thenReturn(JOB_NAME);

        String containerName = "testvirtualfile" + TestEnvironment.GenerateRandomString(15);

        workspaceDir = new File(containerName);


        Run run = mock(Run.class);
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

            File temp = new File(directory.getAbsolutePath(), TEMP_FILE_NAME);
            FileUtils.writeStringToFile(temp, TEMP_FILE_CONTENT);
            File subDirectory = new File(directory.getAbsolutePath(), SUB_FOLDER_NAME);
            subDirectory.mkdir();
            File subTemp = new File(subDirectory.getAbsolutePath(), SUB_TEMP_FILE_NAME);
            FileUtils.writeStringToFile(subTemp, "sub");

            uploadFile(temp, TEMP_FILE_RELATIVE_PATH);
            uploadFile(subTemp, SUB_TEMP_FILE_RELATIVE_PATH);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }

        StorageAccountInfo accountInfo = new StorageAccountInfo(testEnv.azureStorageAccountName, testEnv.azureStorageAccountKey1, null);
        PowerMockito.mockStatic(Utils.class);
        when(Utils.getStorageAccount(any())).thenReturn(accountInfo);
        when(Utils.getBlobContainerReference(accountInfo, containerName, false, true, false)).thenReturn(testEnv.container);
        when(Utils.generateBlobSASURL(any(), any(), any())).thenCallRealMethod();
        when(Utils.getCloudStorageAccount(any())).thenCallRealMethod();
        when(Utils.generateBlobPolicy()).thenCallRealMethod();
        when(Utils.generateExpiryDate()).thenCallRealMethod();

        root = new AzureBlobVirtualFile(containerName, ROOT_FOLDER_NAME, run);
        subDir = new AzureBlobVirtualFile(containerName, SUB_FOLDER_RELATIVE_PATH, run);
        temp = new AzureBlobVirtualFile(containerName, TEMP_FILE_RELATIVE_PATH, run);
        subTemp = new AzureBlobVirtualFile(containerName, SUB_TEMP_FILE_RELATIVE_PATH, run);
        missing = new AzureBlobVirtualFile(containerName, "missing", run);
    }

    private void uploadFile(File file, String cloudFileName) throws Exception {
        CloudBlockBlob blob = testEnv.container.getBlockBlobReference(cloudFileName);
        blob.uploadFromFile(file.getAbsolutePath());
    }

    @Test
    public void exists() throws IOException {
        assertTrue(root.exists());
        assertTrue(subDir.exists());
        assertTrue(temp.exists());
        assertTrue(subTemp.exists());
        assertFalse(missing.exists());
    }

    @Test
    public void child() throws IOException {
        assertTrue(root.child(SUB_FOLDER_NAME).exists());
        assertTrue(subDir.child(SUB_TEMP_FILE_NAME).exists());
        assertFalse(root.child("missing").exists());
    }

    @Test
    public void getName() {
        assertEquals("missing", missing.getName());
        assertEquals(SUB_TEMP_FILE_NAME, subTemp.getName());
        assertEquals(SUB_FOLDER_NAME, subDir.getName());
    }

    @Test
    public void isDirectory() throws IOException {
        assertTrue(root.isDirectory());
        assertTrue(subDir.isDirectory());
        assertFalse(temp.isDirectory());
        assertFalse(subTemp.isDirectory());
        assertFalse(missing.isDirectory());
    }

    @Test
    public void isFile() throws IOException {
        assertTrue(temp.isFile());
        assertTrue(subTemp.isFile());
        assertFalse(missing.isFile());
        assertFalse(root.isFile());
        assertFalse(subDir.isFile());
    }

    @Test
    public void lastModified() throws IOException {
        assertEquals(0, root.lastModified());
        assertEquals(0, subTemp.lastModified());
        assertEquals(0, missing.lastModified());
    }

    @Test
    public void length() throws IOException {
        assertEquals(0, missing.length());
        assertEquals(7, root.length());
        assertEquals(3, subDir.length());
        assertEquals(3, subTemp.length());
        assertEquals(4, temp.length());
    }

    @Test
    public void list() throws IOException {
        assertArrayEquals(new AzureBlobVirtualFile[]{subDir, temp}, root.list());
        assertArrayEquals(new AzureBlobVirtualFile[0], missing.list());
    }

    @Test
    public void open() throws IOException {
        try (InputStream is = subDir.open()) {
            fail("Cannot open a dir");
        } catch (FileNotFoundException e) {
            // expected
        }
        try (InputStream is = missing.open()) {
            fail("Cannot open a missing file");
        } catch (FileNotFoundException e) {
            // expected
        }
        try (InputStream is = temp.open()) {
            assertEquals(TEMP_FILE_CONTENT, IOUtils.toString(is));
        }
    }


    @After
    public void cleanUp() {
        try {
            FileUtils.deleteDirectory(workspaceDir);
            testEnv.container.deleteIfExists();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
