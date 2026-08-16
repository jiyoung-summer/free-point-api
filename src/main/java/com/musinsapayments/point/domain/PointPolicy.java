package com.musinsapayments.point.domain;

import java.time.LocalDateTime;

import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정책 "버전" 원장. append-only, 기존 행은 수정하지 않는다.
 * 유효 정책 = appliedFrom <= now() 인 행 중 가장 최근 1건.
 * 1회 적립 한도 / 개인별 최대 보유 한도처럼 하드코딩이 금지된 값을 여기서 런타임으로 제어한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_policy",
		indexes = @Index(name = "idx_point_policy_applied_from", columnList = "applied_from"))
public class PointPolicy {

	/**
	 * 정책 금액의 현실적 상한. long 합산 오버플로를 원천 차단하기 위한 방어선이다 —
	 * 이 값 이하로만 정책을 만들 수 있게 하면, 활성 적립분 개수가 아무리 많아도
	 * (Long.MAX_VALUE / MAX_REALISTIC_AMOUNT ≈ 922만 건) 합산이 실질적으로 오버플로되지 않는다.
	 */
	private static final long MAX_REALISTIC_AMOUNT = 1_000_000_000_000L; // 1조

	/** 만료일 상한. 요구사항 "만료일 1일~5년 미만"을 도메인이 직접 보장한다. */
	private static final int MAX_ALLOWED_EXPIRE_DAYS = 1824;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String versionLabel;

	@Column(nullable = false)
	private long minEarnAmount;

	@Column(nullable = false)
	private long maxEarnAmount;

	@Column(nullable = false)
	private long maxHoldAmount;

	@Column(nullable = false)
	private int defaultExpireDays;

	@Column(nullable = false)
	private int minExpireDays;

	@Column(nullable = false)
	private int maxExpireDays;

	@Column(nullable = false)
	private LocalDateTime appliedFrom;

	@Column(length = 100)
	private String changedBy;

	@Column(length = 500)
	private String changeReason;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PointPolicy(String versionLabel, long minEarnAmount, long maxEarnAmount, long maxHoldAmount,
			int defaultExpireDays, int minExpireDays, int maxExpireDays, LocalDateTime appliedFrom,
			String changedBy, String changeReason, LocalDateTime now) {
		this.versionLabel = versionLabel;
		this.minEarnAmount = minEarnAmount;
		this.maxEarnAmount = maxEarnAmount;
		this.maxHoldAmount = maxHoldAmount;
		this.defaultExpireDays = defaultExpireDays;
		this.minExpireDays = minExpireDays;
		this.maxExpireDays = maxExpireDays;
		this.appliedFrom = appliedFrom;
		this.changedBy = changedBy;
		this.changeReason = changeReason;
		this.createdAt = now;
	}

	public static PointPolicy create(String versionLabel, long minEarnAmount, long maxEarnAmount, long maxHoldAmount,
			int defaultExpireDays, int minExpireDays, int maxExpireDays, LocalDateTime appliedFrom,
			String changedBy, String changeReason, LocalDateTime now) {
		if (versionLabel == null || versionLabel.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "정책 버전 라벨은 비어 있을 수 없습니다.");
		}
		if (versionLabel.length() > 100) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "정책 버전 라벨은 100자를 넘을 수 없습니다.");
		}
		if (appliedFrom == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "정책 적용 시각(appliedFrom)은 필수입니다.");
		}
		if (minEarnAmount <= 0 || maxEarnAmount < minEarnAmount) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "적립 한도 범위가 올바르지 않습니다.");
		}
		if (maxHoldAmount <= 0) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "최대 보유 한도가 올바르지 않습니다.");
		}
		if (maxEarnAmount > MAX_REALISTIC_AMOUNT || maxHoldAmount > MAX_REALISTIC_AMOUNT) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
					"적립/보유 한도는 " + MAX_REALISTIC_AMOUNT + "을 넘을 수 없습니다.");
		}
		if (minExpireDays < 1 || minExpireDays > maxExpireDays) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "만료일 범위가 올바르지 않습니다.");
		}
		if (maxExpireDays > MAX_ALLOWED_EXPIRE_DAYS) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
					"만료일 상한은 " + MAX_ALLOWED_EXPIRE_DAYS + "일(5년 미만)을 넘을 수 없습니다.");
		}
		if (defaultExpireDays < minExpireDays || defaultExpireDays > maxExpireDays) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "기본 만료일이 허용 범위를 벗어났습니다.");
		}
		if (changedBy != null && changedBy.length() > 100) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "changedBy는 100자를 넘을 수 없습니다.");
		}
		if (changeReason != null && changeReason.length() > 500) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "changeReason은 500자를 넘을 수 없습니다.");
		}
		return new PointPolicy(versionLabel, minEarnAmount, maxEarnAmount, maxHoldAmount,
				defaultExpireDays, minExpireDays, maxExpireDays, appliedFrom, changedBy, changeReason, now);
	}

	public void validateEarnAmount(long amount) {
		if (amount < minEarnAmount || amount > maxEarnAmount) {
			throw new BusinessException(ErrorCode.EARN_AMOUNT_OUT_OF_POLICY);
		}
	}

	/**
	 * 적립 후 보유 한도를 검증하고, 검증에 사용한 합산값(적립 후 예상 잔액)을 그대로 돌려준다.
	 * 호출부가 같은 값을 다시 더해 재계산하지 않도록 하기 위함이다 — currentBalance + amount를
	 * 두 번 계산하면 그중 한쪽만 오버플로 검사를 거치는 실수가 생기기 쉽다.
	 */
	public long validateHoldLimit(long currentBalance, long amount) {
		long projectedBalance;
		try {
			projectedBalance = Math.addExact(currentBalance, amount);
		} catch (ArithmeticException e) {
			throw new BusinessException(ErrorCode.AMOUNT_OVERFLOW, "보유 한도 계산 중 금액 합계가 처리 가능한 범위를 초과했습니다.");
		}
		if (projectedBalance > maxHoldAmount) {
			throw new BusinessException(ErrorCode.HOLD_LIMIT_EXCEEDED);
		}
		return projectedBalance;
	}

	public int resolveExpireDays(Integer requestedDays) {
		int days = (requestedDays != null) ? requestedDays : defaultExpireDays;
		if (days < minExpireDays || days > maxExpireDays) {
			throw new BusinessException(ErrorCode.EXPIRE_DAYS_OUT_OF_RANGE);
		}
		return days;
	}

}
