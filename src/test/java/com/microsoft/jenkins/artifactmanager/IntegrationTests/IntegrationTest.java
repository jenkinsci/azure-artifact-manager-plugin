package com.microsoft.jenkins.artifactmanager.IntegrationTests;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.microsoftopentechnologies.windowsazurestorage.helper.Utils;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import org.junit.ClassRule;

import java.io.File;
import java.util.HashMap;
import java.util.UUID;

/**
 * Copy from windows-azure-storage-plugin since they use the same integration tests environment.
 */
public class IntegrationTest {
    @ClassRule
    // different tests access this so provide absolute path
    @ConfiguredWithCode("/com/microsoft/jenkins/artifactmanager/configuration-as-code.yml")
    public static JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    protected static class TestEnvironment {
        public final String azureStorageAccountName;
        public final String azureStorageAccountKey1;
        public final String azureStorageAccountKey2;

        public final String blobURL;
        public final String CdnURL;
        public final StorageAccountInfo sampleStorageAccount;
        public static final int TOTAL_FILES = 50;
        public HashMap<String, File> uploadFileList = new HashMap<>();
        public String containerName;
        public String shareName;
        public BlobContainerClient container;
        public BlobServiceClient blobClient;

        TestEnvironment(String name) {
            azureStorageAccountName = TestEnvironment.loadFromEnv("AZURE_STORAGE_TEST_STORAGE_ACCOUNT_NAME");
            azureStorageAccountKey1 = TestEnvironment.loadFromEnv("AZURE_STORAGE_TEST_STORAGE_ACCOUNT_KEY1");
            azureStorageAccountKey2 = TestEnvironment.loadFromEnv("AZURE_STORAGE_TEST_STORAGE_ACCOUNT_KEY2");

            blobURL = Utils.DEF_BLOB_URL;
            CdnURL = "";
            sampleStorageAccount = new StorageAccountInfo(azureStorageAccountName, azureStorageAccountKey1, blobURL, CdnURL);
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

        public static String GenerateRandomString(int length) {
            String uuid = UUID.randomUUID().toString();
            return uuid.replaceAll("[^a-z0-9]", "a").substring(0, length);
        }
    }

    protected TestEnvironment testEnv = null;
}
