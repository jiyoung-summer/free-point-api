package com.musinsapayments.point.global.exception;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "에러 응답")
public record ErrorResponse(
		@Schema(description = "에러 코드", example = "INSUFFICIENT_BALANCE") String code,
		@Schema(description = "에러 메시지", example = "포인트 잔액이 부족합니다.") String message
) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message);
	}

}
