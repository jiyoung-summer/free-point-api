package com.musinsapayments.point.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * TraceIdFilter가 요청마다 traceId를 MDC에 심어 로그 상관관계를 만들고,
 * 응답 헤더로도 내려 클라이언트/상위 계층과 이어붙일 수 있게 하는지 확인한다.
 */
class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	@Test
	void 요청마다_traceId가_MDC에_설정되고_응답_헤더로도_내려간_뒤_요청이_끝나면_제거된다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> capturedDuringChain = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> capturedDuringChain.set(MDC.get("traceId")));

		assertThat(capturedDuringChain.get()).isNotBlank();
		assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(capturedDuringChain.get());
		// 스레드 풀 재사용 시 다음 요청으로 값이 새어나가면 안 된다 — 필터 종료 후에는 반드시 비어있어야 한다.
		assertThat(MDC.get("traceId")).isNull();
	}

	@Test
	void 서로_다른_요청은_서로_다른_traceId를_받는다() throws Exception {
		MockHttpServletRequest request1 = new MockHttpServletRequest();
		MockHttpServletResponse response1 = new MockHttpServletResponse();
		MockHttpServletRequest request2 = new MockHttpServletRequest();
		MockHttpServletResponse response2 = new MockHttpServletResponse();

		filter.doFilter(request1, response1, (req, res) -> { });
		filter.doFilter(request2, response2, (req, res) -> { });

		assertThat(response1.getHeader(TraceIdFilter.TRACE_ID_HEADER))
				.isNotEqualTo(response2.getHeader(TraceIdFilter.TRACE_ID_HEADER));
	}

	@Test
	void 상위_계층이_이미_X_Trace_Id_헤더를_보냈다면_새로_만들지_않고_그대로_이어받는다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "upstream-trace-123");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> capturedDuringChain = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> capturedDuringChain.set(MDC.get("traceId")));

		assertThat(capturedDuringChain.get()).isEqualTo("upstream-trace-123");
		assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("upstream-trace-123");
	}

	@Test
	void 공백_X_Trace_Id_헤더는_상위값으로_취급하지_않고_새로_발급한다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "   ");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> { });

		assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();
		assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isNotEqualTo("   ");
	}

}
