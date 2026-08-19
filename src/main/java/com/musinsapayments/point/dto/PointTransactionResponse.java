package com.musinsapayments.point.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "거래 이력 조회 응답 항목")
public record PointTransactionResponse(
		@Schema(description = "거래 pointKey") String pointKey,
		@Schema(description = "EARN / EARN_CANCEL / USE / USE_CANCEL", example = "EARN") String transactionType,
		@Schema(description = "거래 금액") long amount,
		@Schema(description = "주문번호 (USE/USE_CANCEL만 존재)") String orderNo,
		@Schema(description = "관련 원거래 ID (취소 거래에서 원거래를 가리킴)") Long relatedTransactionId,
		@Schema(description = "거래 일시") LocalDateTime createdAt
) {
}
