package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;
import com.musinsapayments.point.repository.PointLotRepository;
import com.musinsapayments.point.repository.PointTransactionRepository;

/**
 * 멱등 응답 JSON 코덱(직렬화/역직렬화)이 실패하는 두 경로를 각각 검증한다. ObjectMapper를 spy로
 * 감싸 특정 메서드만 실패하도록 강제해 재현한다. 통째로 mock으로 바꾸면(MockitoBean) springdoc 등
 * 다른 빈이 기동 중에 실제 ObjectMapper 동작을 필요로 해 컨텍스트 자체가 뜨지 못한다 —
 * spy(MockitoSpyBean)는 실제 빈을 감싸고 지정한 메서드만 가로채므로 안전하다.
 */
@SpringBootTest
class PointServiceIdempotencyRollbackTest {

	@MockitoSpyBean
	private ObjectMapper objectMapper;

	@Autowired
	private PointService pointService;

	@Autowired
	private PointLotRepository lotRepository;

	@Autowired
	private PointTransactionRepository transactionRepository;

	@Test
	void 멱등_응답_직렬화가_실패하면_트랜잭션_전체가_롤백되어_lot도_거래도_남지_않는다() throws JsonProcessingException {
		long userId = System.nanoTime();
		given(objectMapper.writeValueAsString(any())).willThrow(new JsonProcessingException("직렬화 강제 실패") {
		});

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, null, null), "key-1"))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_CODEC_FAILED));

		assertThat(lotRepository.countByUserId(userId)).isZero();
		assertThat(pointService.getBalance(userId).balance()).isZero();
	}

	@Test
	void 멱등_응답_역직렬화가_실패하면_비즈니스_로직_재실행_없이_IDEMPOTENCY_CODEC_FAILED만_반환하고_잔액에_흔적을_남기지_않는다()
			throws Exception {
		long userId = System.nanoTime();
		String idempotencyKey = "read-fail-key";

		// 최초 요청은 캐시 미스라 readValue()를 타지 않는다 — 정상 처리되어 응답이 저장된다.
		pointService.earn(new EarnRequest(userId, 1000, null, null, null), idempotencyKey);

		// 저장된 응답을 읽으려 하면 항상 실패하도록 강제한다 — 배포 중 DTO 구조가 바뀌어 이전에
		// 저장된 JSON과 안 맞는 상황 등을 재현한다. given(spy.method()).willThrow(...)는 스텁을
		// 거는 과정에서 실제 메서드를 먼저 호출하는데(스파이라서), readValue(null, null)이 그
		// 시점에 바로 예외를 던져버려 스텁 자체가 실패한다 — willThrow(...).given(spy).method()
		// (doThrow 방식)는 실제 메서드를 호출하지 않고 스텁을 걸어서 이 문제가 없다.
		willThrow(new JsonProcessingException("역직렬화 강제 실패") {
		}).given(objectMapper).readValue(anyString(), any(Class.class));

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, null, null), idempotencyKey))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.IDEMPOTENCY_CODEC_FAILED));

		// 재시도는 캐시 히트 시점에 실패했으므로 애초에 새 lot/거래를 만들 일이 없다 — "롤백"이 아니라
		// "아예 손대지 않았는지"를 확인한다. 최초 요청이 만든 lot(1건)/잔액(1000)만 그대로여야 한다.
		assertThat(lotRepository.countByUserId(userId)).isEqualTo(1);
		assertThat(pointService.getBalance(userId).balance()).isEqualTo(1000);
	}

}
