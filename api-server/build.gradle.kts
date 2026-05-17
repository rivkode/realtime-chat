plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	implementation("org.springframework.boot:spring-boot-starter-validation")

	testImplementation(testFixtures(project(":common")))
}
