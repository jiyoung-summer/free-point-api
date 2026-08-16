package com.musinsapayments.point.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "포인트 사용 응답")
public record PointUseResponse(
		@Schema(description = "발급된 USE 거래 pointKey")
		String pointKey,

		@Schema(description = "주문번호", example = "A1234")
		String orderNo,

		@Schema(description = "사용 금액", example = "1200")
		long amount,

		@Schema(description = "사용 후 총 잔액", example = "300")
		long balance,

		@Schema(description = "어떤 적립분에서 얼마씩 소진됐는지 (1원 단위 사용처 추적)")
		List<Allocation> allocations
) {

	@Schema(description = "적립분별 사용 내역")
	public record Allocation(
			@Schema(description = "소진된 적립분(Lot) ID") Long lotId,
			@Schema(description = "소진된 적립분의 pointKey") String earnPointKey,
			@Schema(description = "이 적립분에서 사용된 금액") long amount
	) {
	}

}
