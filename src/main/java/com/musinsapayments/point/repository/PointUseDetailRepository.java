package com.musinsapayments.point.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.musinsapayments.point.domain.PointUseDetail;

public interface PointUseDetailRepository extends JpaRepository<PointUseDetail, Long> {

	List<PointUseDetail> findByUseTransactionIdOrderById(Long useTransactionId);

}
