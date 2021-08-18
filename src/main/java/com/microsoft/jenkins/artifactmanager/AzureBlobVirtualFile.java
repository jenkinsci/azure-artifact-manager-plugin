/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.remoting.Callable;
import jenkins.util.VirtualFile;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public class AzureBlobVirtualFile extends AzureAbstractVirtualFile {
    private static final long serialVersionUID = 9054620703341308471L;

    private static final Logger LOGGER = Logger.getLogger(AzureBlobVirtualFile.class.getName());
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

    /**
     * Cache of metadata collected during {@link #run}.
     * Keys are {@link #container}.
     * Values are a stack of cache frames, one per nested {@link #run} call.
     */
    private static final ThreadLocal<Map<String, Deque<CacheFrame>>> CACHE = ThreadLocal.withInitial(HashMap::new);

    private static final class CacheFrame {
        /** {@link #key} of the root virtual file plus a trailing {@code /}. */
        private final String root;
        /**
         * Information about all known (recursive) child <em>files</em> (not directories).
         * Keys are {@code /}-separated relative paths.
         * If the root itself happened to be a file, that information is not cached.
         */
        private final Map<String, CachedMetadata> children;
        CacheFrame(String root, Map<String, CachedMetadata> children) {
            this.root = root;
            this.children = children;
        }
    }

    /**
     * Record that a given file exists.
     */
    private static final class CachedMetadata {
        private final long length, lastModified;
        CachedMetadata(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }
    }


    @Override
    public <V> V run(Callable<V, IOException> callable) throws IOException {
        LOGGER.log(Level.FINE, "enter cache {0} / {1}", new Object[] {container, key});
        Deque<CacheFrame> stack = cacheFrames();
        Map<String, CachedMetadata> saved = new HashMap<>();
        try {
            StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
            Objects.requireNonNull(this.container, "Container must not be null");
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            ListBlobsOptions listBlobsOptions = new ListBlobsOptions()
                    .setPrefix(key);

            for (BlobItem sm : blobContainerReference.listBlobs(listBlobsOptions, null)) {
                BlobItemProperties properties = sm.getProperties();
                OffsetDateTime lastModified = properties.getLastModified();
                long lastModifiedMilli = lastModified.toInstant().toEpochMilli();
                String fileName = stripBeginningSlash(sm.getName().replaceFirst(key, ""));
                saved.put(fileName,
                        new CachedMetadata(properties.getContentLength(), lastModifiedMilli));
            }
        } catch (RuntimeException | URISyntaxException x) {
            throw new IOException(x);
        }
        stack.push(new CacheFrame(stripTrailingSlash(key) + "/", saved));
        try {
            LOGGER.log(Level.FINE, "using cache {0} / {1}: {2} file entries",
                    new Object[] {container, key, saved.size()});
            return callable.call();
        } finally {
            LOGGER.log(Level.FINE, "exit cache {0} / {1}", new Object[] {container, key});
            stack.pop();
        }
    }

    private Deque<CacheFrame> cacheFrames() {
        return CACHE.get().computeIfAbsent(container, c -> new ArrayDeque<>());
    }

    /**
     * Finds a cache frame whose {@link CacheFrame#root} is a prefix of the given {@link #key}
     * or {@code /}-appended variant.
     *
     */
    private @CheckForNull CacheFrame findCacheFrame(String cacheKey) {
        return cacheFrames().stream().filter(frame -> cacheKey.startsWith(frame.root)).findFirst().orElse(null);
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

    private String stripBeginningSlash(String string) {
        String localKey = string;
        if (string.startsWith("/")) {
            localKey = localKey.substring(1);
        }
        return localKey;
    }

    @NonNull
    @Override
    public URI toURI() {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            return new URI(Utils.getBlobUrl(accountInfo, this.container, this.key));
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
        String keyWithNoSlash = stripTrailingSlash(this.key);

        if (keyWithNoSlash.endsWith("/*view*")) {
            return false;
        }

        String keyS = keyWithNoSlash + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINER, "cache hit on directory status of {0} / {1}", new Object[] {container, this.key});
            String relSlash = stripTrailingSlash(keyS.substring(frame.root.length())); // "" or "sub/dir/"
            Set<String> children = frame.children.keySet();
            boolean existsInCache = children.stream().anyMatch(f -> f.startsWith(relSlash));
            // if we don't know about it then it's not a directory
            if (!existsInCache) {
                return false;
            }
            // if it's not an exact file path then it's not a directory
            return children.stream().noneMatch(f -> f.equals(relSlash));
        }

        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[]{container, keyWithNoSlash});

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        try {
            BlobContainerClient blobContainerReference = Utils.getBlobContainerReference(accountInfo, this.container,
                    false);
            Iterator<BlobItem> iterator = blobContainerReference.listBlobsByHierarchy(keyS).iterator();
            return iterator.hasNext();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean isFile() throws IOException {
        String keyS = key + "/";

        if (keyS.endsWith("/*view*/")) {
            return false;
        }

        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            String rel = stripTrailingSlash(keyS.substring(frame.root.length()));
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on file status of {0} / {1}", new Object[] {container, key});
            return metadata != null;
        }

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
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINER, "cache hit on listing of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            VirtualFile[] virtualFiles = frame.children.keySet().stream() // filenames relative to frame root
                    .filter(f -> f.startsWith(relSlash)) // those inside this dir
                    // just the file simple name, or direct subdir name
                    .map(f -> f.substring(relSlash.length()).replaceFirst("/.+", ""))
                    .distinct() // ignore duplicates if have multiple files under one direct subdir
                    // direct children
                    .map(simple -> new AzureBlobVirtualFile(this.container, keyS + simple, this.build))
                    .toArray(VirtualFile[]::new);
            return virtualFiles;
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
            files.add(new AzureBlobVirtualFile(this.container, stripTrailingSlash(blobItem.getName()), this.build));
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
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            String rel = stripTrailingSlash(keyS.substring(frame.root.length()));
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on length of {0} / {1}", new Object[] {container, key});
            return metadata != null ? metadata.length : 0;
        }

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
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            String rel = stripTrailingSlash(keyS.substring(frame.root.length()));
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on lastModified of {0} / {1}", new Object[] {container, key});
            return metadata != null ? metadata.lastModified : 0;
        }

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
