package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "사용 취소 요청 (전체/부분 취소 가능)")
public record UseCancelRequest(
		@Schema(description = "취소 대상 사용 거래의 소유자 회원 ID. pointKey의 실제 소유자와 다르면 거절된다.", example = "1")
		@NotNull Long userId,

		@Schema(description = "취소 금액 (취소 가능 잔여 금액 이하)", example = "1100")
		@Positive long amount
) {
}
