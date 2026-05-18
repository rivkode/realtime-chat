plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	testImplementation(testFixtures(project(":common")))
}
