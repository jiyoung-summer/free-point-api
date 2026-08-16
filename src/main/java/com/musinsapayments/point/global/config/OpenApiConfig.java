package com.musinsapayments.point.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("Free Point API")
						.description("무신사페이먼츠 Backend Engineer 과제 — 적립 / 적립취소 / 사용 / 사용취소 무료 포인트 API")
						.version("v1"));
	}

}
