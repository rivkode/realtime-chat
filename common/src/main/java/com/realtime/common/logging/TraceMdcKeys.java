package com.realtime.common.logging;

/**
 * MDC 키 상수(설계서 §14).
 *
 * <p>한 군데서 관리해 키 이름 오타를 컴파일 타임에 차단한다. logback-spring.xml의
 * {@code <includeMdcKeyName>}과 정확히 일치해야 JSON 로그에 노출된다.
 */
public final class TraceMdcKeys {

	public static final String TRACE_ID = "traceId";
	public static final String SESSION_ID = "sessionId";
	public static final String USER_ID = "userId";
	public static final String EVENT_ID = "eventId";
	public static final String CONNECTION_ID = "connectionId";
	public static final String REQUEST_ID = "requestId";

	private TraceMdcKeys() {
	}
}
