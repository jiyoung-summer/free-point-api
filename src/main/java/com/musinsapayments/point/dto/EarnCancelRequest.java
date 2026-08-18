package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "적립 취소 요청")
public record EarnCancelRequest(
		@Schema(description = "취소 대상 적립분의 소유자 회원 ID. pointKey의 실제 소유자와 다르면 거절된다.", example = "1")
		@NotNull Long userId,

		@Schema(description = "호출자 측 외부 시스템의 거래 ID(선택). 거래 추적 참고용으로만 저장되며 멱등성 처리에는 관여하지 않는다.", example = "CANCEL-SYS-88231")
		@Size(max = 100) String clientTransactionId
) {
}
