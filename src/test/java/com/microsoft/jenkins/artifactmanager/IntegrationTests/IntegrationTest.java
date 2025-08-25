package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.microsoftopentechnologies.windowsazurestorage.helper.Utils;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import org.junit.jupiter.api.BeforeAll;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.HashMap;
import java.util.UUID;

/**
 * Copy from windows-azure-storage-plugin since they use the same integration tests environment.
 */
@WithJenkins
class IntegrationTest {

    protected static JenkinsRule j;

    protected TestEnvironment testEnv = null;

    @BeforeAll
    static void beforeAll(JenkinsRule rule) {
        ConfigurationAsCode.get().configure("/com/microsoft/jenkins/artifactmanager/configuration-as-code.yml");
        j = rule;
    }

    protected static class TestEnvironment {
        public final String azureStorageAccountName;
        public final String azureStorageAccountKey1;
        public final String azureStorageAccountKey2;

        public final String blobURL;
        public final StorageAccountInfo sampleStorageAccount;
        public static final int TOTAL_FILES = 50;
        public final HashMap<String, File> uploadFileList = new HashMap<>();
        public final String containerName;
        public final String shareName;
        public BlobContainerClient container;
        public BlobServiceClient blobClient;

        TestEnvironment(String name) {
            azureStorageAccountName = TestEnvironment.loadFromEnv("AZURE_STORAGE_TEST_STORAGE_ACCOUNT_NAME");
            azureStorageAccountKey1 = TestEnvironment.loadFromEnv("AZURE_STORAGE_TEST_STORAGE_ACCOUNT_KEY1");
            azureStorageAccountKey2 = TestEnvironment.loadFromEnv("AZURE_STORAGE_TEST_STORAGE_ACCOUNT_KEY2");

            blobURL = Utils.DEF_BLOB_URL;
            sampleStorageAccount = new StorageAccountInfo(azureStorageAccountName, azureStorageAccountKey1, blobURL, "");
            containerName = name;
            shareName = name;
        }

        private static String loadFromEnv(final String name) {
            return TestEnvironment.loadFromEnv(name, "");
        }

        private static String loadFromEnv(final String name, final String defaultValue) {
            final String value = System.getenv(name);
            if (value == null || value.isEmpty()) {
                return defaultValue;
            } else {
                return value;
            }
        }

        public static String generateRandomString(int length) {
            String uuid = UUID.randomUUID().toString();
            return uuid.replaceAll("[^a-z0-9]", "a").substring(0, length);
        }
    }
}
