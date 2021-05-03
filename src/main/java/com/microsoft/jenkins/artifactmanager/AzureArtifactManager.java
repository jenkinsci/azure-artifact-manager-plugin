/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.google.common.collect.Lists;
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
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.DirScanner;
import hudson.util.LogTaskListener;
import hudson.util.io.ArchiverFactory;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public final class AzureArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {
    private static final Logger LOGGER = Logger.getLogger(ArtifactManager.class.getName());
    private Run<?, ?> build;
    private AzureArtifactConfig config;
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
                aBuild.getNumber());
    }

    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        if (artifacts.isEmpty()) {
            return;
        }
        LOGGER.fine(Messages.AzureArtifactManager_archive(workspace, artifacts));

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        List<String> filePath = new ArrayList<>();
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            filePath.add(entry.getValue());
        }
        String filesPath = String.join(Constants.COMMA, filePath);

        UploadServiceData serviceData = new UploadServiceData(build, workspace, launcher, listener, accountInfo);
        serviceData.setVirtualPath(getVirtualPath(Constants.ARTIFACTS_PATH));
        serviceData.setContainerName(getActualContainerName(listener));
        serviceData.setFilePath(filesPath);
        serviceData.setUploadType(UploadType.INDIVIDUAL);

        UploadService uploadService = new UploadToBlobService(serviceData);
        try {
            uploadService.execute();
        } catch (WAStorageException e) {
            listener.getLogger().println(Messages.AzureArtifactManager_archive_fail(e));
            throw new IOException(e);
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
        PagedIterable<BlobItem> listBlobItems = container.listBlobsByHierarchy(prefix);
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
                true
        );
    }

    @Override
    public VirtualFile root() {
        return new AzureBlobVirtualFile(this.actualContainerName, getVirtualPath("artifacts"), build);
    }

    @Override
    public void stash(@Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                      @Nonnull EnvVars env, @Nonnull TaskListener listener, @CheckForNull String includes,
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
        List<String> strings = Lists.asList(excludes, new String[]{stashFile});
        return String.join(Constants.COMMA, strings);
    }

    @Override
    public void unstash(@Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull EnvVars env, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        DownloadServiceData serviceData = new DownloadServiceData(build, workspace, launcher, listener, accountInfo);
        serviceData.setContainerName(getActualContainerName(listener));
        String stashes = getVirtualPath(Constants.STASHES_PATH);
        serviceData.setIncludeFilesPattern(stashes + name + Constants.TGZ_FILE_EXTENSION);
        serviceData.setFlattenDirectories(true);

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
    public void clearAllStashes(@Nonnull TaskListener listener) throws IOException, InterruptedException {
        String virtualPath = getVirtualPath(Constants.STASHES_PATH);
        // TODO check

        try {
            int count = deleteWithPrefix(virtualPath);
            listener.getLogger().println(Messages.AzureArtifactManager_clear_stash(count, this.actualContainerName));
        } catch (URISyntaxException e) {
            listener.getLogger().println(Messages.AzureArtifactManager_clear_stash_fail(e));
            throw new IOException(e);
        }
    }

    @Override
    public void copyAllArtifactsAndStashes(@Nonnull Run<?, ?> to, @Nonnull TaskListener listener) throws IOException {
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

            // TODO figure this out
            System.out.println(sourceBlob);
//            if (sourceBlob instanceof CloudBlob) {
//                URI uri = sourceBlob.getName();
//                String path = uri.getPath();
//                String sourceFilePath = path.substring(this.actualContainerName.length() + 2);
//                String destFilePath = sourceFilePath.replace(this.defaultKey, toKey);
//                BlobClient destBlob = container.getBlobClient(destFilePath);
//                destBlob.copyFromUrl(uri);
//                count++;
//            } else if (sourceBlob instanceof CloudBlobDirectory) {
//                count += copyBlobs(((CloudBlobDirectory) sourceBlob).listBlobs(), toKey, container);
//            }
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
