package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "사용 취소 요청 (전체/부분 취소 가능)")
public record UseCancelRequest(
		@Schema(description = "취소 금액 (취소 가능 잔여 금액 이하)", example = "1100")
		@Positive long amount
) {
}
