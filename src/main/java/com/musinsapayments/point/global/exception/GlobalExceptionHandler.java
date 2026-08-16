package com.musinsapayments.point.global.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.extern.slf4j.Slf4j;

/**
 * 4xx(잘못된 요청, 잔액 부족 같은 정상적인 비즈니스 거절)는 매일 일어나는 정상 트래픽이라 로그를
 * 남기지 않는다 — 전부 남기면 진짜 장애 신호가 노이즈에 묻힌다. 5xx(코드가 시스템 상태를 예상하지
 * 못했다는 뜻)만 스택트레이스와 함께 남겨서, 운영 중에는 로그의 ERROR 레벨만 봐도 바로 무엇이
 * 왜 실패했는지 알 수 있게 한다. 응답 바디의 `code`(예: `IDEMPOTENCY_CODEC_FAILED`)도 로그 검색
 * 키워드로 그대로 쓸 수 있다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		if (errorCode.getStatus().is5xxServerError()) {
			log.error("[{}] {}", errorCode, e.getMessage(), e);
		}
		return ResponseEntity.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, e.getMessage()));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
			MissingServletRequestParameterException e) {
		String message = e.getParameterName() + " 파라미터가 필요합니다.";
		return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, message));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
			MethodArgumentTypeMismatchException e) {
		String message = e.getName() + " 파라미터 형식이 올바르지 않습니다.";
		return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, message));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.orElse(ErrorCode.INVALID_INPUT_VALUE.getMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, message));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception e) {
		// 다른 핸들러 어디에도 안 걸린, 코드가 미처 예상하지 못한 예외다 — 원인 파악은 전적으로
		// 이 로그(스택트레이스)에 의존하므로 반드시 남긴다.
		log.error("[{}] unhandled exception", ErrorCode.INTERNAL_SERVER_ERROR, e);
		return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
	}

}
