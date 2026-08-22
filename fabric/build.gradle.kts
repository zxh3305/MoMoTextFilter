plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
}

repositories {
	mavenCentral()
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("momotextfilter") {
			sourceSet(sourceSets.main.get())
		}
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

	// SnakeYAML for YAML config parsing
	implementation("org.yaml:snakeyaml:2.2")
	include("org.yaml:snakeyaml:2.2")

	// LuckPerms API (optional, for permission integration)
	compileOnly("net.luckperms:api:5.4")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.encoding = "UTF-8"
}

java {
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
	repositories {
	}
}