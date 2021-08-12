/*
 * Copyright Yahoo Inc. 2016, see https://github.com/flurry/upload-clients/blob/master/LICENSE.txt for full details
 */
package com.flurry.android.symbols

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.flurry.proguard.AndroidUploadType
import com.flurry.proguard.UploadMapping
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.logging.Logger


/**
 * A Gradle plugin that finds the generated ProGuard file and sends it to Flurry's crash service
 */
class SymbolUploadPlugin implements Plugin<Project> {
    public static final String CONFIGURATION_KEY = "flurryCrash"
    public static final String FLURRY_UUID_KEY = "com.flurry.crash.map_id"

    public static final String API_KEY = "api-key"
    public static final String TOKEN = "token"
    public static final String TIMEOUT = "timeout"
    public static final String NDK = "ndk"

    @Override
    void apply(Project project) {
        UploadMapping.logger = project.logger
        getOrCreateConfig(project)

        project.afterEvaluate {
            SymbolUploadConfiguration config = getOrCreateConfig(project)
            Map<String, String> configValues = evaluateConfig(config)
            String apiKey = configValues[API_KEY]
            String token = configValues[TOKEN]
            int timeout = configValues[TIMEOUT].toInteger()
            boolean ndk = configValues[NDK].toBoolean()

            if (!apiKey) {
                throw new IllegalStateException("You must provide the project's API key")
            } else if (!token) {
                throw new IllegalStateException("You must provide a valid token")
            }

            project.android.applicationVariants.all { BaseVariant variant ->
                String uuid = UUID.randomUUID().toString()
                project.logger.lifecycle("Variant=${variant.baseName} UUID=${uuid}")
                variant.resValue "string", FLURRY_UUID_KEY, uuid
                
                if (variant.buildType.isMinifyEnabled()) {
                    String taskSuffix = variant.name.capitalize()
                    String taskName = String.format('uploadProguardMappingFiles%s', taskSuffix)
                    def uploadMappingTask = project.tasks.register(taskName) {
                        doLast {
                            File mappingFile = getMappingFile(variant, project.logger)
                            if (mappingFile != null) {
                                UploadMapping.uploadFiles(apiKey, uuid,
                                        (Collections.singletonList(mappingFile.absolutePath) as List),
                                        token, timeout, AndroidUploadType.ANDROID_JAVA)
                            } else {
                                project.logger.lifecycle("Mapping file not found")
                            }
                        }
                    }

                    // Attaching upload task to code obfuscation related tasks
                    // Referred to sentry plugin for the list of tasks
                    // https://github.com/getsentry/sentry-android-gradle-plugin/blob/fa456d68afa6edb33340030ccf26577d15bdbc47/plugin-build/src/main/kotlin/io/sentry/android/gradle/SentryTasksProvider.kt#L16-L22
                    [
                            "transformClassesAndResourcesWithR8For${taskSuffix}",
                            "transformClassesAndResourcesWithProguardFor${taskSuffix}",
                            "minify${taskSuffix}WithR8",
                            "minify${taskSuffix}WithProguard"
                    ].each{
                        try {
                            project.tasks.getByName(it).finalizedBy(uploadMappingTask)
                        } catch (UnknownTaskException ignored) {
                        }
                    }
                }

                Closure uploadNDKSymbols = {
                    if (ndk) {
                        NdkSymbolUpload.upload(variant, configValues, project.logger)
                    }
                }

                try {
                    variant.getAssembleProvider().configure() {
                        it.doLast {
                            uploadNDKSymbols()
                        }
                    }
                } catch (Throwable ignored) {
                    // The catch block is a fallback in case if the gradle version does not support the Provider API
                    variant.assemble.doLast {
                        uploadNDKSymbols()
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
        configValues[NDK] = config.ndk.toString()
        configValues.put(TIMEOUT, config.uploadTimeout as String)

        if (config.configPath != null) {
            configValues.putAll(UploadMapping.parseConfigFile(config.configPath) as Map<? extends String, ? extends String>)
        }

        return configValues
    }

    /**
     * Returns the mapping file
     * @param variant the ApplicationVariant
     * @return the file or null if not found
     */
    static File getMappingFile(ApplicationVariant variant, Logger logger) {
        try {
            if (variant.getMappingFileProvider() != null && !variant.getMappingFileProvider().get().isEmpty()) {
                return variant.getMappingFileProvider().get().singleFile
            }
        } catch (Exception ignored) {
            logger.lifecycle("Error while accessing mapping file")
            return variant.mappingFile
        }
        return null
    }
}
