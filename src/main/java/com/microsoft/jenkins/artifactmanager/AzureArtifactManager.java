/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.azure.core.credential.AzureSasCredential;
import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobAsyncClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobUrlParts;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.microsoftopentechnologies.windowsazurestorage.exceptions.WAStorageException;
import com.microsoftopentechnologies.windowsazurestorage.service.DownloadFromContainerService;
import com.microsoftopentechnologies.windowsazurestorage.service.DownloadService;
import com.microsoftopentechnologies.windowsazurestorage.service.UploadService;
import com.microsoftopentechnologies.windowsazurestorage.service.UploadToBlobService;
import com.microsoftopentechnologies.windowsazurestorage.service.model.DownloadServiceData;
import com.microsoftopentechnologies.windowsazurestorage.service.model.UploadServiceData;
import com.microsoftopentechnologies.windowsazurestorage.service.model.UploadType;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.DirScanner;
import hudson.util.LogTaskListener;
import hudson.util.io.ArchiverFactory;
import io.jenkins.plugins.azuresdk.HttpClientRetriever;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.model.Jenkins;
import jenkins.util.VirtualFile;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.microsoft.jenkins.artifactmanager.Utils.generateExpiryDate;

@Restricted(NoExternalUse.class)
public final class AzureArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {
    private static final Logger LOGGER = Logger.getLogger(ArtifactManager.class.getName());
    private final Run<?, ?> build;
    private final AzureArtifactConfig config;
    private String actualContainerName;

    private transient String defaultKey;

    public AzureArtifactManager(Run<?, ?> build, AzureArtifactConfig config) {
        String containerName = config.getContainer();
        String prefix = config.getPrefix();
        checkConfig(containerName, prefix);

        this.build = build;
        this.config = config;
        onLoad(build);
    }

    private void checkConfig(String containerName, String prefix) {
        boolean isContainerNameValid = Utils.containTokens(containerName) || Utils.validateContainerName(containerName);
        if (!isContainerNameValid) {
            throw new IllegalArgumentException(Messages.AzureArtifactConfig_invalid_container_name(containerName));
        }

        boolean isPrefixValid = Utils.isPrefixValid(prefix);
        if (!isPrefixValid) {
            throw new IllegalArgumentException(Messages.AzureArtifactConfig_invalid_prefix(prefix));
        }
    }

