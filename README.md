# Flurry Upload Clients

Flurry's Crash service can symbolicate the crashes reported by Flurry's SDK.
This repository contains code to uploads the symbols required to properly symbolicate
crashes from iOS apps and deobfuscate Android apps with ProGuard obfuscation. Both of these clients require
[programmatic access keys][programmatic-access], these keys are **NOT** the same credentials
that were previously used to access the apis from [api.flurry.com](api.flurry.com).

## How to send iOS symbols at build time

1. Ensure that your project is configured to build dSYM bundles
  ![dSYM setting](instructions/build-dsym-setting.png)
1. Copy the python script at `xcode/upload-symbols.py` to the root of your project directory
1. In XCode add a `Run Script` build phase
  ![XCode build configuration](instructions/xcode-phases.png)
1. Add a configuration file in the root of your project `flurry.config`. Contents:

  ```
  [flurry]
  token=TOKEN
  api-key=API_KEY
  ```
1. Configure the build phase. You can find your API key in the Flurry Developer portal or in you `AppDelegate`
  ```
  ./upload-symbols.py -c flurry.config
  ```
  ![Job configuration](instructions/job-config.png)

Now whenever you build your application you will upload symbols to Flurry's symbolication service. If you wish
you can configure your symbols to be sent only when you build an archive of your project; this is achieved by checking
the _Run script only when installing` checkbox in the configuration.

## How to send iOS symbols for a BitCode enabled app

The recommended approach is to use the Flurry FastLane plugin.

1. Download and Install [Fastlane](https://github.com/fastlane/fastlane)
1. Add the [Flurry Fastlane Plugin](https://github.com/flurry/fastlane-plugin-flurry) to your project.
1. Configure your [Fastfile](https://github.com/flurry/fastlane-plugin-flurry#example-fastfile)

If you choose not to use FastLane, this can be done manually through the following steps:

1. Download symbols from iTunesConnect
  - Go to iTunesConnect
  ![Developer Account](instructions/bitcode-connect.png)
  - Go to _My Apps_
  - Select the app you want symbols for
  - Inspect the current version
  ![Version select](instructions/bitcode-version.png)
  - Open the build
  ![Build select](instructions/bitcode-build.png)
  - Download the dsyms
  ![dSYMs download](instructions/bitcode-dsyms.png)
1. Run the script using the `-p <path to downloaded file>` argument. eg.
```
./upload-symbols.py -c flurry.config -p ~/Downloads/dSYMs.zip
```

## How to send ProGuard mapping files at build time

*Note*: If you have ProGuard enabled and you do not send your mapping file at build time then you must upload the
generated `mapping.txt` file manually before any stack traces received from that version of your app can be deobfuscated.

1. Install Flurry SDK 6.7.0 or greater.
1. Apply the Flurry android crash plugin to your app's build
  ```
  buildscript {
    repositories {
      maven {
        url "https://plugins.gradle.org/m2/"
      }
      maven {
        url  "http://yahoo.bintray.com/maven"
      }
    }
    dependencies {
      classpath "gradle.plugin.com.flurry:symbol-upload:1.0.0"
    }
  }

  apply plugin: 'com.flurry.android.symbols'
  
  ...

  flurryCrash {
    <configuration>
  }
  ```
1. Configure the crash plugin. You may provide either `configPath` or `apiKey` *and* `token`
  - `configPath "<the path to the flurry.config file described above>"`
  - `apiKey "<the api key used to initialize the SDK>"`
  - `token "<An environment variable to read the token from>"`
  - `useEnvironmentVariable (true|false)` the default for `useEnvironmentVariable` is `true` you can set it to `false`
    if you want to inline your [Programmatic Token][programmatic-access], though this is not recommended.

[programmatic-access]: https://developer.yahoo.com/flurry/docs/api/code/apptoken/
