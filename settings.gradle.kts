pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    // PREFER_SETTINGS：Flutter Gradle 插件会向 :flutter 项目注入自己的 maven 仓库
    // （flutter embedding 等预编译产物），FAIL_ON_PROJECT_REPOS 会拒绝该注入。
    // 放宽为 PREFER_SETTINGS 后 settings 仓库仍然优先，本项目依赖解析不受影响。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Flutter 引擎制品（io.flutter:*，版本号带 engine hash）只发布在
        // download.flutter.io，google/mavenCentral 均没有。
        // storage.flutter-io.cn 是其国内镜像（下载快），官方源兜底。
        maven { url = uri("https://storage.flutter-io.cn/download.flutter.io") }
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
    }
}
rootProject.name = "MoodDiary"
include(":app")

// 导航栏 Flutter 模块（混编）。include_flutter.groovy 由 `flutter create -t module`
// 生成：注册 :flutter 子项目并加载 Flutter Gradle 插件（含 native plugin 注册）。
// 注意：脚本依赖 flutter_module/.android/local.properties 中的 flutter.sdk 路径，
// 克隆本仓库后需先执行一次 `flutter pub get`（见 README「Flutter 混编」一节）。
apply(from = File(rootDir, "flutter_module/.android/include_flutter.groovy"))
