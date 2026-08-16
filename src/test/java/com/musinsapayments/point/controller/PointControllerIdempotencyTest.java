package com.musinsapayments.point.controller;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Idempotency-Key 헤더가 실제 HTTP 요청 경로(컨트롤러 → PointService)까지 제대로 전달되는지 확인한다.
 * 서비스 레이어 단위 테스트(PointServiceIdempotencyTest)는 이미 멱등 로직 자체를 검증했으므로,
 * 여기서는 "헤더 → 서비스 인자"로 배선이 실제로 연결되어 있는지만 HTTP 레벨에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointControllerIdempotencyTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 같은_Idempotency_Key_헤더로_두_번_적립을_요청하면_같은_pointKey를_반환한다() throws Exception {
		long userId = System.nanoTime();
		String body = "{\"userId\":" + userId + ",\"amount\":1000}";

		String pointKey1 = mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "http-retry-key")
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		String pointKey2 = mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "http-retry-key")
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(pointKey2).isEqualTo(pointKey1);
	}

	@Test
	void Idempotency_Key_헤더_없이_두_번_적립을_요청하면_서로_다른_적립으로_처리된다() throws Exception {
		long userId = System.nanoTime();
		String body = "{\"userId\":" + userId + ",\"amount\":1000}";

		mockMvc.perform(post("/api/v1/points/earn").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/points/earn").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/points/balance").param("userId", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.balance", is(2000)));
	}

	@Test
	void Idempotency_Key_헤더가_없어도_적립_요청은_정상적으로_성공한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":999,\"amount\":1000}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success", is(true)));
	}

	@Test
	void 공백_Idempotency_Key_헤더는_HTTP_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "   ")
						.content("{\"userId\":998,\"amount\":1000}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
	}

	@Test
	void GET_요청에_Idempotency_Key_헤더를_보내도_완전히_무시된다() throws Exception {
		// GET 컨트롤러 메서드에는 Idempotency-Key @RequestHeader 파라미터 자체가 없어서 Spring이
		// 아예 읽지 않는다 — POST였다면 400으로 거절됐을 공백 값을 일부러 보내, "검증도 하지 않고
		// 그냥 무시한다"는 것을 증명한다(값을 봤다면 공백이므로 거절됐을 것이다).
		long userId = System.nanoTime();
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/points/balance")
						.param("userId", String.valueOf(userId))
						.header("Idempotency-Key", "   "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.balance", is(1000)));
	}

}
