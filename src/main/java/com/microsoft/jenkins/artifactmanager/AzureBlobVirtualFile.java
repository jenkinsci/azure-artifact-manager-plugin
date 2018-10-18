/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.microsoft.azure.storage.StorageException;
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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
//    private transient CloudBlob blob;

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
        return key.replaceFirst(".+/", "");
    }

    @Nonnull
    @Override
    public URI toURI() {
        try {
            return new URI(AZURE_BLOB_URL_PATTERN, storageAccountName,
                    URLEncoder.encode(key, StandardCharsets.UTF_8.name()).replace("%2F", "/").replace("%3A", ":"));
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        String sasUrl;
        try {
            sasUrl = Utils.generateBlobSASURL(accountInfo, this.container, this.key);
        } catch (Exception e) {
            throw new IOException(e);
        }
        return new URL(sasUrl);
    }

    @Override
    public VirtualFile getParent() {
        return new AzureBlobVirtualFile(this.container, this.key.replaceFirst("/[^/]+$", ""), this.build);
    }

    @Override
    public boolean isDirectory() throws IOException {
        String key = this.key + "/";
        Cache cache = findCache(key);
        if (cache != null) {
            LOGGER.log(Level.FINE, "cache hit on directory status of {0} / {1}", new Object[]{container, key});
            String relSlash = key.substring(cache.root.length());
            return cache.children.keySet().stream().anyMatch(f -> f.startsWith(relSlash));
        }
        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[]{container, key});

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container, false, true, false);
            Iterator<ListBlobItem> iterator = blobContainerReference.listBlobs(key).iterator();
            return !iterator.hasNext();
        } catch (URISyntaxException | StorageException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean isFile() throws IOException {
        Cache cache = findCache(key);
        if (cache != null) {

        }

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container, false, true, false);
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
        VirtualFile[] list = new VirtualFile[0];
        String keys = this.key + "/";

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container, false, true, false);
            Iterable<ListBlobItem> blobItems = blobContainerReference.listBlobs(keys);
            list = StreamSupport.stream(blobItems.spliterator(), false)
                    .map(item -> new AzureBlobVirtualFile(this.container, item.getUri().toString(), this.build))
                    .toArray(VirtualFile[]::new);
        } catch (StorageException | URISyntaxException e) {
            e.printStackTrace();
        }
        return list;
    }

    @Nonnull
    @Override
    public VirtualFile child(@Nonnull String name) {
        return new AzureBlobVirtualFile(this.container, key + "/" + name, build);
    }

    @Override
    public long length() throws IOException {

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container, false, true, false);
            Iterable<ListBlobItem> blobItems = blobContainerReference.listBlobs(this.key);
            return sumLength(blobItems);
        } catch (StorageException | URISyntaxException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private long sumLength(Iterable<ListBlobItem> blobItems) throws URISyntaxException, StorageException {
        int length = 0;
        for (ListBlobItem blobItem : blobItems) {
            if (blobItem instanceof CloudBlob) {
                CloudBlob cloudBlob = (CloudBlob) blobItem;
                length += cloudBlob.getProperties().getLength();
            } else if (blobItem instanceof CloudBlobDirectory) {
                return sumLength(((CloudBlobDirectory) blobItem).listBlobs());
            }
        }
        return length;
    }

    @Override
    public long lastModified() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container, false, true, false);
            CloudBlockBlob blockBlobReference = blobContainerReference.getBlockBlobReference(this.key);
            return blockBlobReference.getProperties().getLastModified().getTime();
        } catch (StorageException | URISyntaxException e) {
            e.printStackTrace();
        }
        return 0;
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
            CloudBlobContainer blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container, false, true, false);
            CloudBlockBlob blockBlobReference = blobContainerReference.getBlockBlobReference(this.key);
            return blockBlobReference.openInputStream();
        } catch (StorageException | URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
