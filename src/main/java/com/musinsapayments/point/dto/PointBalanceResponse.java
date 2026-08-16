package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "잔액 조회 응답")
public record PointBalanceResponse(
		@Schema(description = "총 잔액 (SUM of ACTIVE, 미만료 적립분의 remaining_amount)", example = "300")
		long balance
) {
}
