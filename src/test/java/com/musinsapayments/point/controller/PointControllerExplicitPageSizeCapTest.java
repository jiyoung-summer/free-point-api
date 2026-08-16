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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * spring.data.web.pageable.max-page-size(전역 설정) 하나에만 기대지 않고,
 * PointController 자체에 명시한 상한(MAX_LOT_PAGE_SIZE 등)이 독립적으로 동작하는지 확인한다.
 * 전역 설정값을 일부러 100보다 훨씬 크게 올려도 컨트롤러의 명시적 상한이 여전히 이겨야 한다 —
 * 그래야 "설정 파일에서 값이 바뀌거나 실수로 빠져도 코드 레벨 안전망은 남아있다"는 보장이 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.data.web.pageable.max-page-size=5000")
class PointControllerExplicitPageSizeCapTest {

	@Autowired
	private MockMvc mockMvc;

	private static final long USER_ID = 901L;

	@BeforeEach
	void seedOneLot() throws Exception {
		mockMvc.perform(post("/api/v1/points/earn")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":" + USER_ID + ",\"amount\":1000}"));
	}

	@Test
	void 전역_상한을_5000으로_올려도_lots는_컨트롤러가_명시한_100을_넘지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/points/lots")
						.param("userId", String.valueOf(USER_ID))
						.param("size", "5000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.size", is(100)));
	}

	@Test
	void 전역_상한을_5000으로_올려도_transactions는_컨트롤러가_명시한_100을_넘지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/points/transactions")
						.param("userId", String.valueOf(USER_ID))
						.param("size", "5000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.size", is(100)));
	}

}
