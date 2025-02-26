/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.microsoftopentechnologies.windowsazurestorage.helper.AzureStorageAccount;
import com.microsoftopentechnologies.windowsazurestorage.helper.Constants;
import hudson.Util;
import hudson.model.Item;
import hudson.util.DescribableList;
import io.jenkins.plugins.azuresdk.HttpClientRetriever;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import org.apache.commons.lang.StringUtils;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

public final class Utils {
    private static final String PREFIX_PATTERN = "^[a-z0-9A-Z]{1,30}/?$";
    private static final int CONFLICT = 409;

    public static AzureArtifactConfig getArtifactConfig() {
        ArtifactManagerConfiguration artifactManagerConfiguration = ArtifactManagerConfiguration.get();
        DescribableList<ArtifactManagerFactory, ArtifactManagerFactoryDescriptor> artifactManagerFactories =
                artifactManagerConfiguration.getArtifactManagerFactories();
        AzureArtifactManagerFactory azureArtifactManagerFactory =
                artifactManagerFactories.get(AzureArtifactManagerFactory.class);
        return azureArtifactManagerFactory.getConfig();
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
            return lcContainerName.matches(Constants.VAL_CNT_NAME);
        }
        return false;
    }

    public static boolean isPrefixValid(String prefix) {
        if (StringUtils.isEmpty(prefix)) {
            return true;
        }

        return prefix.matches(PREFIX_PATTERN);
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
        AzureStorageAccount.StorageAccountCredential accountCredentials =
                AzureStorageAccount.getStorageAccountCredential(item, config.getStorageCredentialId());
        return AzureStorageAccount.convertToStorageAccountInfo(accountCredentials);
    }

    public static String getBlobUrl(
            StorageAccountInfo storageAccount,
            String containerName,
            String blobName) {

        BlobServiceClient cloudStorageAccount = getCloudStorageAccount(storageAccount);

        // Create the blob client.
        BlobContainerClient container = cloudStorageAccount.getBlobContainerClient(containerName);

        BlobClient blob = container.getBlobClient(blobName);

        // Check if CDN endpoint exists and use CDN URL, otherwise use blob URL
        String cdnEndpoint = storageAccount.getCdnEndPointURL();
        if (!StringUtils.isBlank(cdnEndpoint)) {
            return (cdnEndpoint + "/" + blob.getContainerName() + "/" + blob.getBlobName());
        } else {
            return blob.getBlobUrl();
        }
    }

    public static String generateBlobSASURL(
            StorageAccountInfo storageAccount,
            String containerName,
            String blobName) {

        BlobServiceClient cloudStorageAccount = getCloudStorageAccount(storageAccount);

        // Create the blob client.
        BlobContainerClient container = cloudStorageAccount.getBlobContainerClient(containerName);

        BlobClient blob = container.getBlobClient(blobName);

        return blob.generateSas(generateBlobPolicy());
    }

    public static BlobServiceSasSignatureValues generateBlobPolicy() {
        return new BlobServiceSasSignatureValues(generateExpiryDate(), new BlobSasPermission()
                .setReadPermission(true));
    }

    public static OffsetDateTime generateExpiryDate() {
        return OffsetDateTime.now().plusHours(1);
    }

    public static BlobContainerClient getBlobContainerReference(StorageAccountInfo storageAccount,
                                                                String containerName,
                                                                boolean createIfNotExist) {

        final BlobServiceClient serviceClient = getCloudStorageAccount(storageAccount);

        final BlobContainerClient container = serviceClient.getBlobContainerClient(containerName);

        if (createIfNotExist) {
            if (!container.exists()) {
                try {
                    container.create();
                } catch (BlobStorageException e) {
                    // race condition from multiple builds trying to create the container
                    if (e.getStatusCode() != CONFLICT) {
                        throw e;
                    }
                }
            }
        }

        return container;
    }

    public static BlobServiceClient getCloudStorageAccount(
            final StorageAccountInfo storageAccount) {
        return new BlobServiceClientBuilder()
                .credential(new StorageSharedKeyCredential(storageAccount.getStorageAccName(),
                        storageAccount.getStorageAccountKey()))
                .httpClient(HttpClientRetriever.get())
                .endpoint(storageAccount.getBlobEndPointURL())
                .buildClient();
    }

    private Utils() {
    }
}
