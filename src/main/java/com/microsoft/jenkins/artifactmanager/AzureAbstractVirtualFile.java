package com.microsoft.jenkins.artifactmanager;

import jenkins.util.VirtualFile;

import javax.annotation.CheckForNull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public abstract class AzureAbstractVirtualFile extends VirtualFile {
    private static final Logger LOGGER = Logger.getLogger(AzureAbstractVirtualFile.class.getName());

    protected String storageAccountName;
    String storageType;
    String instanceName;

    protected static final ThreadLocal<Map<String, Deque<Cache>>> cache = ThreadLocal.withInitial(HashMap::new);

    protected static final class Cache {
        final String root;
        final Map<String, CachedMetadata> children;

        Cache(String root, Map<String, CachedMetadata> children) {
            this.root = root;
            this.children = children;
        }
    }

    protected static final class CachedMetadata {
        final long length;
        final long lastModified;

        CachedMetadata(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }
    }

    protected Deque<Cache> cacheFrames() {
        return cache.get().computeIfAbsent(instanceName, c -> new ArrayDeque<>());
    }

    protected @CheckForNull
    Cache findCache(String key) {
        return cacheFrames().stream().filter(frame -> key.startsWith(frame.root)).findFirst().orElse(null);
    }
}
