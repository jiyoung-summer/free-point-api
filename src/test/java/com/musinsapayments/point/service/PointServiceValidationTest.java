package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.dto.UseCancelRequest;
import com.musinsapayments.point.dto.UseRequest;
import com.musinsapayments.point.global.exception.BusinessException;

/**
 * @Positive 는 HTTP 요청 바인딩(@Valid) 시점에만 적용되고, 서비스 메서드를 직접 호출하면
 * 우회된다. amount<=0 이 서비스/도메인 레이어에서도 실제로 막히는지 확인한다.
 */
@SpringBootTest
class PointServiceValidationTest {

	@Autowired
	private PointService pointService;

	@Test
	void 사용_금액_0은_서비스_레이어에서도_거절된다() {
		long userId = 200L;
		pointService.earn(new EarnRequest(userId, 1000, null, null));

		assertThatThrownBy(() -> pointService.use(new UseRequest(userId, "O-1", 0)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 사용_금액_음수는_서비스_레이어에서도_거절되고_거래가_남지_않는다() {
		long userId = 201L;
		pointService.earn(new EarnRequest(userId, 1000, null, null));

		assertThatThrownBy(() -> pointService.use(new UseRequest(userId, "O-1", -100)))
				.isInstanceOf(BusinessException.class);

		// 음수 사용 시도가 잔액에 어떤 흔적도 남기면 안 된다.
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(1000);
	}

	@Test
	void 사용취소_금액_0과_음수는_서비스_레이어에서도_거절된다() {
		long userId = 202L;
		pointService.earn(new EarnRequest(userId, 1000, null, null));
		var use = pointService.use(new UseRequest(userId, "O-1", 500));

		assertThatThrownBy(() -> pointService.useCancel(use.pointKey(), new UseCancelRequest(userId, 0)))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> pointService.useCancel(use.pointKey(), new UseCancelRequest(userId, -50)))
				.isInstanceOf(BusinessException.class);

		// 잘못된 취소 시도가 잔액에 흔적을 남기면 안 된다.
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(500);
	}

	@Test
	void 적립_금액_0_음수_Long_MAX_VALUE는_서비스_레이어에서도_거절된다() {
		long userId = 203L;

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 0, null, null)))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, -1, null, null)))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, Long.MAX_VALUE, null, null)))
				.isInstanceOf(BusinessException.class);

		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

}
