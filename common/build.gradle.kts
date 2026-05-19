plugins {
	`java-library`
	`java-test-fixtures`
}

dependencies {
	api("org.springframework.data:spring-data-mongodb")
	api("com.fasterxml.jackson.core:jackson-databind")
	api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	api("com.fasterxml.uuid:java-uuid-generator:5.1.0")

	// Structured JSON logging (§14.1). Spring Boot의 logback과 호환되며 MDC 키를 자동으로
	// JSON 필드로 노출한다 — 로그 한 라인에 session_id·user_id·trace_id가 그대로 박힘.
	api("net.logstash.logback:logstash-logback-encoder:8.0")

	testFixturesImplementation("org.springframework.data:spring-data-mongodb")
}
