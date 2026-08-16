package com.musinsapayments.point.domain;

import java.time.LocalDateTime;

import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 적립 단위(Lot). EARN 거래 1건 = 1행이며, 적립금액/사용가능잔액/만료일/적립출처를 보유한다.
 * usePriority 는 earnSource 에 매핑되는 값으로, 값이 낮을수록 먼저 소진된다(수기지급 우선).
 * originLotId 는 만료된 적립분을 사용취소로 재적립할 때만 원본 Lot 을 가리킨다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_lot", indexes = {
		// 사용 가능 Lot 조회(findUsableLotsForAllocation) + 잔액 합계(sumBalance)가 공유하는
		// 조건(user_id, status, expire_at)과 소진 우선순위 정렬(use_priority, expire_at, id)을 함께 커버한다.
		@Index(name = "idx_point_lot_usable", columnList = "user_id, status, use_priority, expire_at, id"),
		@Index(name = "idx_point_lot_policy", columnList = "policy_id"),
		@Index(name = "idx_point_lot_origin", columnList = "origin_lot_id"),
		// earnCancel()의 findByPointKey가 이 인덱스 없이는 point_lot 전체를 스캔한다.
		@Index(name = "idx_point_lot_point_key", columnList = "point_key")
})
public class PointLot {

	public enum EarnSource {
		ORDER, EVENT, MANUAL, COMPENSATION
	}

	public enum Status {
		ACTIVE, EXPIRED, CANCELED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String pointKey;

	@Column(nullable = false)
	private long userId;

	@Column(nullable = false, unique = true)
	private long earnTransactionId;

	@Column(nullable = false)
	private long policyId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private EarnSource earnSource;

	@Column(nullable = false)
	private int usePriority;

	private Long originLotId;

	@Column(nullable = false)
	private long amount;

	@Column(nullable = false)
	private long remainingAmount;

	@Column(nullable = false)
	private LocalDateTime expireAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	private PointLot(String pointKey, long userId, long earnTransactionId, long policyId, EarnSource earnSource,
			int usePriority, Long originLotId, long amount, LocalDateTime expireAt, LocalDateTime now) {
		this.pointKey = pointKey;
		this.userId = userId;
		this.earnTransactionId = earnTransactionId;
		this.policyId = policyId;
		this.earnSource = earnSource;
		this.usePriority = usePriority;
		this.originLotId = originLotId;
		this.amount = amount;
		this.remainingAmount = amount;
		this.expireAt = expireAt;
		this.status = Status.ACTIVE;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static PointLot earn(String pointKey, long userId, long earnTransactionId, long policyId,
			EarnSource earnSource, int usePriority, long amount, LocalDateTime expireAt, LocalDateTime now) {
		return new PointLot(pointKey, userId, earnTransactionId, policyId, earnSource, usePriority,
				null, amount, expireAt, now);
	}

	public static PointLot reissue(String pointKey, long userId, long earnTransactionId, long policyId,
			EarnSource earnSource, int usePriority, long originLotId, long amount, LocalDateTime expireAt, LocalDateTime now) {
		return new PointLot(pointKey, userId, earnTransactionId, policyId, earnSource, usePriority,
				originLotId, amount, expireAt, now);
	}

	public boolean isUsable(LocalDateTime now) {
		return status == Status.ACTIVE && remainingAmount > 0 && expireAt.isAfter(now);
	}

	public boolean isExpired(LocalDateTime now) {
		return status == Status.EXPIRED || !expireAt.isAfter(now);
	}

	public void use(long useAmount, LocalDateTime now) {
		if (useAmount <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "사용 금액은 0보다 커야 합니다.");
		}
		if (!isUsable(now)) {
			throw new BusinessException(ErrorCode.POINT_LOT_NOT_USABLE);
		}
		if (remainingAmount < useAmount) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
		}
		this.remainingAmount -= useAmount;
		this.updatedAt = now;
	}

	public void restore(long restoreAmount, LocalDateTime now) {
		if (restoreAmount <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "복원 금액은 0보다 커야 합니다.");
		}
		if (status != Status.ACTIVE) {
			throw new BusinessException(ErrorCode.POINT_LOT_NOT_USABLE, "ACTIVE 상태의 적립분만 복원할 수 있습니다.");
		}
		if (isExpired(now)) {
			throw new BusinessException(ErrorCode.POINT_LOT_NOT_USABLE, "만료된 적립분은 복원할 수 없습니다.");
		}
		long restoredAmount;
		try {
			restoredAmount = Math.addExact(remainingAmount, restoreAmount);
		} catch (ArithmeticException e) {
			throw new BusinessException(ErrorCode.AMOUNT_OVERFLOW);
		}
		if (restoredAmount > amount) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "복원 후 잔액이 원래 적립 금액을 초과할 수 없습니다.");
		}
		this.remainingAmount = restoredAmount;
		this.updatedAt = now;
	}

	/**
	 * 무상 포인트는 만료되면 소멸한다 — 이미 소멸된 적립분은 "취소"할 대상 자체가 없으므로
	 * (한 번도 사용되지 않았더라도) 만료된 lot은 적립취소할 수 없다.
	 */
	public void cancelEarn(LocalDateTime now) {
		if (status == Status.CANCELED) {
			throw new BusinessException(ErrorCode.LOT_ALREADY_CANCELED);
		}
		if (isExpired(now)) {
			throw new BusinessException(ErrorCode.POINT_LOT_NOT_USABLE, "만료된 적립분은 취소할 수 없습니다.");
		}
		if (remainingAmount != amount) {
			throw new BusinessException(ErrorCode.PARTIALLY_USED_LOT_CANNOT_BE_CANCELED);
		}
		this.status = Status.CANCELED;
		this.remainingAmount = 0;
		this.updatedAt = now;
	}

	public void expire(LocalDateTime now) {
		if (status == Status.ACTIVE) {
			this.status = Status.EXPIRED;
			this.updatedAt = now;
		}
	}

}
