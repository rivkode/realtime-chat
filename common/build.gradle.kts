plugins {
	`java-library`
	`java-test-fixtures`
}

dependencies {
	api("org.springframework.data:spring-data-mongodb")
	api("com.fasterxml.jackson.core:jackson-databind")
	api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

	testFixturesImplementation("org.springframework.data:spring-data-mongodb")
}
