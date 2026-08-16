package com.musinsapayments.point.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "적립분(Lot) 목록 조회 응답 항목")
public record PointLotResponse(
		@Schema(description = "적립 pointKey") String pointKey,
		@Schema(description = "적립분(Lot) ID") Long lotId,
		@Schema(description = "적립 금액") long amount,
		@Schema(description = "사용 가능 잔여 금액") long remainingAmount,
		@Schema(description = "만료 일시") LocalDateTime expireAt,
		@Schema(description = "DB에 저장된 상태값. ACTIVE / EXPIRED / CANCELED — 만료 배치가 없어 "
				+ "expireAt이 지나도 ACTIVE로 남아있을 수 있다. 실제 사용 가능 여부는 이 필드가 아니라 usable을 봐야 한다.",
				example = "ACTIVE")
		String status,
		@Schema(description = "조회 시점 기준으로 지금 사용 가능한지(status=ACTIVE, remainingAmount>0, 미만료) — status와 달리 항상 최신 값이다")
		boolean usable,
		@Schema(description = "조회 시점 기준으로 만료일이 지났는지 — status가 아직 ACTIVE여도 true일 수 있다")
		boolean expired,
		@Schema(description = "관리자 수기 지급 여부") boolean manual,
		@Schema(description = "적립 일시") LocalDateTime createdAt
) {
}
