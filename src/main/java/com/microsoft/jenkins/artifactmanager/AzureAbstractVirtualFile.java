/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import jenkins.util.VirtualFile;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.Serial;

@Restricted(NoExternalUse.class)
public abstract class AzureAbstractVirtualFile extends VirtualFile {
    @Serial
    private static final long serialVersionUID = 232262110935432273L;
}
