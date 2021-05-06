/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

public final class Constants {
    public static final String ARTIFACTS_PATH = "artifacts/";
    public static final String STASHES_PATH = "stashes/";
    public static final String TGZ_FILE_EXTENSION = ".tgz";
    public static final String AZURE_STORAGE_DISPLAY_NAME = "Azure Blob Storage";
    public static final String BUILD_PREFIX_FORMAT = "%s/%s";
    public static final String VIRTUAL_PATH_FORMAT = "%s%s/%s";
    public static final String COMMA = ",";
    public static final String FORWARD_SLASH = "/";
    public static final String EMPTY_STRING = "";
    public static final String DEFAULT_INCLUDE_PATTERN = "**";

    private Constants() {
    }
}
