package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;

import com.musinsapayments.point.domain.PointLot;
import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.repository.PointLotRepository;
import com.musinsapayments.point.support.MutableClock;

/**
 * 만료 배치가 없어 status는 expireAt이 지나도 ACTIVE로 남는다(README "남은 과제").
 * "지금 실제로 사용 가능/보유 잔액에 포함되는 적립분"인지 판단할 때 status만 보면
 * 만료된 포인트가 잔액에 섞여 들어간다 — sumBalance/findUsableLotsForAllocation이
 * PointLotRepository.USABLE_CONDITION(status=ACTIVE and expireAt>now)을 실제로
 * 계속 지키고 있는지 이 테스트가 회귀로 지킨다. 이 테스트가 깨진다면 둘 중 하나가
 * status만으로 필터링하도록 잘못 바뀐 것이다.
 */
@SpringBootTest
class PointLotUsableConditionTest {

	@TestConfiguration
	static class ClockTestConfig {
		@Bean
		@Primary
		Clock testClock() {
			return MutableClock.startingAt(Instant.parse("2026-01-01T00:00:00Z"));
		}
	}

	@Autowired
	private PointService pointService;

	@Autowired
	private PointLotRepository lotRepository;

	@Autowired
	private Clock clock;

	@Test
	void 만료됐지만_status가_여전히_ACTIVE인_lot은_잔액과_사용가능_목록에서_제외된다() {
		long userId = System.nanoTime();

		// 1일 만료로 적립하고 쓰지 않은 채 2일을 흘려보낸다: 만료 배치가 없으니 status는 ACTIVE, remainingAmount도 그대로.
		pointService.earn(new EarnRequest(userId, 1000, 1, null));
		((MutableClock) clock).advance(Duration.ofDays(2));

		// given: DB에 저장된 status는 여전히 ACTIVE이고 잔액도 그대로 남아있다 — 이게 이 테스트의 전제다.
		PointLot lot = lotRepository.findByUserId(userId, PageRequest.of(0, 10)).getContent().get(0);
		assertThat(lot.getStatus()).isEqualTo(PointLot.Status.ACTIVE);
		assertThat(lot.getRemainingAmount()).isEqualTo(1000);

		// when & then: 그럼에도 실제 사용 가능 잔액에는 포함되면 안 된다.
		assertThat(pointService.getBalance(userId).balance())
				.as("status=ACTIVE 지만 만료된 lot은 잔액에서 제외되어야 한다")
				.isZero();

		// when & then: 사용 가능한 lot이 없으므로 사용 시도는 잔액 부족으로 거절되어야 한다.
		assertThatThrownBy(() -> pointService.use(new UseRequest(userId, "O-1", 1)))
				.as("만료된 lot으로는 사용할 수 없어야 한다")
				.isInstanceOf(BusinessException.class);
	}

}
