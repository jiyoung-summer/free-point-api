package com.musinsapayments.point.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.musinsapayments.point.domain.PointPolicy;

public interface PointPolicyRepository extends JpaRepository<PointPolicy, Long> {

	/**
	 * appliedFrom만으로 정렬하면, 두 정책 행의 appliedFrom이 완전히 같은 시각일 때(초 단위보다
	 * 촘촘하게 연달아 추가되는 경우 등) 어느 쪽이 "유효 정책"인지가 DB에 따라 불특정해진다.
	 * id를 2차 정렬키로 둬 "같은 시각이면 나중에 추가된(더 큰 id) 쪽이 이긴다"를 결정적으로 만든다.
	 */
	Optional<PointPolicy> findFirstByAppliedFromLessThanEqualOrderByAppliedFromDescIdDesc(LocalDateTime now);

}
