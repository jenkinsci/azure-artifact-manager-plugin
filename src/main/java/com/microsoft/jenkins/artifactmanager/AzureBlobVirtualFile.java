/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.remoting.Callable;
import jenkins.util.VirtualFile;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public class AzureBlobVirtualFile extends AzureAbstractVirtualFile {
    private static final long serialVersionUID = 9054620703341308471L;

    private static final Logger LOGGER = Logger.getLogger(AzureBlobVirtualFile.class.getName());
    private static final String AZURE_BLOB_URL_PATTERN = "https://%s.blob.core.windows.net/%s/%s";
    private static final int NOT_FOUND = 404;

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

    @NonNull
    @Override
    public String getName() {
        String localKey = stripTrailingSlash(key);

        return localKey.replaceFirst(".+/", Constants.EMPTY_STRING);
    }

    private String stripTrailingSlash(String string) {
        String localKey = string;
        if (string.endsWith("/")) {
            localKey = localKey.substring(0, localKey.length() - 1);
        }
        return localKey;
    }

    @NonNull
    @Override
    public URI toURI() {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            String encodedKey = StringUtils.replace(this.key, "%", "%25");
            return new URI(String.format(AZURE_BLOB_URL_PATTERN, accountInfo.getStorageAccName(),
                    container, encodedKey));
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
        String keyWithSlash = stripTrailingSlash(this.key) + Constants.FORWARD_SLASH;
        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[]{container, keyWithSlash});

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            Iterator<BlobItem> iterator = blobContainerReference.listBlobsByHierarchy(keyWithSlash).iterator();
            return iterator.hasNext();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean isFile() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            BlobClient blockBlobReference = blobContainerReference.getBlobClient(key);
            return blockBlobReference.exists();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @NonNull
    @Override
    public VirtualFile[] list() throws IOException {
        if (StringUtils.isBlank(this.container)) {
            // compatible with version before 0.1.2
            return new VirtualFile[0];
        }
        VirtualFile[] list;
        String keys = stripTrailingSlash(this.key) + Constants.FORWARD_SLASH;

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            list = listBlobsFromPrefix(keys, blobContainerReference).toArray(new VirtualFile[0]);
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        return list;
    }

    private List<VirtualFile> listBlobsFromPrefix(String keys, BlobContainerClient blobContainerReference) {
        PagedIterable<BlobItem> blobItems = blobContainerReference.listBlobsByHierarchy(keys);
        List<VirtualFile> files = new ArrayList<>();
        for (BlobItem blobItem : blobItems) {
            files.add(new AzureBlobVirtualFile(this.container, blobItem.getName(), this.build));
        }
        return files;
    }

    @NonNull
    @Override
    public VirtualFile child(@NonNull String name) {
        String joinedKey = stripTrailingSlash(this.key) + Constants.FORWARD_SLASH + name;
        return new AzureBlobVirtualFile(this.container, joinedKey, build);
    }

    @Override
    public long length() throws IOException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            BlobClient blobClient = blobContainerReference.getBlobClient(this.key);

            BlobProperties properties = blobClient.getProperties();

            return properties.getBlobSize();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == NOT_FOUND) {
                return 0;
            }
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public long lastModified() throws IOException {
        if (isDirectory()) {
            return 0;
        }

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            BlobClient blockBlobReference = blobContainerReference.getBlobClient(this.key);
            BlobProperties properties = blockBlobReference.getProperties();
            OffsetDateTime lastModified = properties.getLastModified();
            return lastModified == null ? 0 : lastModified.toEpochSecond();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == NOT_FOUND) {
                return 0;
            }
            // drop the cause in case there's unserializable fields
            throw new IOException(e.getMessage());
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
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            BlobClient blockBlobReference = blobContainerReference.getBlobClient(this.key);
            return blockBlobReference.openInputStream();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
