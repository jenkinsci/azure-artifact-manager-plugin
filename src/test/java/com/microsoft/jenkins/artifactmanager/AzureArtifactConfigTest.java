package com.microsoft.jenkins.artifactmanager;

import hudson.util.FormValidation;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;

public class AzureArtifactConfigTest {
    AzureArtifactConfig.DescriptorImpl descriptor;

    @Before
    public void init() {
        descriptor = Mockito.mock(AzureArtifactConfig.DescriptorImpl.class);
        Mockito.when(descriptor.doCheckContainer(any())).thenCallRealMethod();
    }


    @Test
    public void testContainerName() throws Exception {

        // checking for container name length of 3 characters
        assertEquals(FormValidation.ok(), descriptor.doCheckContainer("abc"));

        // checking for container name length of 5 characters
        assertEquals(FormValidation.ok(), descriptor.doCheckContainer("1abc3"));

        // checking for container name length of 63 characters
        assertEquals(FormValidation.ok(), descriptor.doCheckContainer("abcde12345abcde12345abcde12345abcde12345abcde12345abcde12345abc"));

        // checking for container name with dash (-) characters
        assertEquals(FormValidation.ok(), descriptor.doCheckContainer("abc-def"));

        // checking for special container name
        assertEquals(FormValidation.ok(), descriptor.doCheckContainer("$root"));
        assertEquals(FormValidation.ok(), descriptor.doCheckContainer("${JOB_NAME}"));

        // Negative case : consecutive dashes are not allowed
        assertNotEquals(FormValidation.ok(), descriptor.doCheckContainer("abc--def"));

        // Negative case : dash canot be first character
        assertNotEquals(FormValidation.ok(), descriptor.doCheckContainer("-abc12def"));

        // Negative case : dash canot be last character
        assertNotEquals(FormValidation.ok(), descriptor.doCheckContainer("abc12def-"));

        // Negative case : more than 63 characters
        assertNotEquals(FormValidation.ok(), descriptor.doCheckContainer("abcde12345abcde12345abcde12345abcde12345abcde12345abcde12345abc34"));

        // Negative case : only 2 characters
        assertNotEquals(FormValidation.ok(), descriptor.doCheckContainer("ab"));
    }
}
