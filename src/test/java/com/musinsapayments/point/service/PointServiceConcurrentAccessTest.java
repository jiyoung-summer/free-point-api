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
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;

/**
 * 계정이 이미 존재하는 동일 사용자에게 적립/사용 요청이 정확히 동시에 몰려도
 * point_account 행의 비관적 락(FOR UPDATE)이 요청을 직렬화해 잔액 계산에
 * lost update가 생기지 않는지 검증한다. (계정이 "아직 없을 때"의 생성 경합은
 * PointAccountProvisionerConcurrencyTest에서 별도로 다룬다.)
 */
@SpringBootTest
class PointServiceConcurrentAccessTest {

	@Autowired
	private PointService pointService;

	@RepeatedTest(5)
	void 동일_사용자에게_적립과_사용이_동시에_들어와도_최종_잔액이_정확하다() throws InterruptedException {
		long userId = System.nanoTime();
		long seedBalance = 10_000;
		int earnThreads = 5;
		int useThreads = 5;
		long earnAmount = 1000;
		long useAmount = 1000;
		int totalThreads = earnThreads + useThreads;

		pointService.earn(new EarnRequest(userId, seedBalance, null, null, null));

		ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
		CountDownLatch ready = new CountDownLatch(totalThreads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(totalThreads);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		AtomicInteger orderSeq = new AtomicInteger();

		for (int i = 0; i < earnThreads; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					pointService.earn(new EarnRequest(userId, earnAmount, null, null, null));
				} catch (Throwable t) {
					failures.add(t);
				} finally {
					done.countDown();
				}
			});
		}
		for (int i = 0; i < useThreads; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					pointService.use(new UseRequest(userId, "ORDER-" + orderSeq.incrementAndGet(), useAmount, null));
				} catch (Throwable t) {
					failures.add(t);
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
		assertThat(failures).as("정상적인 동시 요청에서 예외가 발생하면 안 된다: %s", failures).isEmpty();

		long expectedBalance = seedBalance + earnThreads * earnAmount - useThreads * useAmount;
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(expectedBalance);
	}

	@RepeatedTest(5)
	void 동시_사용_요청이_잔액을_초과해도_초과_인출되지_않고_초과분만_정확히_거절된다() throws InterruptedException {
		long userId = System.nanoTime();
		long seedBalance = 5_000;
		long useAmount = 1_000;
		int threadCount = 10; // 요청 총합 10,000 > 잔액 5,000 → 정확히 5건만 성공해야 한다

		pointService.earn(new EarnRequest(userId, seedBalance, null, null, null));

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger insufficientBalanceCount = new AtomicInteger();
		List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();
		AtomicInteger orderSeq = new AtomicInteger();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					pointService.use(new UseRequest(userId, "ORDER-" + orderSeq.incrementAndGet(), useAmount, null));
					successCount.incrementAndGet();
				} catch (BusinessException e) {
					insufficientBalanceCount.incrementAndGet();
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
		assertThat(unexpectedFailures).as("잔액 부족이 아닌 다른 예외가 나오면 안 된다: %s", unexpectedFailures).isEmpty();

		// 잔액(5,000) / 사용액(1,000) = 정확히 5건만 성공, 나머지 5건은 잔액 부족으로 거절되어야 한다.
		assertThat(successCount.get()).as("성공 건수").isEqualTo(5);
		assertThat(insufficientBalanceCount.get()).as("잔액 부족 거절 건수").isEqualTo(5);

		// 가장 중요한 불변식: 동시 요청이 몰려도 잔액이 음수가 되거나 초과 인출되면 안 된다.
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(0);
	}

}
