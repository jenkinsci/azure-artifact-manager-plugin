/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.google.common.collect.Lists;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
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
import hudson.util.io.ArchiverFactory;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Restricted(NoExternalUse.class)
public final class AzureArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {
    private static final Logger LOGGER = Logger.getLogger(ArtifactManager.class.getName());
    private Run<?, ?> build;
    private AzureArtifactConfig config;

    private transient String key;

    public AzureArtifactManager(Run<?, ?> build, AzureArtifactConfig config) {
        this.build = build;
        this.config = config;
        onLoad(build);
    }

    @Override
    public void onLoad(Run<?, ?> build) {
        this.key = String.format("%s/%s", build.getParent().getFullName(), build.getNumber());
    }

    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        if (artifacts.isEmpty()) {
            return;
        }
        LOGGER.fine(Messages.AzureArtifactManager_archive(workspace, artifacts));

        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        List<String> filepath = new ArrayList<>();
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            filepath.add(entry.getValue());
        }
        String filespath = String.join(",", filepath);

        UploadServiceData serviceData = new UploadServiceData(build, workspace, launcher, listener, accountInfo);
        serviceData.setVirtualPath(getVirtualPath("artifacts/"));
        serviceData.setContainerName(config.getContainer());
        serviceData.setFilePath(filespath);
        serviceData.setUploadType(UploadType.INDIVIDUAL);


        UploadService uploadService = new UploadToBlobService(serviceData);
        try {
            uploadService.execute();
        } catch (WAStorageException e) {
            e.printStackTrace();
        }
    }

    private String getVirtualPath(String path) {
        return getVirtualPath(key, path);
    }

    private String getVirtualPath(String key, String path) {
        return String.format("%s%s/%s", config.getPrefix(), key, path);
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        String virtualPath = getVirtualPath("");
        // TODO check if able to delete artifacts

        try {
            int count = deleteWithPrefix(virtualPath);
            return count > 0;
        } catch (URISyntaxException | StorageException e) {
            e.printStackTrace();
        }
        return false;
    }

    private int deleteWithPrefix(String prefix) throws IOException, URISyntaxException, StorageException {
        CloudBlobContainer container = getContainer();
        Iterable<ListBlobItem> listBlobItems = container.listBlobs(prefix);
        return deleteBlobs(listBlobItems);
    }

    private int deleteBlobs(Iterable<ListBlobItem> blobItems)
            throws StorageException, URISyntaxException, IOException {
        int count = 0;
        for (ListBlobItem blobItem : blobItems) {
            if (blobItem instanceof CloudBlob) {
                ((CloudBlob) blobItem).uploadProperties(null, null,
                        Utils.updateUserAgent());
                ((CloudBlob) blobItem).delete();
                count++;
            } else if (blobItem instanceof CloudBlobDirectory) {
                deleteBlobs(((CloudBlobDirectory) blobItem).listBlobs());
            }
        }
        return count;
    }

    private CloudBlobContainer getContainer() throws StorageException, IOException, URISyntaxException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());

        CloudBlobContainer container = Utils.getBlobContainerReference(
                accountInfo,
                config.getContainer(),
                true,
                true,
                false);
        return container;
    }

    @Override
    public VirtualFile root() {
        return new AzureBlobVirtualFile(config.getContainer(), getVirtualPath("artifacts"), build);
    }

    @Override
    public void stash(@Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener, @CheckForNull String includes, @CheckForNull String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws IOException, InterruptedException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());

        UploadServiceData serviceData = new UploadServiceData(build, workspace, launcher, listener, accountInfo);
        FilePath remoteWorkspace = serviceData.getRemoteWorkspace();
        FilePath stashTempFile = remoteWorkspace.child(name + ".tgz");
        try {
            int count;
            count = workspace.archive(ArchiverFactory.TARGZ, stashTempFile.write(),
                    new DirScanner.Glob(Util.fixEmpty(includes) == null ? "**" : includes,
                            excludeFilesAndStash(excludes, stashTempFile.getName()), useDefaultExcludes));
            if (count == 0 && !allowEmpty) {
                throw new AbortException(Messages.AzureArtifactManager_stash_no_file());
            }
            listener.getLogger().println(Messages.AzureArtifactManager_stash_files(count, null));

            serviceData.setVirtualPath(getVirtualPath("stashes/"));
            serviceData.setContainerName(config.getContainer());
            serviceData.setFilePath(stashTempFile.getName());
            serviceData.setUploadType(UploadType.INDIVIDUAL);

            UploadService uploadService = new UploadToBlobService(serviceData);
            try {
                uploadService.execute();
            } catch (WAStorageException e) {
                e.printStackTrace();
            }
        } finally {
            stashTempFile.delete();
            listener.getLogger().println(Messages.AzureArtifactManager_stash_delete(stashTempFile.getName()));
        }
    }

    private String excludeFilesAndStash(String excludes, String stashFile) {
        List<String> strings = Lists.asList(excludes, new String[]{stashFile});
        return String.join(",", strings);
    }

    @Override
    public void unstash(@Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        StorageAccountInfo accountInfo = Utils.getStorageAccount(build.getParent());
        DownloadServiceData serviceData = new DownloadServiceData(build, workspace, launcher, listener, accountInfo);
        serviceData.setContainerName(config.getContainer());
        String stashes = getVirtualPath("stashes/");
        serviceData.setIncludeFilesPattern(stashes + name + ".tgz");
        serviceData.setFlattenDirectories(true);

        DownloadService downloadService = new DownloadFromContainerService(serviceData);
        try {
            downloadService.execute();
        } catch (WAStorageException e) {
            e.printStackTrace();
        }

        FilePath[] stashList = workspace.list(name + ".tgz");
        if (stashList.length == 0) {
            throw new AbortException(Messages.AzureArtifactManager_unstash_not_found(name,
                    config.getContainer(), getVirtualPath("stashes")));
        }

        FilePath stashFile = stashList[0];
        workspace.untarFrom(stashFile.read(), FilePath.TarCompression.GZIP);
        stashFile.delete();
        listener.getLogger().println(Messages.AzureArtifactManager_unstash_files(stashFile.getName()));
    }

    @Override
    public void clearAllStashes(@Nonnull TaskListener listener) throws IOException, InterruptedException {
        String virtualPath = getVirtualPath("stashes/");
        // TODO check

        try {
            int count = deleteWithPrefix(virtualPath);
            listener.getLogger().println(Messages.AzureArtifactManager_clear_stash(count, ""));
        } catch (URISyntaxException | StorageException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void copyAllArtifactsAndStashes(@Nonnull Run<?, ?> to, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        ArtifactManager artifactManager = to.pickArtifactManager();
        if (!(artifactManager instanceof AzureArtifactManager)) {
            throw new AbortException(Messages.AzureArtifactManager_cannot_copy(to, artifactManager.getClass().getName()));
        }

//        AzureArtifactManager azureArtifactManager = (AzureArtifactManager) artifactManager;
        String virtualPath = getVirtualPath("");
//        String destVirtualPath = getVirtualPath(azureArtifactManager.key, "");
//        int count = 0;

        try {
            CloudBlobContainer container = getContainer();
            Iterable<ListBlobItem> listBlobItems = container.listBlobs(virtualPath);
            for (ListBlobItem blob : listBlobItems) {
                URI uri = blob.getUri();
                CloudBlockBlob blockBlobReference = container.getBlockBlobReference("");
                blockBlobReference.startCopy(uri);
//                count++;
            }
        } catch (StorageException | URISyntaxException e) {
            e.printStackTrace();
        }
    }
}
