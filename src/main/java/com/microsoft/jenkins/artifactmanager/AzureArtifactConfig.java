/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.jenkins.artifactmanager;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.microsoftopentechnologies.windowsazurestorage.helper.AzureStorageAccount;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;

@Extension
public class AzureArtifactConfig extends AbstractDescribableImpl<AzureArtifactConfig> implements Serializable {
    @Serial
    private static final long serialVersionUID = -3283542207832596121L;
    private String storageCredentialId;
    private String container;
    private String prefix;
    private boolean disableExternalUrl;

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

    public boolean getDisableExternalUrl() {
        return disableExternalUrl;
    }

    @DataBoundSetter
    public void setDisableExternalUrl(boolean disableExternalUrl) {
        this.disableExternalUrl = disableExternalUrl;
    }

    public String getStorageCredentialId() {
        return this.storageCredentialId;
    }

    public static AzureArtifactConfig get() {
        return ExtensionList.lookupSingleton(AzureArtifactConfig.class);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureArtifactConfig> {
        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
            save();
            return super.configure(req, json);
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Constants.AZURE_STORAGE_DISPLAY_NAME;
        }

        public ListBoxModel doFillStorageCredentialIdItems(@AncestorInPath Item item) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(get().getStorageCredentialId());
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ)
                        && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(get().getStorageCredentialId());
                }
            }
            return result
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            AzureStorageAccount.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(
                                    AzureStorageAccount.class))
                    .includeCurrentValue(get().getStorageCredentialId());
        }

        public FormValidation doCheckContainer(@QueryParameter String container) {
            boolean isValid = Utils.containTokens(container) || Utils.validateContainerName(container);
            if (!isValid) {
                return FormValidation.error(Messages.AzureArtifactConfig_invalid_container_name(container));
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckPrefix(@QueryParameter String prefix) {
            boolean isValid = Utils.isPrefixValid(prefix);
            if (!isValid) {
                return FormValidation.error(Messages.AzureArtifactConfig_invalid_prefix(prefix));
            }
            return FormValidation.ok();
        }
    }
}
