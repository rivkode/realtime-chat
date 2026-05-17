plugins {
	`java-library`
	`java-test-fixtures`
}

dependencies {
	api("org.springframework.data:spring-data-mongodb")
	api("com.fasterxml.jackson.core:jackson-databind")
	api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	api("com.fasterxml.uuid:java-uuid-generator:5.1.0")

	testFixturesImplementation("org.springframework.data:spring-data-mongodb")
}
