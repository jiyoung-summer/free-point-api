package com.musinsapayments.point.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.musinsapayments.point.domain.PointAccount;

import jakarta.persistence.LockModeType;

public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {

	Optional<PointAccount> findByUserId(Long userId);

	long countByUserId(Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from PointAccount a where a.userId = :userId")
	Optional<PointAccount> findByUserIdForUpdate(@Param("userId") Long userId);

}
