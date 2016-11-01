/*
 * Copyright Yahoo Inc. 2016, see https://github.com/flurry/upload-clients/blob/master/LICENSE.txt for full details
 */
package com.flurry.android.symbols

/**
 * Holds the user configurable values
 */
class SymbolUploadConfiguration {
    String apiKey = null
    String token = null
    boolean useEnvVar = true
    String configPath = null

    public void apiKey(String apiKey) {
        this.apiKey = apiKey
    }

    public void token(String token) {
        this.token = token
    }

    public void useEnvironmentVariable(boolean useEnv) {
        this.useEnvVar = useEnv
    }

    public void configPath(String configPath) {
        this.configPath = configPath
    }
}
