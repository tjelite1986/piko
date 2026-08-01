android {
    namespace = "app.morphe.extension.instagram"

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:instagram:stub"))
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.annotation)
    compileOnly(libs.appcompat)
    // Bundled, not compileOnly: the SFTP client has to ride along in the patched APK.
    implementation(libs.jsch)
}
