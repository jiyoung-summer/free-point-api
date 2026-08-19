package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.musinsapayments.point.dto.EarnCancelRequest;
import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.PointEarnResponse;
import com.musinsapayments.point.dto.PointUseResponse;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;
import com.musinsapayments.point.repository.PointLotRepository;
import com.musinsapayments.point.repository.PointTransactionRepository;

/**
 * point_transaction.point_key는 서버가 매번 새로 발급하는 UUID라 재시도 멱등성 키가 될 수 없다
 * (재시도해도 매번 다른 UUID가 나오므로 unique 제약이 재시도 중복을 막아주지 못한다). 클라이언트가
 * 보내는 Idempotency-Key + (user_id, operation_type)으로 별도 저장소(idempotency_key)를 두어,
 * 같은 키로 재시도하면 비즈니스 로직을 다시 실행하지 않고 최초 응답을 그대로 반환하는지 확인한다.
 */
@SpringBootTest
class PointServiceIdempotencyTest {

	@Autowired
	private PointService pointService;

	@Autowired
	private PointLotRepository lotRepository;

	@Autowired
	private PointTransactionRepository transactionRepository;

	@Test
	void 같은_Idempotency_Key로_적립을_재시도해도_lot이_중복_생성되지_않고_동일한_응답을_반환한다() {
		long userId = System.nanoTime();
		String idempotencyKey = "retry-key-1";

		PointEarnResponse first = pointService.earn(new EarnRequest(userId, 1000, null, null), idempotencyKey);
		PointEarnResponse retried = pointService.earn(new EarnRequest(userId, 1000, null, null), idempotencyKey);

		assertThat(retried).isEqualTo(first);
		assertThat(lotRepository.countByUserId(userId)).isEqualTo(1);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(1000);
	}

	@Test
	void Idempotency_Key가_다르면_별개의_적립으로_처리된다() {
		long userId = System.nanoTime();

		pointService.earn(new EarnRequest(userId, 1000, null, null), "key-a");
		pointService.earn(new EarnRequest(userId, 1000, null, null), "key-b");

		assertThat(lotRepository.countByUserId(userId)).isEqualTo(2);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(2000);
	}

	@Test
	void Idempotency_Key를_보내지_않으면_기존처럼_매번_새로_처리된다() {
		long userId = System.nanoTime();

		pointService.earn(new EarnRequest(userId, 1000, null, null));
		pointService.earn(new EarnRequest(userId, 1000, null, null));

		assertThat(lotRepository.countByUserId(userId)).isEqualTo(2);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(2000);
	}

	@Test
	void 서로_다른_사용자가_같은_Idempotency_Key_문자열을_써도_섞이지_않는다() {
		long userId1 = System.nanoTime();
		long userId2 = userId1 + 1;
		String sharedKey = "shared-key";

		pointService.earn(new EarnRequest(userId1, 1000, null, null), sharedKey);
		pointService.earn(new EarnRequest(userId2, 500, null, null), sharedKey);

		assertThat(pointService.getBalance(userId1).balance()).isEqualTo(1000);
		assertThat(pointService.getBalance(userId2).balance()).isEqualTo(500);
	}

