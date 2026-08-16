package com.musinsapayments.point.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.musinsapayments.point.dto.AdminGrantRequest;
import com.musinsapayments.point.dto.PointEarnResponse;
import com.musinsapayments.point.global.common.ApiResponse;
import com.musinsapayments.point.service.PointService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 수기 지급 전용 엔드포인트. 회원용 /points/earn 과 분리해,
 * 클라이언트가 임의로 source(수기지급 여부)를 조작할 수 없도록 한다.
 */
@Tag(name = "Point Admin", description = "관리자 수기 지급 API — 회원용 API와 분리되어 있어 source 조작 불가")
@RestController
@RequestMapping("/api/v1/admin/points")
@RequiredArgsConstructor
public class PointAdminController {

	private final PointService pointService;

	@PostMapping("/manual-earn")
	@Operation(summary = "관리자 수기 지급", description = "earnSource=MANUAL로 적립되어 사용 시 일반 적립보다 우선 소진된다. "
			+ "Idempotency-Key 헤더를 보내면 같은 키로 재시도했을 때 중복 지급되지 않는다.")
	public ApiResponse<PointEarnResponse> manualEarn(@Valid @RequestBody AdminGrantRequest request,
			@Parameter(description = "재시도 멱등키(선택)")
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return ApiResponse.success(pointService.manualEarn(request, idempotencyKey));
	}

}
