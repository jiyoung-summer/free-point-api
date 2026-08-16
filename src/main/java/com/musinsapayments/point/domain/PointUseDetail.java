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
 * 사용 상세. (사용거래 x 적립분) 매핑으로 1원 단위 사용 추적과 반복 부분취소를 지원한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_use_detail", indexes = {
		@Index(name = "idx_point_use_detail_use_transaction", columnList = "use_transaction_id"),
		@Index(name = "idx_point_use_detail_point_lot", columnList = "point_lot_id"),
		@Index(name = "idx_point_use_detail_order_no", columnList = "order_no")
})
public class PointUseDetail {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private long useTransactionId;

	@Column(nullable = false)
	private long pointLotId;

	@Column(nullable = false)
	private long userId;

	@Column(nullable = false, length = 50)
	private String orderNo;

	@Column(nullable = false)
	private long amount;

	@Column(nullable = false)
	private long canceledAmount;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	private PointUseDetail(long useTransactionId, long pointLotId, long userId, String orderNo,
			long amount, LocalDateTime now) {
		this.useTransactionId = useTransactionId;
		this.pointLotId = pointLotId;
		this.userId = userId;
		this.orderNo = orderNo;
		this.amount = amount;
		this.canceledAmount = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static PointUseDetail of(long useTransactionId, long pointLotId, long userId, String orderNo,
			long amount, LocalDateTime now) {
		return new PointUseDetail(useTransactionId, pointLotId, userId, orderNo, amount, now);
	}

	public long cancelableAmount() {
		return amount - canceledAmount;
	}

	public void cancel(long cancelAmount, LocalDateTime now) {
		if (cancelAmount > cancelableAmount()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "취소 가능 금액을 초과했습니다.");
		}
		this.canceledAmount += cancelAmount;
		this.updatedAt = now;
	}

}
