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
