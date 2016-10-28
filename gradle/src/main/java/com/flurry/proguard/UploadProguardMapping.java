/*
 * Copyright 2016 Yahoo Inc. All rights reserved.
 */
package com.flurry.proguard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub for the Upload library
 */
public class UploadProguardMapping {
    public static void uploadProguardFile(String apiKey, String token, String uuid, File mappingFile) {
        System.out.println("Uploading uuid: " + uuid + " file: " + mappingFile.getAbsolutePath());
        System.out.println("We've uploaded the file!");
    }

    public static Map<String, String> parseConfigFile(File configFile) {
        Map<String, String> config = new HashMap<>();

        try {
            List<String> text = Files.readAllLines(configFile.toPath());
            text.forEach(line -> {
                if (line.indexOf("=") != -1) {
                    String[] parts = line.split("=");
                    config.put(parts[0], parts[1]);
                }
            });
        } catch (IOException e) {
            System.out.println("Bad config file " + configFile.getAbsolutePath());
        }

        return config;
    }
}
