package com.musinsapayments.point.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * (user_id, operation_type, idempotency_key) 조합으로 최초 처리 응답(JSON)을 저장한다.
 * 클라이언트가 재시도 시 같은 Idempotency-Key를 보내면, 서비스는 비즈니스 로직을 다시 실행하지 않고
 * 여기 저장된 응답을 그대로 반환한다. operation_type을 함께 키에 넣는 이유는 클라이언트가 같은 키를
 * 실수로 재사용해도 적립과 사용처럼 서로 다른 작업까지 뒤섞여 막히지 않게 하기 위함이다.
 *
 * requestHash는 "같은 키로 다른 내용의 요청을 보내는" 오용을 잡아내기 위한 지문(fingerprint)이다.
 * (user_id, operation_type, idempotency_key)만으로는 요청 내용 자체를 비교할 수 없어서, 예를 들어
 * 같은 키로 적립취소 A를 성공시킨 뒤 같은 키로 적립취소 B를 요청하면 B의 응답이 되어야 할 자리에
 * A의 캐시된 응답이 그대로 반환되는 문제가 있었다 — requestHash가 다르면 캐시를 반환하지 않고
 * IDEMPOTENCY_KEY_REUSED로 거절한다(PointService 참고).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "idempotency_key",
		uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key",
				columnNames = {"user_id", "operation_type", "idempotency_key"}))
public class IdempotencyKeyRecord {

	public enum OperationType {
		EARN, MANUAL_EARN, EARN_CANCEL, USE, USE_CANCEL
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "operation_type", nullable = false, length = 30)
	private OperationType operationType;

	@Column(name = "idempotency_key", nullable = false, length = 200)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	@Lob
	@Column(name = "response_body", nullable = false)
	private String responseBody;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private IdempotencyKeyRecord(long userId, OperationType operationType, String idempotencyKey,
			String requestHash, String responseBody, LocalDateTime now) {
		this.userId = userId;
		this.operationType = operationType;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.responseBody = responseBody;
		this.createdAt = now;
	}

	public static IdempotencyKeyRecord create(long userId, OperationType operationType, String idempotencyKey,
			String requestHash, String responseBody, LocalDateTime now) {
		return new IdempotencyKeyRecord(userId, operationType, idempotencyKey, requestHash, responseBody, now);
	}

}
