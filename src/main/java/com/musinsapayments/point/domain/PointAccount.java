package com.musinsapayments.point.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 계정. 잔액 변경(적립/적립취소/사용/사용취소)의 비관적 락(FOR UPDATE) 기준점.
 * balance 컬럼은 두지 않는다 — 잔액은 PointLot.remainingAmount 합계로 계산한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_account")
public class PointAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private Long userId;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PointAccount(Long userId, LocalDateTime now) {
		this.userId = userId;
		this.createdAt = now;
	}

	public static PointAccount open(Long userId, LocalDateTime now) {
		return new PointAccount(userId, now);
	}

}
