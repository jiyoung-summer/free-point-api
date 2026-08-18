package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.musinsapayments.point.domain.PointTransaction;
import com.musinsapayments.point.dto.EarnCancelRequest;
import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.PointEarnResponse;
import com.musinsapayments.point.dto.PointTransactionResponse;
import com.musinsapayments.point.dto.PointUseResponse;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.repository.PointTransactionRepository;
import com.musinsapayments.point.support.MutableClock;

/**
 * clientTransactionId는 호출자 측 외부 시스템(주문/결제 등)의 거래 ID를 거래 추적 참고용으로
 * 저장·반환만 하는 수동적 필드다 — 재시도 멱등성 판단(Idempotency-Key)에는 관여하지 않는다.
 * 이 테스트는 (1) 4개 변경 API 모두에서 저장/응답/목록 조회까지 값이 그대로 보존되는지,
 * (2) 생략 시 기존처럼 null로 하위호환되는지, (3) 멱등키 재사용 감지(requestHash)에는
 * 영향을 주지 않는지를 확인한다.
 */
@SpringBootTest
class PointServiceClientTransactionIdTest {

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
	private PointTransactionRepository transactionRepository;

	@Autowired
	private Clock clock;

	@Test
	void 적립_요청의_clientTransactionId가_응답과_저장된_거래에_그대로_보존된다() {
		long userId = System.nanoTime();

		PointEarnResponse response = pointService.earn(new EarnRequest(userId, 1000, null, null, "ext-earn-1"));

		assertThat(response.clientTransactionId()).isEqualTo("ext-earn-1");
		PointTransaction saved = transactionRepository.findByPointKey(response.pointKey()).orElseThrow();
		assertThat(saved.getClientTransactionId()).isEqualTo("ext-earn-1");
	}

	@Test
	void 적립취소_요청의_clientTransactionId가_원_적립과_별개로_저장된다() {
		long userId = System.nanoTime();
		var earned = pointService.earn(new EarnRequest(userId, 1000, null, null, "ext-earn-1"));

		var canceled = pointService.earnCancel(earned.pointKey(), new EarnCancelRequest(userId, "ext-cancel-1"));

		assertThat(canceled.clientTransactionId()).isEqualTo("ext-cancel-1");
		PointTransaction cancelTxn = transactionRepository.findByPointKey(canceled.cancelPointKey()).orElseThrow();
		assertThat(cancelTxn.getClientTransactionId()).isEqualTo("ext-cancel-1");
		// 취소 거래의 clientTransactionId는 취소 요청 자체의 값이지, 원 적립의 값을 이어받지 않는다.
		PointTransaction earnTxn = transactionRepository.findByPointKey(earned.pointKey()).orElseThrow();
		assertThat(earnTxn.getClientTransactionId()).isEqualTo("ext-earn-1");
	}

	@Test
	void 사용_요청의_clientTransactionId와_orderNo는_서로_독립적으로_보존된다() {
		long userId = System.nanoTime();
		pointService.earn(new EarnRequest(userId, 1000, null, null, null));

		PointUseResponse used = pointService.use(new UseRequest(userId, "ORDER-1", 300, "ext-use-1"));

		assertThat(used.orderNo()).isEqualTo("ORDER-1");
		assertThat(used.clientTransactionId()).isEqualTo("ext-use-1");
	}

	@Test
	void 사용취소_요청의_clientTransactionId가_응답과_저장된_거래에_그대로_보존된다() {
		long userId = System.nanoTime();
		pointService.earn(new EarnRequest(userId, 1000, null, null, null));
		var used = pointService.use(new UseRequest(userId, "ORDER-1", 300, null));

		var canceled = pointService.useCancel(used.pointKey(), new UseCancelRequest(userId, 100, "ext-usecancel-1"));

		assertThat(canceled.clientTransactionId()).isEqualTo("ext-usecancel-1");
		PointTransaction cancelTxn = transactionRepository.findByPointKey(canceled.pointKey()).orElseThrow();
		assertThat(cancelTxn.getClientTransactionId()).isEqualTo("ext-usecancel-1");
	}

