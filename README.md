# Flurry Upload Clients

Flurry's Crash service can symbolicate the crashes reported by Flurry's SDK.
This script uploads the symbols required to properly symbolicate crashes from
iOS applications.

## How to send iOS symbols

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

Now whenever you build your application you will upload symbols to Flurry's symbolication service. _NB:_ if you are
compiling your applications for the app store with BitCode enabled you will need to download your dSYMs from iTunesConnect
after you have submitted your build. Instuctions for submitting these symbols are forthcoming.
