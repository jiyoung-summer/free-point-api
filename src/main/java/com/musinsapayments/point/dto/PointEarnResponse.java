package com.musinsapayments.point.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "포인트 적립 응답")
public record PointEarnResponse(
		@Schema(description = "발급된 적립 pointKey", example = "b3628ab6-3395-4c36-a4b3-4477da381e7b")
		String pointKey,

		@Schema(description = "적립분(Lot) ID", example = "1")
		Long lotId,

		@Schema(description = "적립 금액", example = "1000")
		long amount,

		@Schema(description = "만료 일시")
		LocalDateTime expireAt,

		@Schema(description = "관리자 수기 지급 여부", example = "false")
		boolean manual,

		@Schema(description = "적립 후 총 잔액", example = "1000")
		long balance,

		@Schema(description = "호출자 측 외부 시스템의 거래 ID (선택, 추적용)")
		String clientTransactionId
) {
}
