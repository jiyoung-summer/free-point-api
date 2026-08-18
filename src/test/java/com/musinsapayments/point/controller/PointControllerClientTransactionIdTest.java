package com.musinsapayments.point.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
 * 요청 본문 필드 clientTransactionId가 실제 HTTP 요청 경로(컨트롤러 → PointService → 응답/조회)까지
 * 제대로 배선되는지 확인한다. 서비스 레이어 단위 테스트(PointServiceClientTransactionIdTest)가 이미
 * 저장/응답/멱등성-무관 여부를 검증했으므로, 여기서는 HTTP 바인딩과 검증만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointControllerClientTransactionIdTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 적립_요청_본문의_clientTransactionId가_응답에_그대로_반환된다() throws Exception {
		long userId = System.nanoTime();

		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000,\"clientTransactionId\":\"ext-1\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", is("ext-1")));
	}

	@Test
	void clientTransactionId를_생략해도_적립_요청은_정상_처리되고_null로_반환된다() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + System.nanoTime() + ",\"amount\":1000}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", nullValue()));
	}

	@Test
	void 사용_사용취소_적립취소_요청에도_clientTransactionId가_각각_응답에_반영된다() throws Exception {
		long userId = System.nanoTime();
		String earnResponse = mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String earnPointKey = JsonPath.read(earnResponse, "$.data.pointKey");

		mockMvc.perform(post("/api/v1/points/earn/" + earnPointKey + "/cancel")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"clientTransactionId\":\"ext-cancel\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", is("ext-cancel")));

		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000}"))
				.andExpect(status().isOk());

		String useResponse = mockMvc.perform(post("/api/v1/points/use")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"orderNo\":\"O-1\",\"amount\":300,\"clientTransactionId\":\"ext-use\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", is("ext-use")))
				.andReturn().getResponse().getContentAsString();
		String usePointKey = JsonPath.read(useResponse, "$.data.pointKey");

		mockMvc.perform(post("/api/v1/points/use/" + usePointKey + "/cancel")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":100,\"clientTransactionId\":\"ext-usecancel\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", is("ext-usecancel")));
	}

	@Test
	void 거래_이력_조회_응답에도_clientTransactionId가_포함된다() throws Exception {
		long userId = System.nanoTime();
		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + userId + ",\"amount\":1000,\"clientTransactionId\":\"ext-list\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/points/transactions").param("userId", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].clientTransactionId", is("ext-list")));
	}

	@Test
	void clientTransactionId가_100자를_초과하면_HTTP_400을_반환한다() throws Exception {
		String tooLong = "a".repeat(101);

		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":" + System.nanoTime() + ",\"amount\":1000,\"clientTransactionId\":\"" + tooLong + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
	}

	@Test
	void 같은_Idempotency_Key_헤더로_재시도할때_clientTransactionId가_달라도_최초_응답이_그대로_반환된다() throws Exception {
		long userId = System.nanoTime();

		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "http-ctid-retry")
						.content("{\"userId\":" + userId + ",\"amount\":1000,\"clientTransactionId\":\"first-ref\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", is("first-ref")));

		mockMvc.perform(post("/api/v1/points/earn")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Idempotency-Key", "http-ctid-retry")
						.content("{\"userId\":" + userId + ",\"amount\":1000,\"clientTransactionId\":\"second-ref\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientTransactionId", is("first-ref")));
	}

}
