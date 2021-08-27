package com.flurry.proguard;

public enum AndroidUploadType {
    ANDROID_JAVA("ANDROID", "ProGuard"),
    ANDROID_NATIVE("ANDROID_NATIVE", "Native")
    ;

    private final String uploadType;
    private final String displayName;

    AndroidUploadType(String uploadType, String displayName) {
        this.uploadType = uploadType;
        this.displayName = displayName;
    }

    public String getUploadType() {
        return uploadType;
    }

    public String getDisplayName() {
        return displayName;
    }

}
