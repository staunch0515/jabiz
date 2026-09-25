plugins {
    `java-library`
}

// Spatial and physical-quantity semantic kinds (docs/design/02-metamodel.md section 1.2). Pure Java like core,
// which it extends through the CustomKindSupport SPI; core and runtime never depend on it.
base {
    archivesName.set("jabiz-ext-geo")
}

dependencies {
    api(project(":core"))
}
