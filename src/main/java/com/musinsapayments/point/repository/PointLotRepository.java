package com.musinsapayments.point.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.musinsapayments.point.domain.PointLot;

public interface PointLotRepository extends JpaRepository<PointLot, Long> {

	/**
	 * 만료 배치가 없어(README "남은 과제") status는 expireAt이 지나도 ACTIVE로 남을 수 있다.
	 * 그래서 "지금 실제로 사용 가능/보유 잔액에 포함되는 적립분"을 뜻하는 조건은 항상
	 * {@code status = ACTIVE AND expireAt > now} 둘 다여야 한다 — status만 걸면 만료된
	 * 포인트가 잔액/통계/정산에 그대로 섞여 들어간다.
	 *
	 * <p>새 조회·통계·정산 쿼리를 추가할 때는 status만 필터링하지 말고 반드시 이 상수를
	 * 함께 쓰거나(JPQL), {@link com.musinsapayments.point.domain.PointLot#isUsable}로
	 * 애플리케이션 레벨에서 다시 한번 걸러라. {@code PointLotUsableConditionTest}가 이 조건이
	 * 실제로 빠지지 않았는지 회귀 테스트로 지킨다.
	 */
	String USABLE_CONDITION = "l.status = :status and l.expireAt > :now";

	Optional<PointLot> findByPointKey(String pointKey);

	long countByUserId(Long userId);

	/**
	 * 적립분 목록 조회(만료/취소 포함 전체 이력). 정렬은 호출부의 Pageable에 맡긴다.
	 * 전체 이력 조회이므로 일부러 USABLE_CONDITION을 걸지 않는다 — 잔액 계산에는 쓰지 말 것.
	 */
	Page<PointLot> findByUserId(Long userId, Pageable pageable);

	/**
	 * 사용 가능한 적립분을 소진 우선순위(수기지급 우선 → 만료 임박 순 → 오래된 순)로 조회한다.
	 */
	@Query("select l from PointLot l "
			+ "where l.userId = :userId and " + USABLE_CONDITION + " and l.remainingAmount > 0 "
			+ "order by l.usePriority asc, l.expireAt asc, l.id asc")
	List<PointLot> findUsableLotsForAllocation(@Param("userId") Long userId,
			@Param("status") PointLot.Status status, @Param("now") LocalDateTime now);

	/**
	 * 사용 가능 잔액 합계. 잔액은 별도 컬럼으로 두지 않고 항상 이 값으로 계산한다.
	 */
	@Query("select coalesce(sum(l.remainingAmount), 0) from PointLot l "
			+ "where l.userId = :userId and " + USABLE_CONDITION)
	long sumBalance(@Param("userId") Long userId, @Param("status") PointLot.Status status,
			@Param("now") LocalDateTime now);

}
