package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.musinsapayments.point.dto.EarnCancelRequest;
import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;
import com.musinsapayments.point.repository.PointLotRepository;

/**
 * earnCancel/useCancel은 pointKey(서버 발급 UUID)만으로 대상을 찾는다 — 인증/인가가 없는 이번
 * 제출 범위에서는(README §8), pointKey를 알고 있는 아무나 남의 적립/사용 내역을 취소 요청할 수
 * 있었다. 요청 본문의 userId가 pointKey의 실제 소유자와 일치하는지 서비스 레이어에서 직접
 * 검증한다. 존재하지 않는 pointKey와 같은 결과(ENTITY_NOT_FOUND)로 응답해, "이 pointKey는
 * 실제로 존재한다(남의 것일 뿐)"는 사실 자체가 새어나가지 않게 한다.
 */
@SpringBootTest
class PointServiceCancelOwnershipTest {

	@Autowired
	private PointService pointService;

	@Autowired
	private PointLotRepository lotRepository;

	@Test
	void 소유자가_아닌_userId로_적립취소를_요청하면_ENTITY_NOT_FOUND로_거절되고_잔액에_흔적을_남기지_않는다() {
		long ownerId = System.nanoTime();
		long strangerId = ownerId + 1;
		var earned = pointService.earn(new EarnRequest(ownerId, 1000, null, null, null));

		assertThatThrownBy(() -> pointService.earnCancel(earned.pointKey(), new EarnCancelRequest(strangerId, null)))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

		// 취소 시도가 실패했으니 lot은 여전히 ACTIVE·미취소 상태로 남아있어야 한다.
		assertThat(pointService.getBalance(ownerId).balance()).isEqualTo(1000);
		assertThat(lotRepository.countByUserId(ownerId)).isEqualTo(1);
	}

	@Test
	void 진짜_소유자는_같은_pointKey로_정상적으로_적립취소할_수_있다() {
		long ownerId = System.nanoTime();
		var earned = pointService.earn(new EarnRequest(ownerId, 1000, null, null, null));

		var response = pointService.earnCancel(earned.pointKey(), new EarnCancelRequest(ownerId, null));

		assertThat(response.canceledAmount()).isEqualTo(1000);
		assertThat(pointService.getBalance(ownerId).balance()).isZero();
	}

	@Test
	void 소유자가_아닌_userId로_사용취소를_요청하면_ENTITY_NOT_FOUND로_거절되고_잔액을_복원하지_않는다() {
		long ownerId = System.nanoTime();
		long strangerId = ownerId + 1;
		pointService.earn(new EarnRequest(ownerId, 1000, null, null, null));
		var used = pointService.use(new UseRequest(ownerId, "ORD-1", 400, null));

		assertThatThrownBy(() -> pointService.useCancel(used.pointKey(), new UseCancelRequest(strangerId, 100, null)))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

		// 취소 시도가 실패했으니 사용으로 차감된 잔액(600)이 그대로여야 한다 — 복원되면 안 된다.
		assertThat(pointService.getBalance(ownerId).balance()).isEqualTo(600);
	}

	@Test
	void 진짜_소유자는_같은_pointKey로_정상적으로_사용취소할_수_있다() {
		long ownerId = System.nanoTime();
		pointService.earn(new EarnRequest(ownerId, 1000, null, null, null));
		var used = pointService.use(new UseRequest(ownerId, "ORD-1", 400, null));

		var response = pointService.useCancel(used.pointKey(), new UseCancelRequest(ownerId, 100, null));

		assertThat(response.canceledAmount()).isEqualTo(100);
		assertThat(pointService.getBalance(ownerId).balance()).isEqualTo(700);
	}

}
