package com.musinsapayments.point.domain;

import java.time.LocalDateTime;

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
 * 사용취소 상세. 어떤 사용상세를 얼마나 되돌렸는지, 만료로 인한 재적립 여부를 기록한다.
 * useDetailId 가 source of truth이고, pointLotId 는 조회 편의를 위한 비정규화 값(도출 가능)이다.
 * reissuedLotId 가 채워져 있으면 "만료되어 신규 적립분으로 재발급됨"을 의미한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_use_cancel_detail", indexes = {
		@Index(name = "idx_use_cancel_detail_cancel_transaction", columnList = "cancel_transaction_id"),
		@Index(name = "idx_use_cancel_detail_use_detail", columnList = "use_detail_id"),
		@Index(name = "idx_use_cancel_detail_point_lot", columnList = "point_lot_id"),
		@Index(name = "idx_use_cancel_detail_reissued_lot", columnList = "reissued_lot_id")
})
public class PointUseCancelDetail {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private long cancelTransactionId;

	@Column(nullable = false)
	private long useDetailId;

	@Column(nullable = false)
	private long pointLotId;

	@Column(nullable = false)
	private long amount;

	private Long reissuedLotId;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PointUseCancelDetail(long cancelTransactionId, long useDetailId, long pointLotId, long amount,
			Long reissuedLotId, LocalDateTime now) {
		this.cancelTransactionId = cancelTransactionId;
		this.useDetailId = useDetailId;
		this.pointLotId = pointLotId;
		this.amount = amount;
		this.reissuedLotId = reissuedLotId;
		this.createdAt = now;
	}

	public static PointUseCancelDetail restoredToLot(long cancelTransactionId, long useDetailId, long pointLotId,
			long amount, LocalDateTime now) {
		return new PointUseCancelDetail(cancelTransactionId, useDetailId, pointLotId, amount, null, now);
	}

	public static PointUseCancelDetail reissuedAsNewLot(long cancelTransactionId, long useDetailId, long pointLotId,
			long amount, long reissuedLotId, LocalDateTime now) {
		return new PointUseCancelDetail(cancelTransactionId, useDetailId, pointLotId, amount, reissuedLotId, now);
	}

}
