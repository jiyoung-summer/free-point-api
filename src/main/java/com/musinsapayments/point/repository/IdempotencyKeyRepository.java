package com.musinsapayments.point.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.musinsapayments.point.domain.IdempotencyKeyRecord;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, Long> {

	Optional<IdempotencyKeyRecord> findByUserIdAndOperationTypeAndIdempotencyKey(
			long userId, IdempotencyKeyRecord.OperationType operationType, String idempotencyKey);

}
