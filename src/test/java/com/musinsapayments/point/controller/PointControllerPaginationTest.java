package com.musinsapayments.point.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /points/lots, /points/transactions 의 페이지 크기 상한과 정렬 필드 화이트리스트가
 * 실제 HTTP 레벨에서 동작하는지 확인한다 (SortSupportTest는 검증 로직 자체만 단위 테스트한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointControllerPaginationTest {

	@Autowired
	private MockMvc mockMvc;

	private static final long USER_ID = 900L;

	@BeforeEach
	void seedOneLot() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":" + USER_ID + ",\"amount\":1000}"));
	}

	@Test
	void size를_아무리_크게_요청해도_전역_상한을_넘지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/points/lots")
						.param("userId", String.valueOf(USER_ID))
						.param("size", "1000000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.size", is(100)));
	}

	@Test
	void 음수_page는_0으로_처리된다() throws Exception {
		mockMvc.perform(get("/api/v1/points/lots")
						.param("userId", String.valueOf(USER_ID))
						.param("page", "-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page", is(0)));
	}

	@Test
	void 존재하지_않는_정렬_필드를_요청하면_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/points/lots")
						.param("userId", String.valueOf(USER_ID))
						.param("sort", "doesNotExist,asc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
	}

	@Test
	void 화이트리스트에_없는_실제_필드로_정렬을_요청하면_400을_반환한다() throws Exception {
		// policyId는 PointLot 엔티티에 실재하는 필드지만 응답 DTO에 노출되지 않고 정렬도 허용하지 않는다.
		mockMvc.perform(get("/api/v1/points/lots")
						.param("userId", String.valueOf(USER_ID))
						.param("sort", "policyId,asc"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 허용된_필드로_정렬하면_정상_처리된다() throws Exception {
		mockMvc.perform(get("/api/v1/points/lots")
						.param("userId", String.valueOf(USER_ID))
						.param("sort", "amount,asc"))
				.andExpect(status().isOk());
	}

	@Test
	void transactions_화이트리스트에_없는_필드로_정렬하면_400을_반환한다() throws Exception {
		mockMvc.perform(get("/api/v1/points/transactions")
						.param("userId", String.valueOf(USER_ID))
						.param("sort", "orderNo,asc"))
				.andExpect(status().isBadRequest());
	}

}
