/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobProperties;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import hudson.model.Run;
import hudson.remoting.Callable;
import jenkins.util.VirtualFile;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

@Restricted(NoExternalUse.class)
public class AzureBlobVirtualFile extends AzureAbstractVirtualFile {
    private static final long serialVersionUID = 9054620703341308471L;

    private static final Logger LOGGER = Logger.getLogger(AzureBlobVirtualFile.class.getName());
    private static final String AZURE_BLOB_URL_PATTERN = "https://%s.blob.core.windows.net/%s/%s";

    private final String container;
    private final String key;
    private final transient Run<?, ?> build;

    public AzureBlobVirtualFile(String container, String key, Run<?, ?> build) {
        this.container = container;
        this.key = key;
        this.build = build;
    }

    public String getContainer() {
        return this.container;
    }

    public String getKey() {
        return this.key;
    }

    @Override
    public <V> V run(Callable<V, IOException> callable) throws IOException {
        return super.run(callable);
    }

    @Nonnull
    @Override
    public String getName() {
        return key.replaceFirst(".+/", Constants.EMPTY_STRING);
    }

    @Nonnull
    @Override
    public URI toURI() {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            return new URI(String.format(AZURE_BLOB_URL_PATTERN, accountInfo.getStorageAccName(), container, key));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        String sas;
        try {
            sas = Utils.generateBlobSASURL(accountInfo, this.container, this.key);
        } catch (Exception e) {
            throw new IOException(e);
        }
        return new URL(toURI() + "?" + sas);
    }

    @Override
    public VirtualFile getParent() {
        return new AzureBlobVirtualFile(this.container, this.key.replaceFirst("/[^/]+$", Constants.EMPTY_STRING),
                this.build);
    }

    @Override
    public boolean isDirectory() throws IOException {
        String keyWithSlash = this.key + Constants.FORWARD_SLASH;
        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[]{container, keyWithSlash});

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false, true, false);
            Iterator<ListBlobItem> iterator = blobContainerReference.listBlobs(keyWithSlash).iterator();
            return iterator.hasNext();
        } catch (URISyntaxException | StorageException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean isFile() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false, true, false);
            CloudBlockBlob blockBlobReference = blobContainerReference.getBlockBlobReference(key);
            return blockBlobReference.exists();
        } catch (URISyntaxException | StorageException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @Nonnull
    @Override
    public VirtualFile[] list() throws IOException {
        VirtualFile[] list;
        String keys = this.key + Constants.FORWARD_SLASH;

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false, true, false);
            Iterable<ListBlobItem> blobItems = blobContainerReference.listBlobs(keys);
            list = StreamSupport.stream(blobItems.spliterator(), false)
                    .map(item -> new AzureBlobVirtualFile(
                            this.container, getRelativePath(item.getUri().toString(), keys), this.build))
                    .toArray(VirtualFile[]::new);
        } catch (StorageException | URISyntaxException e) {
            throw new IOException(e);
        }
        return list;
    }

    private String getRelativePath(String uri, String parent) {
        String substring = uri.substring(uri.indexOf(parent));
        if (substring.endsWith(Constants.FORWARD_SLASH)) {
            substring = substring.substring(0, substring.length() - 1);
        }
        return substring;
    }

    @Nonnull
    @Override
    public VirtualFile child(@Nonnull String name) {
        return new AzureBlobVirtualFile(this.container, key + Constants.FORWARD_SLASH + name, build);
    }

    @Override
    public long length() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false, true, false);
            Iterable<ListBlobItem> blobItems = blobContainerReference.listBlobs(this.key);
            return sumLength(blobItems);
        } catch (StorageException | URISyntaxException e) {

            throw new IOException(e);
        }
    }

    private long sumLength(Iterable<ListBlobItem> blobItems) throws URISyntaxException, StorageException {
        int length = 0;
        for (ListBlobItem blobItem : blobItems) {
            if (blobItem instanceof CloudBlob) {
                CloudBlob cloudBlob = (CloudBlob) blobItem;
                length += cloudBlob.getProperties().getLength();
            } else if (blobItem instanceof CloudBlobDirectory) {
                length += sumLength(((CloudBlobDirectory) blobItem).listBlobs());
            }
        }
        return length;
    }

    @Override
    public long lastModified() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false, true, false);
            CloudBlockBlob blockBlobReference = blobContainerReference.getBlockBlobReference(this.key);
            BlobProperties properties = blockBlobReference.getProperties();
            Date lastModified = properties.getLastModified();
            return lastModified == null ? 0 : lastModified.getTime();
        } catch (StorageException | URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        if (isDirectory()) {
            throw new FileNotFoundException("Cannot open it because it is a directory.");
        }
        if (!isFile()) {
            throw new FileNotFoundException("Cannot open it because it is not a file.");
        }
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false, true, false);
            CloudBlockBlob blockBlobReference = blobContainerReference.getBlockBlobReference(this.key);
            return blockBlobReference.openInputStream();
        } catch (StorageException | URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
