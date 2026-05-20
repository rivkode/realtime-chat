package com.realtime.api.infrastructure.logging;

import com.realtime.common.logging.TraceMdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST 요청마다 MDC에 {@code requestId/sessionId/traceId}를 박는다(§14.1, §14.2).
 *
 * <ul>
 *   <li>{@code X-Request-Id} — 요청 단위 식별자. 없으면 새 UUID 발급. 응답 헤더에 회신.</li>
 *   <li>{@code X-Trace-Id} — 한 메시지의 전체 경로 추적 ID(§14.2). 없으면 새 UUID 발급.
 *       이 요청이 POST /sessions/{id}/events라면 그 이벤트가 events 도큐먼트에 같은 traceId로 저장된다.</li>
 *   <li>{@code /sessions/{id}/...} URL에서 sessionId 추출</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestMdcFilter extends OncePerRequestFilter {

	private static final Pattern SESSION_PATH = Pattern.compile("/sessions/([0-9a-fA-F-]{36})(?:/|$)");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = request.getHeader("X-Request-Id");
		if (requestId == null || requestId.isBlank()) {
			requestId = UUID.randomUUID().toString();
		}
		MDC.put(TraceMdcKeys.REQUEST_ID, requestId);
		response.setHeader("X-Request-Id", requestId);

		String traceId = request.getHeader("X-Trace-Id");
		if (traceId == null || traceId.isBlank()) {
			traceId = UUID.randomUUID().toString();
		}
		MDC.put(TraceMdcKeys.TRACE_ID, traceId);
		response.setHeader("X-Trace-Id", traceId);

		String sessionId = extractSessionId(request.getRequestURI());
		if (sessionId != null) {
			MDC.put(TraceMdcKeys.SESSION_ID, sessionId);
		}

		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(TraceMdcKeys.REQUEST_ID);
			MDC.remove(TraceMdcKeys.TRACE_ID);
			MDC.remove(TraceMdcKeys.SESSION_ID);
		}
	}

	private String extractSessionId(String uri) {
		if (uri == null) return null;
		Matcher m = SESSION_PATH.matcher(uri);
		return m.find() ? m.group(1) : null;
	}
}
