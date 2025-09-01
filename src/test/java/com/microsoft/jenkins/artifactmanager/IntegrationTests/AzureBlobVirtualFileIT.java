package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.microsoft.jenkins.artifactmanager.AzureBlobVirtualFile;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import jenkins.util.VirtualFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzureBlobVirtualFileIT extends IntegrationTest {

    private AzureBlobVirtualFile root, subDir, temp, subTemp, missing;
    private File workspaceDir;

    private static final String TEMP_FILE_RELATIVE_PATH = "test/test.txt";
    private static final String TEMP_FILE_NAME = "test.txt";
    private static final String TEMP_FILE_CONTENT = "test";
    private static final String SUB_TEMP_FILE_RELATIVE_PATH = "test/sub/sub.txt";
    private static final String SUB_TEMP_FILE_NAME = "sub.txt";
    private static final String SUB_FOLDER_NAME = "sub";
    private static final String SUB_FOLDER_RELATIVE_PATH = "test/sub";
    private static final String ROOT_FOLDER_NAME = "test";

    @BeforeEach
    void beforeEach() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild run = project.scheduleBuild2(0).get();

        String containerName = "testvirtualfile" + TestEnvironment.generateRandomString(15);

        workspaceDir = new File(containerName);

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

        File tempFile = new File(directory.getAbsolutePath(), TEMP_FILE_NAME);
        FileUtils.writeStringToFile(tempFile, TEMP_FILE_CONTENT, StandardCharsets.UTF_8);
        File subDirectory = new File(directory.getAbsolutePath(), SUB_FOLDER_NAME);
        boolean mkdir = subDirectory.mkdir();
        if (!mkdir) {
            throw new IllegalStateException();
        }
        File subTempFile = new File(subDirectory.getAbsolutePath(), SUB_TEMP_FILE_NAME);
        FileUtils.writeStringToFile(subTempFile, "sub", StandardCharsets.UTF_8);

        uploadFile(tempFile, TEMP_FILE_RELATIVE_PATH);
        uploadFile(subTempFile, SUB_TEMP_FILE_RELATIVE_PATH);

        root = new AzureBlobVirtualFile(containerName, ROOT_FOLDER_NAME, false, run);
        subDir = new AzureBlobVirtualFile(containerName, SUB_FOLDER_RELATIVE_PATH, false, run);
        temp = new AzureBlobVirtualFile(containerName, TEMP_FILE_RELATIVE_PATH, false, run);
        subTemp = new AzureBlobVirtualFile(containerName, SUB_TEMP_FILE_RELATIVE_PATH, false, run);
        missing = new AzureBlobVirtualFile(containerName, "missing", false, run);
    }

    private void uploadFile(File file, String cloudFileName) {
        BlobClient blob = testEnv.container.getBlobClient(cloudFileName);
        blob.uploadFromFile(file.getAbsolutePath());
    }

    @Test
    void exists() {
        assertTrue(root.exists());
        assertTrue(subDir.exists());
        assertTrue(temp.exists());
        assertTrue(subTemp.exists());
        assertFalse(missing.exists());
    }

    @Test
    void child() throws IOException {
        assertTrue(root.child(SUB_FOLDER_NAME).exists());
        VirtualFile child = subDir.child(SUB_TEMP_FILE_NAME);
        assertTrue(child.exists());
        assertFalse(root.child("missing").exists());
    }

    @Test
    void getName() {
        assertEquals("missing", missing.getName());
        assertEquals(SUB_TEMP_FILE_NAME, subTemp.getName());
        assertEquals(SUB_FOLDER_NAME, subDir.getName());
    }

    @Test
    void isDirectory() {
        assertTrue(root.isDirectory());
        assertTrue(subDir.isDirectory());
        assertFalse(temp.isDirectory());
        assertFalse(subTemp.isDirectory());
        assertFalse(missing.isDirectory());
    }

    @Test
    void isFile() {
        assertTrue(temp.isFile());
        assertTrue(subTemp.isFile());
        assertFalse(missing.isFile());
        assertFalse(root.isFile());
        assertFalse(subDir.isFile());
    }

    @Test
    void lastModified() throws IOException {
        assertEquals(0, root.lastModified());
        assertNotEquals(0, subTemp.lastModified());
        assertEquals(0, missing.lastModified());
    }

    @Test
    void length() throws IOException {
        assertEquals(0, missing.length());
        assertEquals(0, root.length());
        assertEquals(0, subDir.length());
        assertEquals(3, subTemp.length());
        assertEquals(4, temp.length());
    }

    @Test
    void list() {
        VirtualFile[] list = root.list();
        assertArrayEquals(new AzureBlobVirtualFile[]{temp, subDir}, list);
        assertArrayEquals(new AzureBlobVirtualFile[0], missing.list());
    }

    @Test
    void open() throws IOException {
        assertThrows(FileNotFoundException.class, () -> subDir.open());
        assertThrows(FileNotFoundException.class, () -> missing.open());
        try (InputStream is = temp.open()) {
            assertEquals(TEMP_FILE_CONTENT, IOUtils.toString(is, StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void afterEach() {
        try {
            FileUtils.deleteDirectory(workspaceDir);
            if (testEnv.container.exists()) {
                testEnv.container.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
