package com.flurry.proguard;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

/**
 * Test uploads
 */
public class UploadMappingTest {
    private static final String FLURRY_TOKEN = "FLURRY_TOKEN";
    private static final String API_KEY = "API_KEY";

    @Test
    public void testParseConfigFile() {
        String path = getResourcePath("flurry.config");
        Properties vals = UploadMapping.parseConfigFile(path);

        Assert.assertEquals(vals.get("api-key"), "FOO_API_KEY");
        Assert.assertEquals(vals.get("token"), "FOO_TOKEN");
        Assert.assertEquals(vals.get("timeout"), "60000");
    }

    @Test
    public void testUploadFile() throws IOException {
        String apiKey = System.getenv(API_KEY);
        String uuid = UUID.randomUUID().toString();
        String path = getResourcePath("mapping.txt");
        String token = System.getenv(FLURRY_TOKEN);

        UploadMapping.uploadFiles(apiKey, uuid, Collections.singletonList(path), token,
                UploadMapping.ONE_MINUTE_IN_MS, AndroidUploadType.ANDROID_JAVA);
    }

    private String getResourcePath(String resource) {
        return UploadMappingTest.class.getClassLoader().getResource(resource).getPath();
    }
}
