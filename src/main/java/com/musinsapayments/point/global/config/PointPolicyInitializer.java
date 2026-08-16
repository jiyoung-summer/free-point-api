package com.musinsapayments.point.global.config;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.musinsapayments.point.domain.PointPolicy;
import com.musinsapayments.point.repository.PointPolicyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동 시 정책 테이블이 비어 있으면 기본 정책 1건을 시딩한다.
 * 이후 정책 변경은 하드코딩이 아니라 이 테이블에 새 버전 행을 추가하는 방식으로 이뤄진다.
 * 기본값 : 1회 1~100,000, 개인별 최대 보유 1,000,000, 만료일 1~1824일(5년 미만), 기본 만료 365일.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointPolicyInitializer implements ApplicationRunner {

	private final PointPolicyRepository policyRepository;
	private final Clock clock;

	@Override
	public void run(ApplicationArguments args) {
		if (policyRepository.count() > 0) {
			log.debug("정책이 이미 존재해 기본 정책 시딩을 건너뜁니다.");
			return;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		PointPolicy defaultPolicy = PointPolicy.create(
				"v1", 1, 100_000, 1_000_000,
				365, 1, 1824,
				now, "system", "초기 정책 시딩", now);
		policyRepository.save(defaultPolicy);
		// 정책이 하나도 없으면 모든 상태 변경 API가 POLICY_NOT_CONFIGURED로 즉시 실패하므로,
		// 기동 시 실제로 시딩됐는지는 운영자가 로그만 보고도 바로 알 수 있어야 한다.
		log.info("기본 정책을 시딩했습니다: versionLabel={}, maxEarnAmount={}, maxHoldAmount={}",
				defaultPolicy.getVersionLabel(), defaultPolicy.getMaxEarnAmount(), defaultPolicy.getMaxHoldAmount());
	}

}
