package com.microsoft.jenkins.artifactmanager;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class AzureBlobVirtualFileTest {
    @Test
    public void testGetRelativePath() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        AzureBlobVirtualFile azureBlobVirtualFile = new AzureBlobVirtualFile("", "", null);
        Method method = AzureBlobVirtualFile.class.getDeclaredMethod("getRelativePath", String.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(azureBlobVirtualFile, "http://a.com/sub/a.txt", "sub/");
        assertEquals("sub/a.txt", result);
        result = (String) method.invoke(azureBlobVirtualFile, "http://a.com/sub/", "sub/");
        assertEquals("sub", result);
    }
}
