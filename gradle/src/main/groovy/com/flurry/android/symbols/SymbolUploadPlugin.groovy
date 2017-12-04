/*
 * Copyright Yahoo Inc. 2016, see https://github.com/flurry/upload-clients/blob/master/LICENSE.txt for full details
 */
package com.flurry.android.symbols

import com.android.build.gradle.api.BaseVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.flurry.proguard.UploadProGuardMapping

/**
 * A Gradle plugin that finds the generated ProGuard file and sends it to Flurry's crash service
 */
class SymbolUploadPlugin implements Plugin<Project> {
    public static final String CONFIGURATION_KEY = "flurryCrash"
    public static final String FLURRY_UUID_KEY = "com.flurry.crash.map_id"

    public static final String API_KEY = "api-key"
    public static final String TOKEN = "token"
    public static final String TIMEOUT = "timeout"

    @Override
    void apply(Project project) {
        UploadProGuardMapping.logger = project.logger
        getOrCreateConfig(project)

        project.afterEvaluate {
            SymbolUploadConfiguration config = getOrCreateConfig(project)
            Map<String, String> configValues = evaluateConfig(config)
            String apiKey = configValues[API_KEY]
            String token = configValues[TOKEN]
            int timeout = configValues[TIMEOUT].toInteger()

            if (!apiKey) {
                throw new IllegalStateException("You must provide the project's API key")
            } else if (!token) {
                throw new IllegalStateException("You must provide a valid token")
            }

            project.android.applicationVariants.all { BaseVariant variant ->
                if (variant.mappingFile) {
                    String uuid = UUID.randomUUID().toString()
                    project.logger.lifecycle("Variant=${variant.baseName} UUID=${uuid}")

                    variant.resValue "string", FLURRY_UUID_KEY, uuid
                    variant.assemble.doFirst {
                        UploadProGuardMapping.uploadFile(apiKey, uuid, variant.mappingFile.absolutePath, token, timeout)
                    }
                }
            }
        }
    }

    /**
     * Creates a configuration container in the project for Gradle to populate
     *
     * @param target the project to find/create the configuration in
     * @return the project's configuration container
     */
    private static SymbolUploadConfiguration getOrCreateConfig(Project target) {
        SymbolUploadConfiguration config = target.extensions.findByType(SymbolUploadConfiguration)
        if (!config) {
            config = target.extensions.create(CONFIGURATION_KEY, SymbolUploadConfiguration)
        }
        return config
    }

    private static Map<String, String> evaluateConfig(SymbolUploadConfiguration config) {
        Map<String, String> configValues = new HashMap<>()

        if (config.apiKey) {
            configValues[API_KEY] = config.apiKey
        }
        if (config.token) {
            configValues[TOKEN] = config.useEnvVar ? System.getenv(config.token) : config.token
        }
        configValues.put(TIMEOUT, config.uploadTimeout as String)

        if (config.configPath) {
            configValues.putAll(UploadProGuardMapping.parseConfigFile(config.configPath) as Map<? extends String, ? extends String>)
        }

        return configValues
    }
}
