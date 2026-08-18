package com.musinsapayments.point.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용 취소 응답")
public record PointUseCancelResponse(
		@Schema(description = "발급된 USE_CANCEL 거래 pointKey")
		String pointKey,

		@Schema(description = "취소된 금액", example = "1100")
		long canceledAmount,

		@Schema(description = "취소 후 총 잔액", example = "1400")
		long balance,

		@Schema(description = "적립분별 복원/재적립 내역")
		List<Restoration> restorations,

		@Schema(description = "호출자 측 외부 시스템의 거래 ID (선택, 추적용)")
		String clientTransactionId
) {

	@Schema(description = "reissued=true 면 originLotId 가 만료되어 reissuedPointKey 로 신규 적립되었다는 뜻이고, "
			+ "false 면 originLotId 에 amount 만큼 그대로 복원되었다는 뜻이다.")
	public record Restoration(
			@Schema(description = "취소 대상이 되었던 원본 적립분(Lot) ID") Long originLotId,
			@Schema(description = "복원/재적립된 금액") long amount,
			@Schema(description = "만료로 인한 신규 재적립 여부") boolean reissued,
			@Schema(description = "reissued=true 일 때 새로 발급된 적립 pointKey") String reissuedPointKey
	) {
	}

}
