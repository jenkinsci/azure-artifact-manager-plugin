/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.OperationContext;
import com.microsoft.azure.storage.RetryNoRetry;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobContainerPermissions;
import com.microsoft.azure.storage.blob.BlobContainerPublicAccessType;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions;
import com.microsoft.azure.storage.blob.SharedAccessBlobPolicy;
import com.microsoft.azure.storage.core.BaseRequest;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.microsoftopentechnologies.windowsazurestorage.helper.AzureCredentials;
import com.microsoftopentechnologies.windowsazurestorage.helper.Constants;
import hudson.Util;
import hudson.model.Item;
import hudson.util.DescribableList;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class Utils {
    private static final String PREFIX_PATTERN = "^[a-z0-9A-Z]{1,30}/?$";

    public static AzureArtifactConfig getArtifactConfig() {
        ArtifactManagerConfiguration artifactManagerConfiguration = ArtifactManagerConfiguration.get();
        DescribableList<ArtifactManagerFactory, ArtifactManagerFactoryDescriptor> artifactManagerFactories =
                artifactManagerConfiguration.getArtifactManagerFactories();
        AzureArtifactManagerFactory azureArtifactManagerFactory =
                artifactManagerFactories.get(AzureArtifactManagerFactory.class);
        AzureArtifactConfig config = azureArtifactManagerFactory.getConfig();
        return config;
    }

    public static boolean validateContainerName(String containerName) {
        if (containerName != null) {
            if (containerName.equals(Constants.ROOT_CONTAINER) || containerName.equals(Constants.WEB_CONTAINER)) {
                return true;
            }

            String lcContainerName = containerName.trim().toLowerCase(
                    Locale.ENGLISH);
            if (!lcContainerName.equals(containerName)) {
                return false;
            }
            if (lcContainerName.matches(Constants.VAL_CNT_NAME)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPrefixValid(String prefix) {
        if (prefix != null) {
            return prefix.matches(PREFIX_PATTERN);
        }
        return false;
    }

    public static boolean containTokens(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return text.matches(Constants.TOKEN_FORMAT);
    }

    public static String replaceMacro(String s, Map<String, String> props, Locale locale) {
        String result = Util.replaceMacro(s, props);
        if (result == null) {
            return null;
        }
        return result.trim().toLowerCase(locale);
    }

    // TODO methods below should be removed after refactoring windows storage plugin's codes
    public static StorageAccountInfo getStorageAccount(Item item) {
        AzureArtifactConfig config = getArtifactConfig();
        AzureCredentials.StorageAccountCredential accountCredentials =
                AzureCredentials.getStorageAccountCredential(item, config.getStorageCredentialId());
        StorageAccountInfo accountInfo = AzureCredentials.convertToStorageAccountInfo(accountCredentials);
        return accountInfo;
    }

    public static String generateBlobSASURL(
            StorageAccountInfo storageAccount,
            String containerName,
            String blobName) throws Exception {

        CloudStorageAccount cloudStorageAccount = getCloudStorageAccount(storageAccount);

        // Create the blob client.
        CloudBlobClient blobClient = cloudStorageAccount.createCloudBlobClient();
        CloudBlobContainer container = blobClient.getContainerReference(containerName);

        // At this point need to throw an error back since container itself did not exist.
        if (!container.exists()) {
            throw new Exception("WAStorageClient: generateBlobSASURL: Container " + containerName
                    + " does not exist in storage account " + storageAccount.getStorageAccName());
        }

        CloudBlob blob = container.getBlockBlobReference(blobName);
        String sas = blob.generateSharedAccessSignature(generateBlobPolicy(), null);

        return sas;
    }

    public static SharedAccessBlobPolicy generateBlobPolicy() {
        SharedAccessBlobPolicy policy = new SharedAccessBlobPolicy();
        policy.setSharedAccessExpiryTime(generateExpiryDate());
        policy.setPermissions(EnumSet.of(SharedAccessBlobPermissions.READ));

        return policy;
    }

    public static Date generateExpiryDate() {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTime(new Date());
        calendar.add(Calendar.HOUR, 1);
        return calendar.getTime();
    }

    public static CloudBlobContainer getBlobContainerReference(StorageAccountInfo storageAccount,
                                                               String containerName,
                                                               boolean createIfNotExist,
                                                               boolean allowRetry,
                                                               Boolean cntPubAccess)
            throws URISyntaxException, StorageException, IOException {

        final CloudStorageAccount cloudStorageAccount = getCloudStorageAccount(storageAccount);
        final CloudBlobClient serviceClient = cloudStorageAccount.createCloudBlobClient();

        if (!allowRetry) {
            // Setting no retry policy
            final RetryNoRetry rnr = new RetryNoRetry();
            // serviceClient.setRetryPolicyFactory(rnr);
            serviceClient.getDefaultRequestOptions().setRetryPolicyFactory(rnr);
        }

        final CloudBlobContainer container = serviceClient.getContainerReference(containerName);

        boolean cntExists = container.exists();
        if (createIfNotExist && !cntExists) {
            container.createIfNotExists(null, Utils.updateUserAgent());
        }

        // Apply permissions only if container is created newly
        setContainerPermission(container, cntExists, cntPubAccess);

        return container;
    }

    private static void setContainerPermission(
            CloudBlobContainer container,
            boolean cntExists,
            Boolean cntPubAccess) throws StorageException {
        if (!cntExists && cntPubAccess != null) {
            // Set access permissions on container.
            final BlobContainerPermissions cntPerm = new BlobContainerPermissions();
            if (cntPubAccess) {
                cntPerm.setPublicAccess(BlobContainerPublicAccessType.CONTAINER);
            } else {
                cntPerm.setPublicAccess(BlobContainerPublicAccessType.OFF);
            }
            container.uploadPermissions(cntPerm);
        }
    }

    private static String getEndpointSuffix(String blobURL) throws URISyntaxException {
        final int endSuffixStartIndex = blobURL.toLowerCase().indexOf("core");
        if (endSuffixStartIndex < 0) {
            throw new URISyntaxException(blobURL, "The blob endpoint is not correct!");
        }

        return blobURL.substring(endSuffixStartIndex);
    }

    public static CloudStorageAccount getCloudStorageAccount(
            final StorageAccountInfo storageAccount) throws URISyntaxException, MalformedURLException {
        CloudStorageAccount cloudStorageAccount;
        final String accName = storageAccount.getStorageAccName();
        final String blobURLStr = storageAccount.getBlobEndPointURL();
        final StorageCredentialsAccountAndKey credentials = new StorageCredentialsAccountAndKey(accName,
                storageAccount.getStorageAccountKey());

        if (StringUtils.isBlank(blobURLStr) || blobURLStr.equalsIgnoreCase(Constants.DEF_BLOB_URL)) {
            cloudStorageAccount = new CloudStorageAccount(credentials);
        } else {
            final URL blobURL = new URL(blobURLStr);
            boolean useHttps = blobURL.getProtocol().equalsIgnoreCase("https");

            cloudStorageAccount = new CloudStorageAccount(credentials, useHttps, getEndpointSuffix(blobURLStr));
        }

        return cloudStorageAccount;
    }

    @Nonnull
    public static Jenkins getJenkinsInstance() {
        return Jenkins.getInstance();
    }

    public static String getPluginInstance() {
        String instanceId = null;
        try {
            if (Utils.getJenkinsInstance().getLegacyInstanceId() == null) {
                instanceId = "local";
            } else {
                instanceId = Utils.getJenkinsInstance().getLegacyInstanceId();
            }
        } catch (Exception e) {
        }
        return instanceId;
    }

    public static String getPluginVersion() {
        String version = Utils.class.getPackage().getImplementationVersion();
        return version;
    }

    public static OperationContext updateUserAgent() throws IOException {
        return updateUserAgent(null);
    }

    public static OperationContext updateUserAgent(final Long contentLength) throws IOException {
        String version = null;
        String instanceId = null;
        try {
            version = Utils.getPluginVersion();
            if (version == null) {
                version = "local";
            }
            instanceId = Utils.getPluginInstance();
        } catch (Exception e) {
        }

        String pluginUserAgent;
        if (contentLength == null) {
            pluginUserAgent = String.format("%s/%s/%s", Constants.PLUGIN_NAME, version, instanceId);
        } else {
            pluginUserAgent = String.format("%s/%s/%s/ContentLength/%s",
                    Constants.PLUGIN_NAME, version, instanceId, contentLength.toString());
        }

        final String baseUserAgent = BaseRequest.getUserAgent();
        if (baseUserAgent != null) {
            pluginUserAgent = pluginUserAgent + "/" + baseUserAgent;
        }

        OperationContext opContext = new OperationContext();
        HashMap<String, String> temp = new HashMap<String, String>();
        temp.put("User-Agent", pluginUserAgent);

        opContext.setUserHeaders(temp);
        return opContext;
    }

    private Utils() {
    }
}
