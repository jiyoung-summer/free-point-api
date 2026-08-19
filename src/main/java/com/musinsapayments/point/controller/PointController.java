package com.musinsapayments.point.controller;

import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.musinsapayments.point.dto.EarnCancelRequest;
import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.PointBalanceResponse;
import com.musinsapayments.point.dto.PointEarnCancelResponse;
import com.musinsapayments.point.dto.PointEarnResponse;
import com.musinsapayments.point.dto.PointLotResponse;
import com.musinsapayments.point.dto.PointTransactionResponse;
import com.musinsapayments.point.dto.PointUseCancelResponse;
import com.musinsapayments.point.dto.PointUseResponse;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.common.ApiResponse;
import com.musinsapayments.point.global.common.PageResponse;
import com.musinsapayments.point.global.common.PageableSupport;
import com.musinsapayments.point.global.common.SortSupport;
import com.musinsapayments.point.global.exception.ErrorResponse;
import com.musinsapayments.point.service.PointService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Point", description = "회원용 포인트 적립/사용 API")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointController {

	private static final Set<String> LOT_SORT_PROPERTIES = Set.of("createdAt", "expireAt", "amount", "remainingAmount");
	private static final Set<String> TRANSACTION_SORT_PROPERTIES = Set.of("createdAt", "amount");
	private static final int MAX_LOT_PAGE_SIZE = 100;
	private static final int MAX_TRANSACTION_PAGE_SIZE = 100;

	private final PointService pointService;

	@PostMapping("/earn")
	@Operation(summary = "포인트 적립", description = "1회 적립 한도·개인별 최대 보유 한도는 point_policy로 런타임 제어된다. expireDays 미지정 시 정책 기본값(365일)이 적용된다. "
			+ "Idempotency-Key 헤더를 보내면 같은 키로 재시도했을 때 중복 적립되지 않고 최초 응답이 그대로 반환된다.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "적립 성공"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "적립 금액/만료일이 정책 범위를 벗어남",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "개인별 최대 보유 한도 초과",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResponse<PointEarnResponse> earn(@Valid @RequestBody EarnRequest request,
			@Parameter(description = "재시도 멱등키(선택). 같은 요청을 재전송할 때 동일한 값을 보내면 중복 처리되지 않는다.")
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return ApiResponse.success(pointService.earn(request, idempotencyKey));
	}

	@PostMapping("/earn/{pointKey}/cancel")
	@Operation(summary = "적립 취소", description = "적립분이 한 번도 사용되지 않은 경우에만 취소할 수 있다. "
			+ "요청 본문의 userId가 pointKey의 실제 소유자와 다르면 존재하지 않는 pointKey와 동일하게(404) 거절된다.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "적립 취소 성공"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 pointKey이거나 userId가 소유자와 다름",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 취소되었거나 일부 사용된 적립분",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResponse<PointEarnCancelResponse> cancelEarn(
			@Parameter(description = "취소할 적립분의 pointKey", example = "b3628ab6-3395-4c36-a4b3-4477da381e7b")
			@PathVariable String pointKey,
			@Valid @RequestBody EarnCancelRequest request,
			@Parameter(description = "재시도 멱등키(선택)")
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return ApiResponse.success(pointService.earnCancel(pointKey, request, idempotencyKey));
	}

	@PostMapping("/use")
	@Operation(summary = "포인트 사용", description = "주문 시에만 사용 가능. 관리자 수기 지급 적립분이 우선, 동일 우선순위 내에서는 만료 임박 적립분부터 소진한다. "
			+ "Idempotency-Key 헤더를 보내면 같은 키로 재시도했을 때 중복 차감되지 않고 최초 응답이 그대로 반환된다.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용 성공"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "잔액 부족",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResponse<PointUseResponse> use(@Valid @RequestBody UseRequest request,
			@Parameter(description = "재시도 멱등키(선택). 네트워크 타임아웃 후 재시도 시 이중 차감을 막는다.")
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return ApiResponse.success(pointService.use(request, idempotencyKey));
	}

	@PostMapping("/use/{pointKey}/cancel")
	@Operation(summary = "사용 취소", description = "전체/부분 취소를 지원한다. 취소 대상 적립분이 이미 만료되었다면 동일 금액을 신규 적립(pointKey 새로 발급)으로 되돌린다.")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용 취소 성공"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용 거래 pointKey",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "취소 요청 금액이 취소 가능 금액을 초과",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ApiResponse<PointUseCancelResponse> cancelUse(
			@Parameter(description = "취소할 사용 거래의 pointKey", example = "889b8711-a52b-46bd-a756-869c8cc446b6")
			@PathVariable String pointKey,
			@Valid @RequestBody UseCancelRequest request,
			@Parameter(description = "재시도 멱등키(선택)")
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return ApiResponse.success(pointService.useCancel(pointKey, request, idempotencyKey));
	}

	@GetMapping("/balance")
	@Operation(summary = "잔액 조회", description = "SUM(point_lot.remaining_amount) — ACTIVE 상태이고 만료되지 않은 적립분의 합")
	public ApiResponse<PointBalanceResponse> getBalance(
			@Parameter(description = "회원 ID", example = "1") @RequestParam Long userId) {
		return ApiResponse.success(pointService.getBalance(userId));
	}

	@GetMapping("/lots")
	@Operation(summary = "적립분 목록 조회", description = "만료/취소/사용완료 포함 전체 이력. 기본 정렬은 createdAt 내림차순. "
			+ "sort로 지정 가능한 필드: createdAt, expireAt, amount, remainingAmount. size는 최대 " + MAX_LOT_PAGE_SIZE + "까지만 허용된다.")
	public ApiResponse<PageResponse<PointLotResponse>> getLots(
			@Parameter(description = "회원 ID", example = "1") @RequestParam Long userId,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		SortSupport.validate(pageable.getSort(), LOT_SORT_PROPERTIES);
		Pageable safePageable = PageableSupport.capSize(pageable, MAX_LOT_PAGE_SIZE);
		return ApiResponse.success(pointService.getLots(userId, safePageable));
	}

	@GetMapping("/transactions")
	@Operation(summary = "거래 이력 조회", description = "EARN/EARN_CANCEL/USE/USE_CANCEL 전체 이력. 기본 정렬은 createdAt 내림차순. "
			+ "sort로 지정 가능한 필드: createdAt, amount. size는 최대 " + MAX_TRANSACTION_PAGE_SIZE + "까지만 허용된다.")
	public ApiResponse<PageResponse<PointTransactionResponse>> getTransactions(
			@Parameter(description = "회원 ID", example = "1") @RequestParam Long userId,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		SortSupport.validate(pageable.getSort(), TRANSACTION_SORT_PROPERTIES);
		Pageable safePageable = PageableSupport.capSize(pageable, MAX_TRANSACTION_PAGE_SIZE);
		return ApiResponse.success(pointService.getTransactions(userId, safePageable));
	}

}
