package com.musinsapayments.point.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	EARN_AMOUNT_OUT_OF_POLICY(HttpStatus.BAD_REQUEST, "적립 요청 금액이 정책 허용 범위를 벗어났습니다."),
	EXPIRE_DAYS_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "만료일이 정책 허용 범위를 벗어났습니다."),
	HOLD_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "개인별 최대 보유 한도를 초과합니다."),
	INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "포인트 잔액이 부족합니다."),
	POINT_LOT_NOT_USABLE(HttpStatus.CONFLICT, "사용할 수 없는 적립분입니다."),
	PARTIALLY_USED_LOT_CANNOT_BE_CANCELED(HttpStatus.CONFLICT, "이미 사용된 적립분은 취소할 수 없습니다."),
	LOT_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 적립분입니다."),
	AMOUNT_OVERFLOW(HttpStatus.INTERNAL_SERVER_ERROR, "금액 합계 계산 중 처리 가능한 범위를 초과했습니다."),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "이미 사용된 Idempotency-Key이지만 요청 내용이 다릅니다. 같은 키는 동일한 요청 재시도에만 사용하세요."),

	// 아래 3개는 전부 500이지만, "무엇이 실패했는지"를 뭉뚱그리지 않기 위해 INTERNAL_SERVER_ERROR와
	// 분리했다 — 코드/로그로 원인을 구분할 수 있어야 운영 중 장애 대응이 가능하다.
	ACCOUNT_PROVISIONING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "계정 정보를 확인할 수 없습니다. 잠시 후 다시 시도해주세요."),
	IDEMPOTENCY_CODEC_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "요청 처리 결과를 저장하지 못했습니다. 잠시 후 다시 시도해주세요."),
	POLICY_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "적용 가능한 포인트 정책이 없습니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

}
