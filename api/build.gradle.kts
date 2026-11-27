plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.openapi.generator")
}

kotlin { jvmToolchain(21) }

// Output lands in build/; `make generate` copies its sources into
// src/main/kotlin so the checked-in client can be diffed for drift.
openApiGenerate {
    generatorName = "kotlin"
    inputSpec = rootProject.file("openapi.yaml")
    outputDir = layout.buildDirectory.dir("openapi")
    cleanupOutput = true
    packageName = "enterprises.legitimate.rabbitears.api"
    library = "jvm-okhttp4"
    configOptions = mapOf("serializationLibrary" to "kotlinx_serialization", "dateLibrary" to "java8")
    // Ids are opaque to the app: kept as the server's canonical text, so routes, saved state and
    // ledgers hold them as they are.
    typeMappings = mapOf("UUID" to "kotlin.String")
    // Untyped in the spec: an SQLite value, which only a JSON element carries as it is.
    schemaMappings = mapOf("SyncScalar" to "kotlinx.serialization.json.JsonElement")
    generateApiDocumentation = false
    generateModelDocumentation = false
    generateApiTests = false
    generateModelTests = false
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.5.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
}