    @Override
    public void onLoad(Run<?, ?> aBuild) {
        this.defaultKey = String.format(Constants.BUILD_PREFIX_FORMAT, aBuild.getParent().getFullName(),
                aBuild.getNumber()).replace("%2F", "/");
    }

    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        if (artifacts.isEmpty()) {
            return;
        }
        LOGGER.fine(Messages.AzureArtifactManager_archive(workspace, artifacts));

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());

        List<UploadObject> objects = new ArrayList<>();

        Map<String, String> contentTypes = workspace
                .act(new ContentTypeGuesser(new ArrayList<>(artifacts.keySet()), listener));

        try {
            BlobContainerClient container = Utils.getBlobContainerReference(accountInfo, config.getContainer(), true);

            for (Map.Entry<String, String> entry : contentTypes.entrySet()) {
                String path = "artifacts/" + entry.getKey();
                String blobPath = getBlobPath(path);

                BlobClient blobClient = container.getBlobClient(blobPath);
                String sas = generateSas(blobClient);
                String blobUrl = blobClient.getBlobUrl() + "?" + sas;

                UploadObject uploadObject = new UploadObject(entry.getKey(), blobUrl, entry.getValue());
                objects.add(uploadObject);
            }

            workspace.act(new UploadToBlobStorage(
                    Jenkins.get().getProxy(),
                    accountInfo.getBlobEndPointURL(),
                    objects,
                    listener
            ));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private String generateSas(BlobClient blobClient) {
        BlobSasPermission permissions = new BlobSasPermission().setWritePermission(true);
        BlobServiceSasSignatureValues sasSignatureValues =
                new BlobServiceSasSignatureValues(generateExpiryDate(), permissions);

        return blobClient.generateSas(sasSignatureValues);
    }

    private String getBlobPath(String path) {
        return getBlobPath(defaultKey, path);
    }

    private String getBlobPath(String key, String path) {
        return String.format("%s%s/%s", config.getPrefix(), key, path);
    }

    private static class ContentTypeGuesser extends MasterToSlaveFileCallable<Map<String, String>> {
        private static final long serialVersionUID = 1L;

        private final Collection<String> relPaths;
        private final TaskListener listener;

        ContentTypeGuesser(Collection<String> relPaths, TaskListener listener) {
            this.relPaths = relPaths;
            this.listener = listener;
        }

        @Override
        public Map<String, String> invoke(File f, VirtualChannel channel) {
            Map<String, String> contentTypes = new HashMap<>();
            for (String relPath : relPaths) {
                File theFile = new File(f, relPath);
                try {
                    String contentType = Files.probeContentType(theFile.toPath());
                    if (contentType == null) {
                        contentType = URLConnection.guessContentTypeFromName(theFile.getName());
                    }
                    contentTypes.put(relPath, contentType);
                } catch (IOException e) {
                    Functions.printStackTrace(e, listener
                            .error("Unable to determine content type for file: " + theFile));
                }
            }
            return contentTypes;
        }
    }

    private static class UploadObject implements Serializable {
        private final String name;
        private final String url;
        private final String contentType;

        UploadObject(
                String name,
                String url,
                String contentType
        ) {
            this.name = name;
            this.url = url;
            this.contentType = contentType;
        }

        public String getName() {
            return name;
        }

        public String getContentType() {
            return contentType;
        }

        public String getUrl() {
            return url;
        }
    }

    private static class UploadToBlobStorage extends MasterToSlaveFileCallable<Void> {

        public static final int MAX_QUEUE_SIZE_IN_NETTY = 500;
        public static final int TIMEOUT = 30;
        private final ProxyConfiguration proxy;
        private final String blobEndpoint;
        private final List<UploadObject> uploadObjects;
        private final TaskListener listener;

        UploadToBlobStorage(
                ProxyConfiguration proxy,
                String blobEndpoint,
                List<UploadObject> uploadObjects,
                TaskListener listener
        ) {
            this.proxy = proxy;
            this.blobEndpoint = blobEndpoint;
            this.uploadObjects = uploadObjects;
            this.listener = listener;
        }

        private BlobServiceAsyncClient getBlobServiceClient(String sas) {
            return new BlobServiceClientBuilder()
                    .credential(new AzureSasCredential(sas))
                    .httpClient(HttpClientRetriever.get(proxy))
                    .endpoint(blobEndpoint)
                    .buildAsyncClient();
        }

        private BlobServiceClient getSynchronousBlobServiceClient(String sas) {
            return new BlobServiceClientBuilder()
                    .credential(new AzureSasCredential(sas))
                    .httpClient(HttpClientRetriever.get(proxy))
                    .endpoint(blobEndpoint)
                    .buildClient();
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) {
            // fall back to sync client when more than 500 files are being uploaded
            // likely less efficient although not really tested for scale yet.
            // https://github.com/jenkinsci/azure-artifact-manager-plugin/issues/26
            if (uploadObjects.size() < MAX_QUEUE_SIZE_IN_NETTY) {
                for (UploadObject uploadObject : uploadObjects) {
                    BlobUrlParts blobUrlParts = BlobUrlParts.parse(uploadObject.getUrl());

                    BlobAsyncClient blobClient = getBlobClient(blobUrlParts);

                    String file = new File(f, uploadObject.getName()).getAbsolutePath();
                    BlobUploadFromFileOptions options = new BlobUploadFromFileOptions(file)
                            .setHeaders(getBlobHttpHeaders(uploadObject));
                    blobClient.uploadFromFileWithResponse(options)
                            .doOnError(throwable -> listener.error("[AzureStorage] Failed to upload file %s, error: %s",
                                    file, throwable.getMessage()))
                            .subscribe();
                }
            } else {
                uploadObjects.parallelStream()
                        .forEach(uploadObject -> {
                            BlobUrlParts blobUrlParts = BlobUrlParts.parse(uploadObject.getUrl());
                            BlobClient blobClient = getSynchronousBlobClient(blobUrlParts);
                            String file = new File(f, uploadObject.getName()).getAbsolutePath();
                            BlobUploadFromFileOptions options = new BlobUploadFromFileOptions(file)
                                    .setHeaders(getBlobHttpHeaders(uploadObject));
                            blobClient.uploadFromFileWithResponse(options, Duration.ofSeconds(TIMEOUT), null);
                        });
            }
            return null;
        }

        private BlobAsyncClient getBlobClient(BlobUrlParts blobUrlParts) {
            String sas = blobUrlParts.getCommonSasQueryParameters().encode();
            BlobServiceAsyncClient blobServiceClient = getBlobServiceClient(sas);

            BlobContainerAsyncClient containerClient = blobServiceClient
                    .getBlobContainerAsyncClient(blobUrlParts.getBlobContainerName());
            return containerClient.getBlobAsyncClient(blobUrlParts.getBlobName());
        }

        private BlobClient getSynchronousBlobClient(BlobUrlParts blobUrlParts) {
            String sas = blobUrlParts.getCommonSasQueryParameters().encode();
            BlobServiceClient blobServiceClient = getSynchronousBlobServiceClient(sas);

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(blobUrlParts.getBlobContainerName());
            return containerClient.getBlobClient(blobUrlParts.getBlobName());
        }

        private BlobHttpHeaders getBlobHttpHeaders(UploadObject uploadObject) {
            BlobHttpHeaders method = new BlobHttpHeaders();
            method.setContentType(uploadObject.getContentType());
            return method;
        }
    }

    private String getActualContainerName(TaskListener listener) throws IOException, InterruptedException {
        EnvVars envVars = build.getEnvironment(listener);
        this.actualContainerName = Utils.replaceMacro(Util.fixNull(config.getContainer()), envVars, Locale.ENGLISH);
        return this.actualContainerName;
    }

    private String getVirtualPath(String path) {
        return getVirtualPath(defaultKey, path);
    }

    private String getVirtualPath(String key, String path) {
        return String.format(Constants.VIRTUAL_PATH_FORMAT, config.getPrefix(), key, path);
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        String virtualPath = getVirtualPath("");
        // TODO check if able to delete artifacts

        try {
            int count = deleteWithPrefix(virtualPath);
            return count > 0;
        } catch (URISyntaxException e) {
            LOGGER.severe(Messages.AzureArtifactManager_delete_fail(e));
            throw new IOException(e);
        }
    }

    private int deleteWithPrefix(String prefix) throws IOException, URISyntaxException, InterruptedException {
        BlobContainerClient container = getContainer();
        ListBlobsOptions listBlobsOptions = new ListBlobsOptions().setPrefix(prefix);
        PagedIterable<BlobItem> listBlobItems = container.listBlobs(listBlobsOptions, null);
        return deleteBlobs(container, listBlobItems);
    }

    private int deleteBlobs(BlobContainerClient container, PagedIterable<BlobItem> blobItems) {
        int count = 0;
        for (BlobItem blobItem : blobItems) {
            BlobClient blobClient = container.getBlobClient(blobItem.getName());
            blobClient.delete();
        }
        return count;
    }

    private BlobContainerClient getContainer() throws IOException,
            URISyntaxException, InterruptedException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        if (StringUtils.isEmpty(this.actualContainerName)) {
            this.actualContainerName = getActualContainerName(new LogTaskListener(LOGGER, Level.INFO));
        }

        return Utils.getBlobContainerReference(
                accountInfo,
                this.actualContainerName,
                false
        );
    }

    @Override
    public VirtualFile root() {
        return new AzureBlobVirtualFile(this.actualContainerName, getVirtualPath("artifacts"), build);
    }

    @Override
    public void stash(@NonNull String name, @NonNull FilePath workspace, @NonNull Launcher launcher,
                      @NonNull EnvVars env, @NonNull TaskListener listener, @CheckForNull String includes,
                      @CheckForNull String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws
            IOException, InterruptedException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());

        UploadServiceData serviceData = new UploadServiceData(build, workspace, launcher, listener, accountInfo);
        FilePath remoteWorkspace = serviceData.getRemoteWorkspace();
        FilePath stashTempFile = remoteWorkspace.child(name + Constants.TGZ_FILE_EXTENSION);
        try {
            int count;
            count = workspace.archive(ArchiverFactory.TARGZ, stashTempFile.write(),
                    new DirScanner.Glob(Util.fixEmpty(includes) == null ? Constants.DEFAULT_INCLUDE_PATTERN : includes,
                            excludeFilesAndStash(excludes, stashTempFile.getName()), useDefaultExcludes));
            if (count == 0 && !allowEmpty) {
                throw new AbortException(Messages.AzureArtifactManager_stash_no_file());
            }
            listener.getLogger().println(Messages.AzureArtifactManager_stash_files(count, this.actualContainerName));

            serviceData.setVirtualPath(getVirtualPath(Constants.STASHES_PATH));
            serviceData.setContainerName(getActualContainerName(listener));
            serviceData.setFilePath(stashTempFile.getName());
            serviceData.setUploadType(UploadType.INDIVIDUAL);

            // TODO rewrite to not use azure-storage plugin API like in artifacts
            UploadService uploadService = new UploadToBlobService(serviceData);
            try {
                uploadService.execute();
            } catch (WAStorageException e) {
                listener.getLogger().println(Messages.AzureArtifactManager_stash_fail(e));
                throw new IOException(e);
            }
        } finally {
            stashTempFile.delete();
            listener.getLogger().println(Messages.AzureArtifactManager_stash_delete(stashTempFile.getName()));
        }
    }

    private String excludeFilesAndStash(String excludes, String stashFile) {
        List<String> strings = Arrays.asList(excludes, stashFile);
        return String.join(Constants.COMMA, strings);
    }

    @Override
    public void unstash(@NonNull String name, @NonNull FilePath workspace, @NonNull Launcher launcher,
                        @NonNull EnvVars env, @NonNull TaskListener listener) throws IOException, InterruptedException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        DownloadServiceData serviceData = new DownloadServiceData(build, workspace, launcher, listener, accountInfo);
        serviceData.setContainerName(getActualContainerName(listener));
        String stashes = getVirtualPath(Constants.STASHES_PATH);
        serviceData.setIncludeFilesPattern(stashes + name + Constants.TGZ_FILE_EXTENSION);
        serviceData.setFlattenDirectories(true);

        workspace.mkdirs();
        DownloadService downloadService = new DownloadFromContainerService(serviceData);
        try {
            downloadService.execute();
        } catch (WAStorageException e) {
            listener.getLogger().println(Messages.AzureArtifactManager_unstash_fail(e));
            throw new IOException(e);
        }

        FilePath[] stashList = workspace.list(name + Constants.TGZ_FILE_EXTENSION);
        if (stashList.length == 0) {
            throw new AbortException(Messages.AzureArtifactManager_unstash_not_found(name,
                    this.actualContainerName, getVirtualPath(Constants.STASHES_PATH)));
        }

        FilePath stashFile = stashList[0];
        workspace.untarFrom(stashFile.read(), FilePath.TarCompression.GZIP);
        stashFile.delete();
        listener.getLogger().println(Messages.AzureArtifactManager_unstash_files(stashFile.getName()));
    }

    @Override
    public void clearAllStashes(@NonNull TaskListener listener) throws IOException, InterruptedException {
        String virtualPath = getVirtualPath(Constants.STASHES_PATH);

        try {
            int count = deleteWithPrefix(virtualPath);
            listener.getLogger().println(Messages.AzureArtifactManager_clear_stash(count, this.actualContainerName));
        } catch (URISyntaxException e) {
            listener.getLogger().println(Messages.AzureArtifactManager_clear_stash_fail(e));
            throw new IOException(e);
        }
    }

    @Override
    public void copyAllArtifactsAndStashes(@NonNull Run<?, ?> to, @NonNull TaskListener listener) throws IOException {
        ArtifactManager artifactManager = to.pickArtifactManager();
        if (!(artifactManager instanceof AzureArtifactManager)) {
            throw new AbortException(Messages.AzureArtifactManager_cannot_copy(to, artifactManager.getClass()
                    .getName()));
        }

        AzureArtifactManager azureArtifactManager = (AzureArtifactManager) artifactManager;
        try {
            int artifactsCount = copyBlobsWithPrefix(Constants.ARTIFACTS_PATH, azureArtifactManager.defaultKey);
            int stashesCount = copyBlobsWithPrefix(Constants.STASHES_PATH, azureArtifactManager.defaultKey);
            listener.getLogger().println(Messages.AzureArtifactManager_copy_all(artifactsCount, stashesCount,
                    this.defaultKey, azureArtifactManager.defaultKey));
        } catch (URISyntaxException | InterruptedException e) {
            listener.getLogger().println(Messages.AzureArtifactManager_copy_all_fail(e));
            throw new IOException(e);
        }
    }

    private int copyBlobs(PagedIterable<BlobItem> sourceBlobs, String toKey, BlobContainerClient container) {
        int count = 0;
        for (BlobItem sourceBlob : sourceBlobs) {
            if (Boolean.TRUE.equals(sourceBlob.isPrefix())) {
                count += copyBlobs(
                        container.listBlobsByHierarchy(sourceBlob.getName()),
                        toKey,
                        container
                );
            } else {
                String destFilePath = sourceBlob.getName().replace(this.defaultKey, toKey);

                BlobClient blobClient = container.getBlobClient(sourceBlob.getName());
                BlobClient destBlob = container.getBlobClient(destFilePath);

                String srcBlobUrl = blobClient.getBlobUrl();
                String srcBlobSas = blobClient.generateSas(Utils.generateBlobPolicy());

                destBlob.copyFromUrl(srcBlobUrl + "?" + srcBlobSas);
                count++;
            }
        }
        return count;
    }

    private int copyBlobsWithPrefix(String prefix, String toKey) throws IOException, URISyntaxException,
            InterruptedException {
        BlobContainerClient container = getContainer();
        String sourcePath = getVirtualPath(prefix);
        PagedIterable<BlobItem> sourceBlobs = container.listBlobsByHierarchy(sourcePath);
        return copyBlobs(sourceBlobs, toKey, container);
    }
}
