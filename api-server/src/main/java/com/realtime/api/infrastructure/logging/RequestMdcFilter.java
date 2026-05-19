package com.realtime.api.infrastructure.logging;

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
 * REST 요청마다 MDC에 {@code requestId}와 (URL에서 추출되면) {@code sessionId}를 박는다(§14.1).
 *
 * <ul>
 *   <li>클라이언트가 {@code X-Request-Id} 헤더를 보내면 그것을 잇고, 없으면 새 UUID 발급</li>
 *   <li>{@code /sessions/{id}/...} 형태 URL에서 sessionId 추출 → 그 요청에 관여한 모든 로그가
 *       같은 sessionId로 묶여 검색 가능</li>
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
		MDC.put("requestId", requestId);
		response.setHeader("X-Request-Id", requestId);

		String sessionId = extractSessionId(request.getRequestURI());
		if (sessionId != null) {
			MDC.put("sessionId", sessionId);
		}

		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove("requestId");
			MDC.remove("sessionId");
		}
	}

	private String extractSessionId(String uri) {
		if (uri == null) return null;
		Matcher m = SESSION_PATH.matcher(uri);
		return m.find() ? m.group(1) : null;
	}
}