	@Test
	void 같은_Idempotency_Key로_적립취소를_재시도하면_이미_취소됐다는_예외_없이_동일_응답을_반환한다() {
		long userId = System.nanoTime();
		var earned = pointService.earn(new EarnRequest(userId, 1000, null, null));
		String idempotencyKey = "cancel-retry-key";

		var first = pointService.earnCancel(earned.pointKey(), new EarnCancelRequest(userId), idempotencyKey);
		var retried = pointService.earnCancel(earned.pointKey(), new EarnCancelRequest(userId), idempotencyKey);

		assertThat(retried).isEqualTo(first);
		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

	@Test
	void 같은_Idempotency_Key로_사용을_재시도해도_잔액이_중복_차감되지_않는다() {
		long userId = System.nanoTime();
		pointService.earn(new EarnRequest(userId, 1000, null, null));
		String idempotencyKey = "use-retry-key";

		PointUseResponse first = pointService.use(new UseRequest(userId, "ORD-1", 300), idempotencyKey);
		PointUseResponse retried = pointService.use(new UseRequest(userId, "ORD-1", 300), idempotencyKey);

		assertThat(retried).isEqualTo(first);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(700);
	}

	@Test
	void 같은_Idempotency_Key로_사용취소를_재시도해도_잔액이_중복_복원되지_않는다() {
		long userId = System.nanoTime();
		pointService.earn(new EarnRequest(userId, 1000, null, null));
		var used = pointService.use(new UseRequest(userId, "ORD-1", 400));
		String idempotencyKey = "use-cancel-retry-key";

		var first = pointService.useCancel(used.pointKey(), new UseCancelRequest(userId, 200), idempotencyKey);
		var retried = pointService.useCancel(used.pointKey(), new UseCancelRequest(userId, 200), idempotencyKey);

		assertThat(retried).isEqualTo(first);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(800);
	}

	@Test
	void memo가_null인_요청과_memo가_문자열_null인_요청은_다른_요청으로_구분되어야_한다() {
		// requestHash가 파트를 '|'로 이어붙이기만 하면 Java null(→ "null" 텍스트로 이어붙여짐)과
		// 문자열 리터럴 "null"이 같은 문자열을 만들어 서로 다른 요청인데도 같은 지문이 나올 수 있다.
		long userId = System.nanoTime();
		String idempotencyKey = "collision-key";

		pointService.earn(new EarnRequest(userId, 1000, null, null), idempotencyKey);

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, "null"), idempotencyKey))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
	}

	@Test
	void 같은_Idempotency_Key를_다른_적립_금액으로_재사용하면_거절되고_최초_적립만_유효하다() {
		long userId = System.nanoTime();
		String idempotencyKey = "key-1";

		pointService.earn(new EarnRequest(userId, 1000, null, null), idempotencyKey);

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 500, null, null), idempotencyKey))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));

		// 두 번째(내용이 다른) 요청은 절대 실행되면 안 된다 — 잔액은 최초 요청분(1000)만 반영되어야 한다.
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(1000);
		assertThat(lotRepository.countByUserId(userId)).isEqualTo(1);
	}

	@Test
	void 같은_Idempotency_Key로_서로_다른_적립분을_취소하려_하면_거절되고_B는_취소되지_않는다() {
		long userId = System.nanoTime();
		var earnedA = pointService.earn(new EarnRequest(userId, 1000, null, null));
		var earnedB = pointService.earn(new EarnRequest(userId, 500, null, null));
		String idempotencyKey = "cancel-key";

		pointService.earnCancel(earnedA.pointKey(), new EarnCancelRequest(userId), idempotencyKey);

		assertThatThrownBy(() -> pointService.earnCancel(earnedB.pointKey(), new EarnCancelRequest(userId), idempotencyKey))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));

		// B는 여전히 살아있어야 한다 — 취소된 건 A뿐이어야 한다.
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(500);
	}

	@Test
	void 공백_Idempotency_Key는_거절되고_적립도_처리되지_않는다() {
		long userId = System.nanoTime();

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, null), "   "))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

	@Test
	void 길이가_200자를_넘는_Idempotency_Key는_거절된다() {
		long userId = System.nanoTime();
		String tooLong = "a".repeat(201);

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, null), tooLong))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

	@Test
	void 제어_문자를_포함한_Idempotency_Key는_거절된다() {
		long userId = System.nanoTime();

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, null), "key-1\n"))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

	@Test
	void 길이가_정확히_200자인_Idempotency_Key는_허용된다() {
		long userId = System.nanoTime();
		String exactly200 = "a".repeat(200);

		var response = pointService.earn(new EarnRequest(userId, 1000, null, null), exactly200);

		assertThat(response.amount()).isEqualTo(1000);
	}

	@Test
	void 동시에_같은_Idempotency_Key로_여러_요청이_몰려도_정확히_1건만_실제로_처리된다() throws InterruptedException {
		long userId = System.nanoTime();
		String idempotencyKey = "concurrent-key";
		int threadCount = 8;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<PointEarnResponse> responses = new CopyOnWriteArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					responses.add(pointService.earn(new EarnRequest(userId, 1000, null, null), idempotencyKey));
				} catch (Throwable t) {
					failures.add(t);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		boolean finished = done.await(15, TimeUnit.SECONDS);
		executor.shutdown();

		assertThat(finished).as("스레드가 제한 시간 안에 끝나야 한다").isTrue();
		assertThat(failures).as("멱등 처리 경합으로 예외가 발생하면 안 된다: %s", failures).isEmpty();
		assertThat(responses).as("모든 응답이 동일한 pointKey를 가져야 한다(같은 요청의 결과)")
				.extracting(PointEarnResponse::pointKey)
				.containsOnly(responses.get(0).pointKey());

		// 실제 비즈니스 로직은 8건 중 정확히 1건만 실행되어야 한다 — lot/거래 모두 1개.
		assertThat(lotRepository.countByUserId(userId)).isEqualTo(1);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(1000);
		long earnTxnCount = transactionRepository.findByUserId(userId,
						org.springframework.data.domain.Pageable.unpaged())
				.stream()
				.filter(t -> t.getTransactionType() == com.musinsapayments.point.domain.PointTransaction.Type.EARN)
				.collect(Collectors.counting());
		assertThat(earnTxnCount).as("EARN 거래도 정확히 1건만 생성되어야 한다").isEqualTo(1L);
	}

}
