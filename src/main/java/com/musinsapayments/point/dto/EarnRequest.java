package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "포인트 적립 요청")
public record EarnRequest(
		@Schema(description = "회원 ID", example = "1")
		@NotNull Long userId,

		@Schema(description = "적립 금액 (1 ~ 정책 최대 적립액)", example = "1000")
		@Positive long amount,

		@Schema(description = "만료일수. 미지정 시 정책 기본값(365일) 적용", example = "30")
		Integer expireDays,

		@Schema(description = "적립 사유 메모", example = "리뷰 이벤트")
		@Size(max = 30) String memo
) {
}
