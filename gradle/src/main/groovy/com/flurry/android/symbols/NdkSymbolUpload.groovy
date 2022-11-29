/*
 * Copyright Yahoo Inc. 2017, see https://github.com/flurry/upload-clients/blob/master/LICENSE.txt for full details
 */
package com.flurry.android.symbols

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.tasks.ExternalNativeBuildTask
import com.flurry.proguard.AndroidUploadType
import com.flurry.proguard.UploadMapping
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger

import static groovy.io.FileType.FILES

/*
 * Finds the generated Native shared object files and sends them to Flurry's crash service
 *
 * Portions of this file are taken from Bugsnag-android-gradle-plugin which has the following license:
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Bugsnag
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

class NdkSymbolUpload {

    static void upload(BaseVariant variant, Map<String, String> configValues, Logger logger) {
        ArrayList<String> files = new ArrayList<>()
        Closure addSharedObjectFiles = { File sharedObjectFile ->
            logger.lifecycle("Found .so file: " + sharedObjectFile.parentFile.name + "/" + sharedObjectFile.name)
            files.add(sharedObjectFile.absolutePath)
        }

        Closure searchForSharedObjectFiles = { ExternalNativeBuildTask task ->
            if (task.objFolder instanceof DirectoryProperty) {
                findSharedObjectFiles(task.objFolder.asFileTree.files, addSharedObjectFiles)
                findSharedObjectFiles(task.soFolder.asFileTree.files, addSharedObjectFiles)
            }
            else {
                findSharedObjectFiles(Set.of(task.objFolder), addSharedObjectFiles)
                findSharedObjectFiles(Set.of(task.soFolder), addSharedObjectFiles)
            }
        }

        variant.getExternalNativeBuildProviders().each {
            it.configure() {
                searchForSharedObjectFiles(it)
            }
        }
        
        if (files.size() > 0) {
            String apiKey = configValues[SymbolUploadPlugin.API_KEY]
            String token = configValues[SymbolUploadPlugin.TOKEN]
            int timeout = configValues[SymbolUploadPlugin.TIMEOUT].toInteger()

            UploadMapping.uploadFiles(apiKey, null, files, token, timeout,
                    AndroidUploadType.ANDROID_NATIVE)
        }
    }

    private static void findSharedObjectFiles(Set<File> fileSet, Closure processor) {
        if (fileSet == null) {
            return;
        }
        for(File file: fileSet) {
            if (file.exists()) {
                if (file.isDirectory()) {
                    file.eachDir { dir ->
                        dir.eachFileMatch(FILES, ~/.*\.so$/, { processor(it) })
                    }
                }
                else {
                    if (file.getName().endsWith(".so")) {
                        processor(file);
                    }
                }
            }
        }
    }
}
