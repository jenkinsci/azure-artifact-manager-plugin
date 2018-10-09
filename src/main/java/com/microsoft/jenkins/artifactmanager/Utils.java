package com.microsoft.jenkins.artifactmanager;

import hudson.util.DescribableList;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;

public class Utils {
    public static AzureArtifactConfig getArtifactConfig() {
        ArtifactManagerConfiguration artifactManagerConfiguration = ArtifactManagerConfiguration.get();
        DescribableList<ArtifactManagerFactory, ArtifactManagerFactoryDescriptor> artifactManagerFactories =
                artifactManagerConfiguration.getArtifactManagerFactories();
        AzureArtifactManagerFactory azureArtifactManagerFactory = artifactManagerFactories.get(AzureArtifactManagerFactory.class);
        AzureArtifactConfig config = azureArtifactManagerFactory.getConfig();
        return config;
    }
}
