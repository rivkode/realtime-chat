plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")

	// Resilience4j — Redis publish/presence에 circuit breaker(§15.4 graceful degradation).
	// Spring Boot 3 호환 starter는 AOP·actuator 통합·application.yaml 설정 자동 wiring.
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
	implementation("org.springframework.boot:spring-boot-starter-aop")

	testImplementation(testFixtures(project(":common")))
}
