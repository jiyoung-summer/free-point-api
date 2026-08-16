package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "적립 취소 응답")
public record PointEarnCancelResponse(
		@Schema(description = "발급된 EARN_CANCEL 거래 pointKey")
		String cancelPointKey,

		@Schema(description = "취소된 적립분의 원본 pointKey")
		String canceledEarnPointKey,

		@Schema(description = "취소된 금액", example = "1000")
		long canceledAmount,

		@Schema(description = "취소 후 총 잔액")
		long balance
) {
}
