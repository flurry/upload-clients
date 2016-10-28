package com.flurry.android.symbols

import com.android.build.gradle.api.BaseVariant
import com.flurry.proguard.UploadProguardMapping
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A Gradle plugin that finds the generated ProGuard file and sends it to Flurry's crash service
 */
class SymbolUploadPlugin implements Plugin<Project> {
    public static final String CONFIGURATION_KEY = "flurryCrash"
    public static final String API_KEY = "api-key"
    public static final String TOKEN = "token"
    public static final String FLURRY_UUID_KEY = "com.flurry.crash.map_id"

    @Override
    void apply(Project project) {
        getOrCreateConfig(project)

        project.afterEvaluate {
            SymbolUploadConfiguration config = getOrCreateConfig(project)
            Map<String, String> configValues = evaluateConfig(config)

            project.android.applicationVariants.all { BaseVariant variant ->
                if (variant.mappingFile) {
                    String uuid = UUID.randomUUID().toString()
                    project.logger.lifecycle("Variant=${variant.baseName} UUID=${uuid}")

                    variant.resValue "string", FLURRY_UUID_KEY, uuid
                    variant.assemble.doFirst {
                        UploadProguardMapping.uploadProguardFile(configValues.get(API_KEY), configValues.get(TOKEN), uuid, variant.mappingFile)
                    }
                }
            }
        }
    }

    private SymbolUploadConfiguration getOrCreateConfig(Project target) {
        SymbolUploadConfiguration config = target.extensions.findByType(SymbolUploadConfiguration.class)
        if (config == null) {
            config = target.extensions.create(CONFIGURATION_KEY, SymbolUploadConfiguration.class)
        }
        return config
    }

    private Map<String, String> evaluateConfig(SymbolUploadConfiguration config) {
        if (config.configPath != null) {
            File configFile = new File(config.configPath)
            return UploadProguardMapping.parseConfigFile(configFile)
        } else {
            Map<String, String> configValues = new HashMap<>();

            configValues.put(API_KEY, config.apiKey);
            configValues.put(TOKEN, config.useEnvVar ? System.getenv(config.token) : config.token)

            return configValues;
        }
    }
}
