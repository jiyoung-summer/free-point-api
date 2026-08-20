package com.musinsapayments.point.global.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 traceId를 MDC에 심어 로그 패턴(%X{traceId})에 자동으로 찍히게 한다.
 * 동시 요청이 몰릴 때, 같은 요청에 속한 로그 줄들을 traceId로 묶어볼 수 있다.
 * 상위(API Gateway 등)에서 이미 X-Trace-Id를 붙여 보냈다면 그대로 이어받아,
 * 이 서비스 안에서만 끊기지 않고 상위 트레이스와 연결되게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final String MDC_KEY = "traceId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String traceId = request.getHeader(TRACE_ID_HEADER);
		if (traceId == null || traceId.isBlank()) {
			traceId = UUID.randomUUID().toString();
		}
		MDC.put(MDC_KEY, traceId);
		response.setHeader(TRACE_ID_HEADER, traceId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			// 스레드 풀 재사용 환경에서 다음 요청으로 traceId가 새어나가지 않도록 반드시 제거한다.
			MDC.remove(MDC_KEY);
		}
	}

}
