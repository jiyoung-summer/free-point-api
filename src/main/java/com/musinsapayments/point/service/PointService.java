package com.musinsapayments.point.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musinsapayments.point.domain.IdempotencyKeyRecord;
import com.musinsapayments.point.domain.PointAccount;
import com.musinsapayments.point.domain.PointLot;
import com.musinsapayments.point.domain.PointPolicy;
import com.musinsapayments.point.domain.PointTransaction;
import com.musinsapayments.point.domain.PointUseCancelDetail;
import com.musinsapayments.point.domain.PointUseDetail;
import com.musinsapayments.point.dto.AdminGrantRequest;
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
import com.musinsapayments.point.global.common.PageResponse;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;
import com.musinsapayments.point.repository.IdempotencyKeyRepository;
import com.musinsapayments.point.repository.PointAccountRepository;
import com.musinsapayments.point.repository.PointLotRepository;
import com.musinsapayments.point.repository.PointPolicyRepository;
import com.musinsapayments.point.repository.PointTransactionRepository;
import com.musinsapayments.point.repository.PointUseCancelDetailRepository;
import com.musinsapayments.point.repository.PointUseDetailRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 적립 / 적립취소 / 사용 / 사용취소 유스케이스.
 * 잔액을 바꾸는 모든 메서드는 시작 시 계정 행을 FOR UPDATE 로 선점해 같은 사용자의 요청을 직렬화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointService {

	private static final int MANUAL_USE_PRIORITY = 0;
	private static final int NORMAL_USE_PRIORITY = 100;
	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

	private final PointAccountRepository accountRepository;
	private final PointAccountProvisioner accountProvisioner;
	private final PointPolicyRepository policyRepository;
	private final PointTransactionRepository transactionRepository;
	private final PointLotRepository lotRepository;
	private final PointUseDetailRepository useDetailRepository;
	private final PointUseCancelDetailRepository useCancelDetailRepository;
	private final IdempotencyKeyRepository idempotencyKeyRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Transactional
	public PointEarnResponse earn(EarnRequest request) {
		return earn(request, null);
	}

	@Transactional
	public PointEarnResponse earn(EarnRequest request, String idempotencyKey) {
		return earn(request.userId(), request.amount(), request.expireDays(), request.memo(), PointLot.EarnSource.ORDER,
				IdempotencyKeyRecord.OperationType.EARN, idempotencyKey);
	}

	@Transactional
	public PointEarnResponse manualEarn(AdminGrantRequest request) {
		return manualEarn(request, null);
	}

	@Transactional
	public PointEarnResponse manualEarn(AdminGrantRequest request, String idempotencyKey) {
		return earn(request.userId(), request.amount(), request.expireDays(), request.memo(), PointLot.EarnSource.MANUAL,
				IdempotencyKeyRecord.OperationType.MANUAL_EARN, idempotencyKey);
	}

	private PointEarnResponse earn(Long userId, long amount, Integer expireDays, String memo, PointLot.EarnSource source,
			IdempotencyKeyRecord.OperationType operationType, String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);
		LocalDateTime now = LocalDateTime.now(clock);

		// 멱등성 확인은 반드시 계정 락을 잡은 "뒤"에 한다 — 락 없이 조회부터 하면 동시에 들어온 같은 키의
		// 두 요청이 둘 다 "기존 응답 없음"을 보고 둘 다 실행해버리는 경쟁이 생긴다. 락이 같은 사용자의
		// 요청을 직렬화해주므로 별도의 분산 락 없이도 check-then-act가 안전해진다.
		PointAccount account = resolveAccountForUpdate(userId);
		String requestHash = requestHash(userId, amount, expireDays, memo);
		if (idempotencyKey != null) {
			Optional<PointEarnResponse> cached = checkIdempotency(userId, operationType, idempotencyKey,
					requestHash, PointEarnResponse.class);
			if (cached.isPresent()) {
				return cached.get();
			}
		}

		PointPolicy policy = currentPolicy(now);
		policy.validateEarnAmount(amount);

		long currentBalance = lotRepository.sumBalance(userId, PointLot.Status.ACTIVE, now);
		long projectedBalance = policy.validateHoldLimit(currentBalance, amount);

		LocalDateTime expireAt = now.plusDays(policy.resolveExpireDays(expireDays));

		PointTransaction txn = PointTransaction.earn(account.getId(), userId, amount, memo, now);
		transactionRepository.save(txn);

		int priority = source == PointLot.EarnSource.MANUAL ? MANUAL_USE_PRIORITY : NORMAL_USE_PRIORITY;
		PointLot lot = PointLot.earn(txn.getPointKey(), userId, txn.getId(), policy.getId(),
				source, priority, amount, expireAt, now);
		lotRepository.save(lot);

		PointEarnResponse response = new PointEarnResponse(txn.getPointKey(), lot.getId(), amount, expireAt,
				source == PointLot.EarnSource.MANUAL, projectedBalance);
		saveIdempotentResponseIfPresent(userId, operationType, idempotencyKey, requestHash, response, now);
		return response;
	}

	@Transactional
	public PointEarnCancelResponse earnCancel(String earnPointKey) {
		return earnCancel(earnPointKey, null);
	}

	@Transactional
	public PointEarnCancelResponse earnCancel(String earnPointKey, String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);
		LocalDateTime now = LocalDateTime.now(clock);
		PointLot lot = lotRepository.findByPointKey(earnPointKey)
				.orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "적립 내역을 찾을 수 없습니다."));

		PointAccount account = resolveAccountForUpdate(lot.getUserId());
		String requestHash = requestHash(earnPointKey);
		if (idempotencyKey != null) {
			Optional<PointEarnCancelResponse> cached = checkIdempotency(lot.getUserId(),
					IdempotencyKeyRecord.OperationType.EARN_CANCEL, idempotencyKey, requestHash, PointEarnCancelResponse.class);
			if (cached.isPresent()) {
				return cached.get();
			}
		}

		long canceledAmount = lot.getRemainingAmount();
		lot.cancelEarn(now);

		PointTransaction txn = PointTransaction.earnCancel(account.getId(), lot.getUserId(), canceledAmount,
				lot.getEarnTransactionId(), null, now);
		transactionRepository.save(txn);

		long balance = lotRepository.sumBalance(lot.getUserId(), PointLot.Status.ACTIVE, now);
		PointEarnCancelResponse response = new PointEarnCancelResponse(txn.getPointKey(), earnPointKey, canceledAmount, balance);
		saveIdempotentResponseIfPresent(lot.getUserId(), IdempotencyKeyRecord.OperationType.EARN_CANCEL, idempotencyKey,
				requestHash, response, now);
		return response;
	}

	@Transactional
	public PointUseResponse use(UseRequest request) {
		return use(request, null);
	}

	@Transactional
	public PointUseResponse use(UseRequest request, String idempotencyKey) {
		if (request.amount() <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "사용 금액은 0보다 커야 합니다.");
		}
		validateIdempotencyKey(idempotencyKey);

		LocalDateTime now = LocalDateTime.now(clock);
		PointAccount account = resolveAccountForUpdate(request.userId());
		String requestHash = requestHash(request.userId(), request.orderNo(), request.amount());
		if (idempotencyKey != null) {
			Optional<PointUseResponse> cached = checkIdempotency(request.userId(),
					IdempotencyKeyRecord.OperationType.USE, idempotencyKey, requestHash, PointUseResponse.class);
			if (cached.isPresent()) {
				return cached.get();
			}
		}

		List<PointLot> usableLots = lotRepository.findUsableLotsForAllocation(request.userId(), PointLot.Status.ACTIVE, now);
		long available = sumExact(usableLots.stream().mapToLong(PointLot::getRemainingAmount));
		if (available < request.amount()) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
		}

		PointTransaction txn = PointTransaction.use(account.getId(), request.userId(), request.amount(),
				request.orderNo(), null, now);
		transactionRepository.save(txn);

		List<PointUseResponse.Allocation> allocations = new ArrayList<>();
		long remaining = request.amount();
		for (PointLot lot : usableLots) {
			if (remaining <= 0) {
				break;
			}
			long take = Math.min(lot.getRemainingAmount(), remaining);
			lot.use(take, now);

			PointUseDetail detail = PointUseDetail.of(txn.getId(), lot.getId(), request.userId(),
					request.orderNo(), take, now);
			useDetailRepository.save(detail);

			allocations.add(new PointUseResponse.Allocation(lot.getId(), lot.getPointKey(), take));
			remaining -= take;
		}

		long balance = lotRepository.sumBalance(request.userId(), PointLot.Status.ACTIVE, now);
		PointUseResponse response = new PointUseResponse(txn.getPointKey(), request.orderNo(), request.amount(), balance, allocations);
		saveIdempotentResponseIfPresent(request.userId(), IdempotencyKeyRecord.OperationType.USE, idempotencyKey,
				requestHash, response, now);
		return response;
	}

	@Transactional
	public PointUseCancelResponse useCancel(String usePointKey, UseCancelRequest request) {
		return useCancel(usePointKey, request, null);
	}

	@Transactional
	public PointUseCancelResponse useCancel(String usePointKey, UseCancelRequest request, String idempotencyKey) {
		if (request.amount() <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "취소 금액은 0보다 커야 합니다.");
		}
		validateIdempotencyKey(idempotencyKey);

		LocalDateTime now = LocalDateTime.now(clock);
		PointTransaction useTxn = transactionRepository.findByPointKey(usePointKey)
				.filter(t -> t.getTransactionType() == PointTransaction.Type.USE)
				.orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "사용 내역을 찾을 수 없습니다."));

		PointAccount account = resolveAccountForUpdate(useTxn.getUserId());
		String requestHash = requestHash(usePointKey, request.amount());
		if (idempotencyKey != null) {
			Optional<PointUseCancelResponse> cached = checkIdempotency(useTxn.getUserId(),
					IdempotencyKeyRecord.OperationType.USE_CANCEL, idempotencyKey, requestHash, PointUseCancelResponse.class);
			if (cached.isPresent()) {
				return cached.get();
			}
		}

		PointPolicy policy = currentPolicy(now);

		List<PointUseDetail> details = useDetailRepository.findByUseTransactionIdOrderById(useTxn.getId());
		long cancelableTotal = sumExact(details.stream().mapToLong(PointUseDetail::cancelableAmount));
		if (request.amount() > cancelableTotal) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "취소 가능 금액을 초과했습니다.");
		}

		PointTransaction cancelTxn = PointTransaction.useCancel(account.getId(), useTxn.getUserId(),
				request.amount(), useTxn.getOrderNo(), useTxn.getId(), null, now);
		transactionRepository.save(cancelTxn);

		// details 개수만큼 findById를 반복하면 N+1이 된다 — 대상 lot을 한 번에 모아서 가져온다.
		Map<Long, PointLot> originLotsByLotId = lotRepository
				.findAllById(details.stream().map(PointUseDetail::getPointLotId).distinct().toList())
				.stream()
				.collect(Collectors.toMap(PointLot::getId, Function.identity()));

		List<PointUseCancelResponse.Restoration> restorations = new ArrayList<>();
		long remaining = request.amount();
		for (PointUseDetail detail : details) {
			if (remaining <= 0) {
				break;
			}
			long take = Math.min(detail.cancelableAmount(), remaining);
			if (take <= 0) {
				continue;
			}
			detail.cancel(take, now);
			restorations.add(restoreAllocation(cancelTxn, detail, take, policy, originLotsByLotId, now));
			remaining -= take;
		}

		long balance = lotRepository.sumBalance(useTxn.getUserId(), PointLot.Status.ACTIVE, now);
		PointUseCancelResponse response = new PointUseCancelResponse(cancelTxn.getPointKey(), request.amount(), balance, restorations);
		saveIdempotentResponseIfPresent(useTxn.getUserId(), IdempotencyKeyRecord.OperationType.USE_CANCEL, idempotencyKey,
				requestHash, response, now);
		return response;
	}

	public PointBalanceResponse getBalance(Long userId) {
		long balance = lotRepository.sumBalance(userId, PointLot.Status.ACTIVE, LocalDateTime.now(clock));
		return new PointBalanceResponse(balance);
	}

	public PageResponse<PointLotResponse> getLots(Long userId, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now(clock);
		Page<PointLotResponse> page = lotRepository.findByUserId(userId, pageable)
				.map(lot -> new PointLotResponse(lot.getPointKey(), lot.getId(), lot.getAmount(),
						lot.getRemainingAmount(), lot.getExpireAt(), lot.getStatus().name(),
						lot.isUsable(now), lot.isExpired(now),
						lot.getEarnSource() == PointLot.EarnSource.MANUAL, lot.getCreatedAt()));
		return PageResponse.from(page);
	}

	public PageResponse<PointTransactionResponse> getTransactions(Long userId, Pageable pageable) {
		Page<PointTransactionResponse> page = transactionRepository.findByUserId(userId, pageable)
				.map(txn -> new PointTransactionResponse(txn.getPointKey(), txn.getTransactionType().name(),
						txn.getAmount(), txn.getOrderNo(), txn.getRelatedTransactionId(), txn.getCreatedAt()));
		return PageResponse.from(page);
	}

	private PointUseCancelResponse.Restoration restoreAllocation(PointTransaction cancelTxn, PointUseDetail detail,
			long amount, PointPolicy policy, Map<Long, PointLot> originLotsByLotId, LocalDateTime now) {
		PointLot originLot = originLotsByLotId.get(detail.getPointLotId());
		if (originLot == null) {
			throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "원본 적립분을 찾을 수 없습니다.");
		}

		if (!originLot.isExpired(now)) {
			originLot.restore(amount, now);
			useCancelDetailRepository.save(PointUseCancelDetail.restoredToLot(
					cancelTxn.getId(), detail.getId(), originLot.getId(), amount, now));
			return new PointUseCancelResponse.Restoration(originLot.getId(), amount, false, null);
		}

		LocalDateTime reissueExpireAt = now.plusDays(policy.resolveExpireDays(null));
		PointTransaction reissueTxn = PointTransaction.earn(cancelTxn.getAccountId(), cancelTxn.getUserId(),
				amount, "USE_CANCEL_REISSUE", now);
		transactionRepository.save(reissueTxn);

		PointLot reissuedLot = PointLot.reissue(reissueTxn.getPointKey(), cancelTxn.getUserId(), reissueTxn.getId(),
				policy.getId(), originLot.getEarnSource(), originLot.getUsePriority(),
				originLot.getId(), amount, reissueExpireAt, now);
		lotRepository.save(reissuedLot);

		useCancelDetailRepository.save(PointUseCancelDetail.reissuedAsNewLot(
				cancelTxn.getId(), detail.getId(), originLot.getId(), amount, reissuedLot.getId(), now));
		return new PointUseCancelResponse.Restoration(originLot.getId(), amount, true, reissueTxn.getPointKey());
	}

	private PointAccount resolveAccountForUpdate(Long userId) {
		return accountRepository.findByUserIdForUpdate(userId)
				.orElseGet(() -> {
					try {
						accountProvisioner.provision(userId);
					} catch (DataIntegrityViolationException e) {
						// 동시에 같은 사용자의 최초 적립이 경합해 상대가 먼저 계정을 만든 경우 — 아래 재조회로 넘어간다.
						// 정상적으로 처리되는 경쟁이라 에러는 아니지만, 실제 운영에서 이 경합이 얼마나
						// 자주 일어나는지(신규 유입 트래픽 패턴 파악)는 DEBUG로 남겨서 추적 가능하게 한다.
						log.debug("계정 생성 경합 감지, 재조회로 진행합니다: userId={}", userId);
					}
					return accountRepository.findByUserIdForUpdate(userId)
							.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_PROVISIONING_FAILED));
				});
	}

	/**
	 * (user_id, operation_type, idempotency_key)가 같아도 요청 "내용"이 다르면 재시도가 아니라
	 * 키 오용이다 — 예를 들어 같은 키로 적립취소 A를 성공시킨 뒤 같은 키로 적립취소 B를 요청하면,
	 * B의 응답이 되어야 할 자리에 A의 캐시된 응답이 그대로 나가버릴 수 있다. requestHash로 저장된
	 * 요청 지문과 현재 요청 지문을 비교해, 같으면 캐시를 반환하고 다르면 거절한다.
	 */
	private <T> Optional<T> checkIdempotency(long userId, IdempotencyKeyRecord.OperationType operationType,
			String idempotencyKey, String requestHash, Class<T> responseType) {
		return idempotencyKeyRepository
				.findByUserIdAndOperationTypeAndIdempotencyKey(userId, operationType, idempotencyKey)
				.map(record -> {
					if (!record.getRequestHash().equals(requestHash)) {
						throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
					}
					return readJson(record.getResponseBody(), responseType);
				});
	}

	private void saveIdempotentResponseIfPresent(long userId, IdempotencyKeyRecord.OperationType operationType,
			String idempotencyKey, String requestHash, Object response, LocalDateTime now) {
		if (idempotencyKey == null) {
			return;
		}
		try {
			// save()가 아니라 saveAndFlush()를 쓴다 — JPA는 save()를 호출해도 실제 INSERT를 트랜잭션
			// commit 시점까지 미룰 수 있는데, 그러면 UK 위반이 이 메서드의 try-catch 밖(커밋 시점)에서
			// 터져 여기서 못 잡는다. (지금은 id가 IDENTITY 전략이라 save()만으로도 매번 즉시 flush되긴
			// 하지만, 그건 우연한 구현 세부사항이지 보장이 아니다.) saveAndFlush()로 이 메서드 안에서
			// 즉시 INSERT를 실행시켜, 아래 catch가 "최종 안전망"이라는 이름값을 실제로 하게 만든다.
			idempotencyKeyRepository.saveAndFlush(IdempotencyKeyRecord.create(
					userId, operationType, idempotencyKey, requestHash, writeJson(response), now));
		} catch (DataIntegrityViolationException e) {
			// point_account 락을 먼저 잡고 나서야 idempotency_key를 조회·저장하므로(checkIdempotency 호출부
			// 참고), 같은 (user_id, operation_type, idempotency_key)를 노리는 두 요청은 계정 락 단계에서부터
			// 이미 직렬화되어 여기서 UK 충돌이 날 일이 없다 — 이 catch는 그 불변식이 깨질 미래의 변경(락 순서
			// 변경, 배치성 재처리 등)에 대비한 최종 안전망이다. 원인을 알 수 없는 500 대신 명확한 충돌
			// 비즈니스 예외로 변환한다. 클라이언트 응답은 409(4xx)라 GlobalExceptionHandler의 5xx 자동
			// 로깅을 안 타므로, 여기서 직접 WARN을 남긴다 — "이 불변식이 실제로 깨졌다"는 신호이기 때문에
			// 일반적인 IDEMPOTENCY_KEY_REUSED(클라이언트가 키를 잘못 재사용한 경우)보다 훨씬 심각하다.
			log.warn("idempotency_key UK 충돌 — 계정 락으로 직렬화됐어야 할 요청이 경합했습니다: "
					+ "userId={}, operationType={}, idempotencyKey={}", userId, operationType, idempotencyKey, e);
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"동시 요청으로 인한 멱등성 키 충돌이 발생했습니다. 잠시 후 다시 시도해주세요.");
		}
	}

	/**
	 * 헤더로 그대로 들어오는 값이라 `null`(헤더 미전송)과 "빈 값을 보낸 것"을 구분해서 막아야 한다.
	 * 공백 키가 통과하면 서로 다른 요청들이 전부 "" 하나의 키로 뭉쳐 캐시가 잘못 히트될 수 있고,
	 * 길이·제어문자 제한이 없으면 DB 컬럼(request_hash와 달리 idempotency_key는 원문 그대로 저장)에
	 * 임의 크기·형태의 값이 그대로 들어간다. null(미전송)은 정상 — 멱등성 기능을 쓰지 않는 것뿐이다.
	 */
	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null) {
			return;
		}
		if (idempotencyKey.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key는 공백일 수 없습니다.");
		}
		if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
					"Idempotency-Key는 " + MAX_IDEMPOTENCY_KEY_LENGTH + "자를 넘을 수 없습니다.");
		}
		if (idempotencyKey.chars().anyMatch(Character::isISOControl)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key에 제어 문자를 포함할 수 없습니다.");
		}
	}

	/**
	 * '|' 같은 구분자로 파트를 이어붙이기만 하면, 값 자체에 구분자가 들어있는 경우
	 * (["a", "b|c"] vs ["a|b", "c"])나 Java null과 문자열 리터럴 "null"이 같은 텍스트를 만들어
	 * 서로 다른 요청이 같은 지문으로 충돌할 수 있었다(실제로 memo=null과 memo="null"이 충돌했다).
	 * JSON 배열로 직렬화하면 각 값이 타입에 맞게 이스케이프·구분되어(문자열은 따옴표, null은
	 * unquoted null) 이런 충돌이 구조적으로 불가능해진다.
	 */
	private String requestHash(Object... parts) {
		String canonicalJson;
		try {
			canonicalJson = objectMapper.writeValueAsString(parts);
		} catch (JsonProcessingException e) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CODEC_FAILED);
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256은 모든 JVM에 필수 구현이므로 발생할 수 없다.", e);
		}
	}

	/**
	 * JSON 직렬화/역직렬화 실패는 클라이언트가 원인을 파악하거나 재시도로 해결할 수 있는 문제가 아니다
	 * (요청 값 문제가 아니라 서버 쪽 저장/복원 로직의 문제) — 메시지에는 "멱등", "직렬화" 같은 내부
	 * 구현 용어를 노출하지 않되, 코드(`IDEMPOTENCY_CODEC_FAILED`)는 다른 500 원인과 구분해서
	 * 운영 중 로그/모니터링에서 원인을 바로 알 수 있게 한다.
	 */
	private <T> T readJson(String json, Class<T> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (JsonProcessingException e) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CODEC_FAILED);
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CODEC_FAILED);
		}
	}

	private PointPolicy currentPolicy(LocalDateTime now) {
		return policyRepository.findFirstByAppliedFromLessThanEqualOrderByAppliedFromDescIdDesc(now)
				.orElseThrow(() -> new BusinessException(ErrorCode.POLICY_NOT_CONFIGURED));
	}

	/**
	 * LongStream.sum()은 오버플로 시 조용히 값을 감싸(wrap) 음수 등 잘못된 합계를 만들 수 있다.
	 * Math.addExact로 누적해 오버플로 시 즉시 예외로 드러낸다.
	 */
	private long sumExact(LongStream stream) {
		try {
			return stream.reduce(0L, Math::addExact);
		} catch (ArithmeticException e) {
			throw new BusinessException(ErrorCode.AMOUNT_OVERFLOW);
		}
	}

}
