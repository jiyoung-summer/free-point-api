package com.musinsapayments.point.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "적립 취소 요청")
public record EarnCancelRequest(
		@Schema(description = "취소 대상 적립분의 소유자 회원 ID. pointKey의 실제 소유자와 다르면 거절된다.", example = "1")
		@NotNull Long userId
) {
}
