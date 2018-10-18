/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.microsoftopentechnologies.windowsazurestorage.helper.AzureCredentials;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.Serializable;
import java.util.Collections;

@Extension
public class AzureArtifactConfig implements ExtensionPoint, Serializable, Describable<AzureArtifactConfig> {
    private String storageCredentialId;
    private String container;
    private String prefix;

    public AzureArtifactConfig() {
    }

    @DataBoundConstructor
    public AzureArtifactConfig(String storageCredentialId) {
        this.storageCredentialId = storageCredentialId;
    }

    public String getContainer() {
        return container;
    }

    @DataBoundSetter
    public void setContainer(String container) {
        this.container = container;
    }

    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getStorageCredentialId() {
        return this.storageCredentialId;
    }

    public static AzureArtifactConfig get() {
        return ExtensionList.lookupSingleton(AzureArtifactConfig.class);
    }

    @Override
    public Descriptor<AzureArtifactConfig> getDescriptor() {
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance == null) {
            return null;
        }
        return instance.getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureArtifactConfig> {
        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            save();
            return super.configure(req, json);
        }

        @Override
        public String getDisplayName() {
            return "Azure Storage";
        }

        public ListBoxModel doFillStorageCredentialIdItems(@AncestorInPath Item owner) {
            ListBoxModel m = new StandardListBoxModel().withAll(
                    CredentialsProvider.lookupCredentials(
                            AzureCredentials.class,
                            owner,
                            ACL.SYSTEM,
                            Collections.emptyList()));
            return m;
        }
    }
}
