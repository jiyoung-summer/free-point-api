package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.repository.PointAccountRepository;
import com.musinsapayments.point.repository.PointLotRepository;

/**
 * 계정이 아직 없는 동일 사용자에게 "최초 적립"이 정확히 동시에 여러 건 들어올 때
 * (PointService.resolveAccountForUpdate → PointAccountProvisioner.provision 경합 경로)
 * 통합 테스트로 검증한다. H2와 실제 운영 DB(Aurora/MySQL)의 unique 제약 충돌·트랜잭션 격리
 * 동작이 다를 수 있다는 우려에 대해 — 이 테스트는 실제 스레드 + 실제 트랜잭션 + 실제 DB(H2)로
 * 아래 4가지를 전부 확인한다:
 *   1) 모든 요청이 예외 없이 성공하는가
 *   2) point_account 행이 정확히 1개만 생성되는가 (중복 생성 없음)
 *   3) point_lot 행 개수가 요청 개수와 정확히 일치하는가 (유실/중복 없음)
 *   4) 최종 잔액이 요청 개수 × 적립액과 정확히 일치하는가 (lost update 없음)
 *
 * CountDownLatch로 스레드를 같은 순간에 출발시켜 Thread.sleep 없이 경쟁 상태를 재현하고,
 * 동시 실행 스레드 수를 2~10까지 파라미터화해 다양한 경합 규모를 확인한다.
 */
@SpringBootTest
class PointAccountProvisionerConcurrencyTest {

	@Autowired
	private PointService pointService;

	@Autowired
	private PointAccountRepository accountRepository;

	@Autowired
	private PointLotRepository lotRepository;

	@ParameterizedTest(name = "동시 요청 {0}건")
	@ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
	void 신규_사용자에게_최초_적립이_동시에_여러_건_들어와도_계정은_1개만_생성되고_잔액과_lot_수가_정확하다(int threadCount) throws InterruptedException {
		long userId = System.nanoTime(); // 매 실행마다 새로운, 계정이 아직 없는 사용자
		long earnAmount = 1000;

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<String> succeededPointKeys = new CopyOnWriteArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					var response = pointService.earn(new EarnRequest(userId, earnAmount, null, null, null));
					succeededPointKeys.add(response.pointKey());
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

		// 1) 스레드가 제한 시간 안에 끝나고, 모든 요청이 예외 없이 성공해야 한다.
		assertThat(finished).as("스레드가 제한 시간 안에 끝나야 한다").isTrue();
		assertThat(failures).as("계정 생성 경합으로 예외가 발생하면 안 된다: %s", failures).isEmpty();
		assertThat(succeededPointKeys).as("모든 요청이 성공해야 한다").hasSize(threadCount);
		assertThat(succeededPointKeys).as("각 적립은 서로 다른 pointKey를 받아야 한다(중복 없음)")
				.doesNotHaveDuplicates();

		// 2) point_account 행이 정확히 1개만 생성되어야 한다.
		assertThat(accountRepository.countByUserId(userId))
				.as("경합 상황에서도 계정은 정확히 1개만 생성되어야 한다")
				.isEqualTo(1);

		// 3) point_lot 행 개수가 요청 개수와 정확히 일치해야 한다(유실/중복 없음).
		assertThat(lotRepository.countByUserId(userId))
				.as("적립 요청 수만큼 정확히 lot이 생성되어야 한다")
				.isEqualTo(threadCount);

		// 4) 최종 잔액이 요청 개수 x 적립액과 정확히 일치해야 한다(lost update 없음).
		assertThat(pointService.getBalance(userId).balance())
				.as("최종 잔액은 (요청 수 x 적립액)과 정확히 일치해야 한다")
				.isEqualTo(earnAmount * threadCount);
	}

}
