package com.musinsapayments.point.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.musinsapayments.point.domain.PointTransaction;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

	Optional<PointTransaction> findByPointKey(String pointKey);

	/**
	 * 거래 내역 조회(EARN/EARN_CANCEL/USE/USE_CANCEL 전체). 정렬은 호출부의 Pageable에 맡긴다.
	 */
	Page<PointTransaction> findByUserId(Long userId, Pageable pageable);

}
