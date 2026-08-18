package com.musinsapayments.point.domain;

import java.time.LocalDateTime;
import java.util.UUID;

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
 * 거래 원장. 적립/적립취소/사용/사용취소 행위가 1건씩 append-only 로 기록된다.
 * pointKey 는 서버가 발급하는 거래 식별자다.
 * 요청 재시도 멱등성은 IdempotencyKeyRecord가 담당한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_transaction", indexes = {
		// 거래 내역 조회(PointTransactionRepository.findByUserId)는 account_id가 아니라
		// user_id로 필터링한다 — user_id는 "조회 편의를 위한 비정규화 컬럼"이라고 문서화해놓고
		// 정작 인덱스는 실제로 쓰이지 않는 account_id 기준으로 잡아뒀던 것을 바로잡았다.
		@Index(name = "idx_point_transaction_user_created", columnList = "user_id, created_at"),
		@Index(name = "idx_point_transaction_related", columnList = "related_transaction_id")
})
public class PointTransaction {

	public enum Type {
		EARN, EARN_CANCEL, USE, USE_CANCEL
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 40)
	private String pointKey;

	@Column(nullable = false)
	private long accountId;

	@Column(nullable = false)
	private long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Type transactionType;

	@Column(nullable = false)
	private long amount;

	@Column(length = 50)
	private String orderNo;

	private Long relatedTransactionId;

	@Column(length = 30)
	private String displayCode;

	// 호출자 측 외부 시스템(주문/결제 등)의 거래 ID. 거래 추적을 위해 그대로 보관하는 참고값일 뿐이며,
	// 재시도 멱등성 판단에는 전혀 관여하지 않는다(그 역할은 IdempotencyKeyRecord가 전담한다).
	// 값 중복은 정상이라 별도 유니크 제약을 두지 않는다.
	@Column(length = 100)
	private String clientTransactionId;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PointTransaction(long accountId, long userId, Type transactionType, long amount,
			String orderNo, Long relatedTransactionId, String displayCode, String clientTransactionId, LocalDateTime now) {
		this.pointKey = UUID.randomUUID().toString();
		this.accountId = accountId;
		this.userId = userId;
		this.transactionType = transactionType;
		this.amount = amount;
		this.orderNo = orderNo;
		this.relatedTransactionId = relatedTransactionId;
		this.displayCode = displayCode;
		this.clientTransactionId = clientTransactionId;
		this.createdAt = now;
	}

	public static PointTransaction earn(long accountId, long userId, long amount, String displayCode,
			String clientTransactionId, LocalDateTime now) {
		return new PointTransaction(accountId, userId, Type.EARN, amount, null, null, displayCode, clientTransactionId, now);
	}

	public static PointTransaction earnCancel(long accountId, long userId, long amount,
			long relatedTransactionId, String displayCode, String clientTransactionId, LocalDateTime now) {
		return new PointTransaction(accountId, userId, Type.EARN_CANCEL, amount, null, relatedTransactionId,
				displayCode, clientTransactionId, now);
	}

	public static PointTransaction use(long accountId, long userId, long amount, String orderNo,
			String displayCode, String clientTransactionId, LocalDateTime now) {
		return new PointTransaction(accountId, userId, Type.USE, amount, orderNo, null, displayCode, clientTransactionId, now);
	}

	public static PointTransaction useCancel(long accountId, long userId, long amount, String orderNo,
			long relatedTransactionId, String displayCode, String clientTransactionId, LocalDateTime now) {
		return new PointTransaction(accountId, userId, Type.USE_CANCEL, amount, orderNo, relatedTransactionId,
				displayCode, clientTransactionId, now);
	}

}
