/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

@Restricted(NoExternalUse.class)
public class AzureArtifactManagerFactory extends ArtifactManagerFactory {
    private final AzureArtifactConfig config;

    @DataBoundConstructor
    public AzureArtifactManagerFactory(AzureArtifactConfig config) {
        if (config == null) {
            throw new IllegalArgumentException();
        }
        this.config = config;
    }

    public AzureArtifactConfig getConfig() {
        return this.config;
    }

    @CheckForNull
    @Override
    public ArtifactManager managerFor(Run<?, ?> build) {
        return new AzureArtifactManager(build, Utils.getArtifactConfig());
    }

    @Extension
    public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Azure Artifact Storage";
        }
    }

}
