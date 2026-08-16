package com.musinsapayments.point.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * @Valid 로 걸어둔 DTO 검증이 실제 HTTP 요청 경로(컨트롤러 → GlobalExceptionHandler)를
 * 통해서도 의도한 상태 코드/에러 응답으로 나오는지 확인한다. RequestValidationTest는 DTO를
 * 직접 Validator에 넣어보는 단위 테스트라 컨트롤러/예외 핸들러를 거치지 않는다 — 이 테스트는
 * 그 경로 전체(바인딩 → @Valid → MethodArgumentNotValidException → 400 응답 바디)를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointControllerValidationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 적립_금액_0은_HTTP_400과_INVALID_INPUT_VALUE를_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":1,\"amount\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
	}

	@Test
	void 적립_금액_음수는_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":1,\"amount\":-1000}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
	}

	@Test
	void userId가_없으면_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 주문번호가_빈_문자열이면_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/use")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":1,\"orderNo\":\"\",\"amount\":1000}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 사용취소_금액이_음수면_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/use/some-point-key/cancel")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":-100}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 존재하지_않는_pointKey로_적립취소하면_HTTP_404를_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn/does-not-exist/cancel"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("ENTITY_NOT_FOUND")));
	}

	@Test
	void balance_조회에_userId가_없으면_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/points/balance"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 정상적인_적립_요청은_HTTP_200을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":1,\"amount\":1000}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success", is(true)));
	}

	@Test
	void balance_조회에_userId가_숫자가_아니면_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/points/balance").param("userId", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
	}

	@Test
	void 요청_본문이_JSON으로_파싱되지_않으면_HTTP_500과_INTERNAL_SERVER_ERROR를_반환한다() throws Exception {
		// GlobalExceptionHandler의 다른 핸들러 어디에도 안 걸리는 예외(HttpMessageNotReadableException 등)가
		// catch-all(Exception.class)로 실제로 떨어지는지 확인한다.
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{이건 유효한 JSON이 아님"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code", is("INTERNAL_SERVER_ERROR")));
	}

	@Test
	void health_체크는_HTTP_200과_OK를_반환한다() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", is("OK")));
	}

	@Test
	void 정상적인_적립취소_요청은_HTTP_200을_반환한다() throws Exception {
		long userId = System.nanoTime();
		String earnResponse = mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String pointKey = JsonPath.read(earnResponse, "$.data.pointKey");

		mockMvc.perform(post("/api/v1/points/earn/" + pointKey + "/cancel"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success", is(true)))
				.andExpect(jsonPath("$.data.canceledAmount", is(1000)));
	}

	@Test
	void 정상적인_사용_요청은_HTTP_200을_반환한다() throws Exception {
		long userId = System.nanoTime();
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000}"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/points/use")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"orderNo\":\"O-1\",\"amount\":300}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success", is(true)))
				.andExpect(jsonPath("$.data.balance", is(700)));
	}

	@Test
	void 정상적인_사용취소_요청은_HTTP_200을_반환한다() throws Exception {
		long userId = System.nanoTime();
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000}"))
				.andExpect(status().isOk());
		String useResponse = mockMvc.perform(post("/api/v1/points/use")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"orderNo\":\"O-1\",\"amount\":300}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String usePointKey = JsonPath.read(useResponse, "$.data.pointKey");

		mockMvc.perform(post("/api/v1/points/use/" + usePointKey + "/cancel")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success", is(true)))
				.andExpect(jsonPath("$.data.balance", is(800)));
	}

}
