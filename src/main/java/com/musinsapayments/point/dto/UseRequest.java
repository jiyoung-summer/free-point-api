package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "포인트 사용 요청")
public record UseRequest(
		@Schema(description = "회원 ID", example = "1")
		@NotNull Long userId,

		@Schema(description = "주문번호", example = "A1234")
		@NotBlank @Size(max = 50) String orderNo,

		@Schema(description = "사용 금액", example = "1200")
		@Positive long amount,

		@Schema(description = "호출자 측 외부 시스템의 거래 ID(선택). 거래 추적 참고용으로만 저장되며 멱등성 처리에는 관여하지 않는다.", example = "ORDER-SYS-88231")
		@Size(max = 100) String clientTransactionId
) {
}
