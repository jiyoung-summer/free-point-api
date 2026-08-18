package com.musinsapayments.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * springdoc이 {@code Pageable}(안의 {@code Sort})을 평탄화하지 못하면, {@code sort} 파라미터가
 * 일반 객체로 취급되어 {@code content: application/json}으로 문서화된다 — 그러면 Swagger UI가
 * 값을 JSON 배열 문자열(예: {@code sort=["createdAt"]})로 보내버리는데, Spring의 {@code Pageable}
 * 리졸버는 이 형식을 모르고 문자열 전체를 필드명으로 취급해 {@code SortSupport} 화이트리스트에서
 * 거절한다(실제로 Swagger UI로 재현·확인함). {@code springdoc.default-flat-param-object: true}로
 * 고쳤고, 이 설정이 나중에 조용히 빠지지 않는지 OpenAPI 스펙을 직접 파싱해 지킨다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSortParamTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void lots_sort_파라미터는_JSON_content가_아니라_평범한_배열_쿼리_파라미터로_문서화된다() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andReturn().getResponse().getContentAsString();
		JsonNode sortParam = findParam(body, "/api/v1/points/lots", "sort");

		assertThat(sortParam.has("content"))
				.as("sort가 content(JSON)로 문서화되면 Swagger UI가 배열을 JSON 문자열로 보내 Pageable 파싱과 어긋난다")
				.isFalse();
		assertThat(sortParam.path("schema").path("type").asText())
				.as("sort는 반복 가능한 단순 배열 쿼리 파라미터여야 한다")
				.isEqualTo("array");
	}

	private JsonNode findParam(String apiDocsJson, String path, String paramName) throws Exception {
		JsonNode root = objectMapper.readTree(apiDocsJson);
		JsonNode parameters = root.path("paths").path(path).path("get").path("parameters");
		for (JsonNode param : parameters) {
			if (paramName.equals(param.path("name").asText())) {
				return param;
			}
		}
		throw new AssertionError(paramName + " 파라미터를 " + path + "의 OpenAPI 스펙에서 찾지 못했습니다.");
	}

}
