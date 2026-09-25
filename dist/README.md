# dist/

此目录曾直接提交构建好的 APK，导致仓库体积膨胀到 58 MB。
APK 现已改为通过 [GitHub Releases](../../releases) 分发，不再进入版本库。

历史版本仍可在 Releases 页面找到，
或从对应的 tag（`v1.2.2`、`v1.3.0`）检出源码后用 `./gradlew assembleRelease` 自行构建。
