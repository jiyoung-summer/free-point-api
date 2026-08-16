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

import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.PointEarnResponse;
import com.musinsapayments.point.dto.PointLotResponse;
import com.musinsapayments.point.dto.PointUseCancelResponse;
import com.musinsapayments.point.dto.PointUseResponse;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.support.MutableClock;

/**
 * 요구사항에 명시된 예시 시나리오(A~E)를 그대로 재현해 단계별 잔액을 검증한다.
 * A는 짧은 만료일(1일)을 부여해 "A만 만료되고 B는 유효한" 상황을 시간 이동으로 재현한다.
 */
@SpringBootTest
class PointServiceScenarioTest {

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
	private Clock clock;

	@Test
	void 예시_시나리오_전체_잔액이_단계별로_일치한다() {
		long userId = 1L;

		// 1) 1000원 적립 (pointKey A, 잔액 0 -> 1000). 만료를 1일로 짧게 잡아 이후 시간 이동으로 "A만 만료" 상황을 만든다.
		PointEarnResponse earnA = pointService.earn(new EarnRequest(userId, 1000, 1, null));
		assertThat(earnA.balance()).isEqualTo(1000);

		// 2) 500원 적립 (pointKey B, 잔액 1000 -> 1500). 기본 만료(365일)라 A보다 훨씬 나중에 만료된다.
		PointEarnResponse earnB = pointService.earn(new EarnRequest(userId, 500, null, null));
		assertThat(earnB.balance()).isEqualTo(1500);

		// 3) 주문 A1234 에서 1200원 사용 (pointKey C, 잔액 1500 -> 300). 만료 임박 순이라 A(1000)를 먼저, B(200)를 나중에 소진한다.
		PointUseResponse use = pointService.use(new UseRequest(userId, "A1234", 1200));
		assertThat(use.balance()).isEqualTo(300);
		assertThat(use.allocations()).hasSize(2);
		assertThat(use.allocations().get(0).earnPointKey()).isEqualTo(earnA.pointKey());
		assertThat(use.allocations().get(0).amount()).isEqualTo(1000);
		assertThat(use.allocations().get(1).earnPointKey()).isEqualTo(earnB.pointKey());
		assertThat(use.allocations().get(1).amount()).isEqualTo(200);

		// 4) A의 적립이 만료되었다 (1일 만료 → 2일 경과시키면 A만 만료, B는 그대로 유효).
		((MutableClock) clock).advance(Duration.ofDays(2));

		// 5) C의 사용금액 1200원 중 1100원 부분 사용취소 (pointKey D, 잔액 300 -> 1400).
		PointUseCancelResponse cancel = pointService.useCancel(use.pointKey(), new UseCancelRequest(1100));
		assertThat(cancel.balance()).isEqualTo(1400);
		assertThat(cancel.restorations()).hasSize(2);

		// A 몫(1000원)은 이미 만료되었으므로 신규 적립(pointKey E)으로 재발급된다.
		PointUseCancelResponse.Restoration aRestoration = cancel.restorations().get(0);
		assertThat(aRestoration.amount()).isEqualTo(1000);
		assertThat(aRestoration.reissued()).isTrue();
		assertThat(aRestoration.reissuedPointKey()).isNotBlank();
		assertThat(aRestoration.reissuedPointKey()).isNotEqualTo(earnA.pointKey());

		// B 몫(100원)은 아직 유효하므로 B에 그대로 복원된다.
		PointUseCancelResponse.Restoration bRestoration = cancel.restorations().get(1);
		assertThat(bRestoration.amount()).isEqualTo(100);
		assertThat(bRestoration.reissued()).isFalse();
		assertThat(bRestoration.originLotId()).isNotNull();

		// C는 이제 1200원 중 100원만 추가로 부분취소할 수 있다.
		assertThatThrownBy(() -> pointService.useCancel(use.pointKey(), new UseCancelRequest(101)))
				.isInstanceOf(BusinessException.class);

		PointUseCancelResponse finalCancel = pointService.useCancel(use.pointKey(), new UseCancelRequest(100));
		assertThat(finalCancel.canceledAmount()).isEqualTo(100);
		assertThat(finalCancel.balance()).isEqualTo(1500);
	}

	@Test
	void 만료된_적립분은_DB_status가_ACTIVE로_남아도_조회_응답에서는_usable_false_expired_true로_보인다() {
		long userId = 5L;

		// 만료 배치가 없으므로(README "남은 과제") status 컬럼은 시간이 지나도 ACTIVE로 남는다.
		// 대신 조회 시점에 usable/expired를 계산해 내려줘서 status만 보고 오해하지 않게 한다.
		pointService.earn(new EarnRequest(userId, 1000, 1, null)); // 1일 만료
		((MutableClock) clock).advance(Duration.ofDays(2)); // 만료 시점을 지났지만 배치가 없어 status는 그대로 ACTIVE

		PointLotResponse lot = pointService.getLots(userId, PageRequest.of(0, 10)).content().get(0);

		assertThat(lot.status()).isEqualTo("ACTIVE");
		assertThat(lot.expired()).isTrue();
		assertThat(lot.usable()).isFalse();
	}

	@Test
	void 한번도_사용하지_않았어도_만료된_적립은_적립취소_API로_취소할_수_없다() {
		long userId = 6L;

		// 무상 포인트는 만료되면 소멸한다 — 만료 배치가 없어 status는 ACTIVE로 남지만
		// (한 번도 쓰지 않았더라도) 적립취소 API로 되돌릴 수 있는 대상이 아니다.
		PointEarnResponse earn = pointService.earn(new EarnRequest(userId, 1000, 1, null)); // 1일 만료
		((MutableClock) clock).advance(Duration.ofDays(2));

		assertThatThrownBy(() -> pointService.earnCancel(earn.pointKey()))
				.isInstanceOf(BusinessException.class);

		// 실패한 취소 시도가 잔액에 흔적을 남기면 안 된다(여전히 만료된 채로 사용 불가 상태일 뿐, 취소된 것도 아님).
		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

}
