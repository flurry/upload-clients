## Releasing new versions of this plugin

First you must publish the `proguard` project. This requires that you have the
`FLURRY_TOKEN` and `API_KEY` variables set.
* `FLURRY_TOKEN` is a flurry auth token
* `API_KEY` is the api key of a project that `FLURRY_TOKEN` has access to

You should will also need the following properties for the publish to work, they
can be set either in `~/.gradle/gradle.properties` or as arguments to your build.
* `bintray.api.user` - your bintray username
* `bintray.api.key` - your bintray api key

```
cd proguard
./gradlew bintrayUpload
# go to https://bintray.com/yahoo/maven/flurry-android-proguard-core/VERSION
# click publish
cd ../gradle
./gradlew bintrayUpload publishPlugins
# go to https://bintray.com/yahoo/maven/flurry-android-proguard-plugin
# click publish
```