	@Test
	void clientTransactionId를_생략하면_기존처럼_null로_처리되어_하위호환된다() {
		long userId = System.nanoTime();

		PointEarnResponse earned = pointService.earn(new EarnRequest(userId, 1000, null, null, null));
		PointUseResponse used = pointService.use(new UseRequest(userId, "ORDER-1", 100, null));

		assertThat(earned.clientTransactionId()).isNull();
		assertThat(used.clientTransactionId()).isNull();
		assertThat(transactionRepository.findByPointKey(earned.pointKey()).orElseThrow().getClientTransactionId()).isNull();
	}

	@Test
	void 서로_다른_적립_거래가_같은_clientTransactionId_값을_가져도_둘_다_정상_처리된다() {
		// 외부 시스템 참고값일 뿐이라 유니크 제약이 없다 — 값 중복은 정상이다.
		long userId = System.nanoTime();

		PointEarnResponse first = pointService.earn(new EarnRequest(userId, 1000, null, null, "dup-ref"));
		PointEarnResponse second = pointService.earn(new EarnRequest(userId, 500, null, null, "dup-ref"));

		assertThat(first.clientTransactionId()).isEqualTo("dup-ref");
		assertThat(second.clientTransactionId()).isEqualTo("dup-ref");
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(1500);
	}

	@Test
	void 만료된_적립분의_사용취소_재적립_거래는_호출자의_clientTransactionId를_물려받지_않는다() {
		long userId = System.nanoTime();
		var earned = pointService.earn(new EarnRequest(userId, 1000, 1, null, null)); // 1일 만료
		var used = pointService.use(new UseRequest(userId, "ORDER-1", 1000, null));
		((MutableClock) clock).advance(Duration.ofDays(2)); // 사용취소 시점에는 원 적립분이 만료된 상태

		var canceled = pointService.useCancel(used.pointKey(), new UseCancelRequest(userId, 1000, "ext-usecancel-2"));

		assertThat(canceled.restorations()).hasSize(1);
		assertThat(canceled.restorations().get(0).reissued()).isTrue();
		String reissuedPointKey = canceled.restorations().get(0).reissuedPointKey();

		// 재적립 거래는 사용취소 요청이 아니라 시스템이 내부적으로 만들어내는 별개의 EARN 거래다.
		PointTransaction reissueTxn = transactionRepository.findByPointKey(reissuedPointKey).orElseThrow();
		assertThat(reissueTxn.getClientTransactionId()).isNull();
	}

	@Test
	void 거래_이력_조회_응답에도_clientTransactionId가_그대로_내려온다() {
		long userId = System.nanoTime();
		PointEarnResponse earned = pointService.earn(new EarnRequest(userId, 1000, null, null, "ext-earn-list"));

		var page = pointService.getTransactions(userId, PageRequest.of(0, 10));

		PointTransactionResponse item = page.content().stream()
				.filter(t -> t.pointKey().equals(earned.pointKey()))
				.findFirst()
				.orElseThrow();
		assertThat(item.clientTransactionId()).isEqualTo("ext-earn-list");
	}

	@Test
	void 같은_Idempotency_Key_재시도에서_clientTransactionId가_달라도_최초_응답이_그대로_반환된다() {
		// clientTransactionId는 requestHash에 포함되지 않는 순수 참고값이다 — 같은 멱등키로
		// 재시도하면서 clientTransactionId만 다르게 보내도 IDEMPOTENCY_KEY_REUSED로 거절되면 안 되고,
		// 최초 응답(최초 clientTransactionId 포함)이 그대로 재반환되어야 한다.
		long userId = System.nanoTime();
		String idempotencyKey = "ctid-retry-key";

		PointEarnResponse first = pointService.earn(new EarnRequest(userId, 1000, null, null, "first-ref"), idempotencyKey);
		PointEarnResponse retried = pointService.earn(new EarnRequest(userId, 1000, null, null, "second-ref"), idempotencyKey);

		assertThat(retried).isEqualTo(first);
		assertThat(retried.clientTransactionId()).isEqualTo("first-ref");
	}

}
