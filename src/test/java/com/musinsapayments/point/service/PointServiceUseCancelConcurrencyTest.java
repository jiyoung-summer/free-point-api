package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.PointUseResponse;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;

/**
 * 같은 사용 거래(pointKey)에 대해 부분 사용취소 요청이 동시에 여러 건 들어올 때,
 * 취소 가능 금액(cancelableAmount)을 초과해 취소되는 경쟁 상태가 없는지 검증한다.
 */
@SpringBootTest
class PointServiceUseCancelConcurrencyTest {

	@Autowired
	private PointService pointService;

	@RepeatedTest(5)
	void 동시_부분_취소_요청이_취소가능금액을_초과해도_초과_취소되지_않는다() throws InterruptedException {
		long userId = System.nanoTime();
		long earnAmount = 1_000;
		long useAmount = 1_000;
		long cancelChunk = 300;
		int threadCount = 5; // 요청 총합 1,500 > 취소가능금액 1,000 → 정확히 3건만 성공해야 한다(900원)

		pointService.earn(new EarnRequest(userId, earnAmount, null, null, null));
		PointUseResponse use = pointService.use(new UseRequest(userId, "ORDER-1", useAmount, null));

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger rejectedCount = new AtomicInteger();
		List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					pointService.useCancel(use.pointKey(), new UseCancelRequest(userId, cancelChunk, null));
					successCount.incrementAndGet();
				} catch (BusinessException e) {
					rejectedCount.incrementAndGet();
				} catch (Throwable t) {
					unexpectedFailures.add(t);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		boolean finished = done.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		assertThat(finished).as("스레드가 제한 시간 안에 끝나야 한다").isTrue();
		assertThat(unexpectedFailures).as("취소 가능 금액 초과가 아닌 다른 예외가 나오면 안 된다: %s", unexpectedFailures).isEmpty();

		// 취소가능금액(1,000) / 요청단위(300) = 정확히 3건만 성공(900원), 나머지 2건은 거절되어야 한다.
		assertThat(successCount.get()).as("성공 건수").isEqualTo(3);
		assertThat(rejectedCount.get()).as("거절 건수").isEqualTo(2);

		// 가장 중요한 불변식: 실제 사용된 금액(1,000)보다 더 많이 취소되면 안 된다.
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(900);
	}

}
