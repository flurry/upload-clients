/*
 * Copyright Yahoo Inc. 2016, see https://github.com/flurry/upload-clients/blob/master/LICENSE.txt for full details
 */
package com.flurry.android.symbols

/**
 * Holds the user configurable values
 */
class SymbolUploadConfiguration {
    String apiKey
    String token
    boolean useEnvVar = true
    String configPath
    int uploadTimeout = 300

    void apiKey(String apiKey) {
        this.apiKey = apiKey
    }

    void token(String token) {
        this.token = token
    }

    void useEnvironmentVariable(boolean useEnv) {
        this.useEnvVar = useEnv
    }

    void configPath(String configPath) {
        this.configPath = configPath
    }

    void uploadTimeout(int uploadTimeout) {
        this.uploadTimeout = uploadTimeout
    }

    @Override
    String toString() {
        return "SymbolUploadConfiguration{" +
                "apiKey='" + apiKey + '\'' +
                ", token='" + token + '\'' +
                ", useEnvVar=" + useEnvVar +
                ", configPath='" + configPath + '\'' +
                ", uploadTimeout=" + uploadTimeout +
                '}'
    }
}
