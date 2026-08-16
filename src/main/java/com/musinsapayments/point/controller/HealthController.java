package com.musinsapayments.point.controller;

import com.musinsapayments.point.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "헬스 체크")
@RestController
public class HealthController {

	@GetMapping("/api/health")
	@Operation(summary = "헬스 체크", description = "애플리케이션 기동 여부 확인")
	public ApiResponse<String> health() {
		return ApiResponse.success("OK");
	}

}
