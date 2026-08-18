package com.musinsapayments.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.musinsapayments.point.dto.EarnRequest;
import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;
import com.musinsapayments.point.repository.PointPolicyRepository;

/**
 * point_policy 테이블이 비어 있으면(운영자가 실수로 전부 지웠다거나, PointPolicyInitializer가
 * 아직 시딩하기 전이라거나) 모든 상태 변경 요청이 즉시, 일관되게 실패해야 한다. 이 실패가 다른
 * 500 원인(JSON 코덱 실패, 계정 조회 실패 등)과 뭉뚱그려지면 운영 중에는 "정책을 새로 등록해야
 * 한다"는 원인을 로그에서 바로 알아볼 수 없다 — `POLICY_NOT_CONFIGURED`로 구분되는지 확인한다.
 *
 * @Transactional로 감싸 테스트가 끝나면 deleteAll()을 포함한 모든 변경이 롤백되게 해서, 전역
 * 리소스인 point_policy를 공유하는 다른 테스트에 영향을 남기지 않는다.
 */
@SpringBootTest
class PointServiceMissingPolicyTest {

	@Autowired
	private PointService pointService;

	@Autowired
	private PointPolicyRepository policyRepository;

	@Test
	@Transactional
	void 정책이_하나도_없으면_POLICY_NOT_CONFIGURED로_거절된다() {
		policyRepository.deleteAll();
		long userId = System.nanoTime();

		assertThatThrownBy(() -> pointService.earn(new EarnRequest(userId, 1000, null, null, null)))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.POLICY_NOT_CONFIGURED));
	}

}
