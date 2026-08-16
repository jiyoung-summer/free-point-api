package com.musinsapayments.point.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.musinsapayments.point.domain.PointAccount;
import com.musinsapayments.point.repository.PointAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * 계정 최초 생성을 별도의 짧은 트랜잭션(REQUIRES_NEW)으로 분리한다.
 * 같은 사용자가 동시에 처음 적립을 시도해 유니크 제약이 충돌하면 이 메서드는 예외를 그대로 던지고,
 * 이 트랜잭션만 정상적으로 롤백된다(본 적립 트랜잭션은 건드리지 않는다).
 *
 * 유니크 제약 위반을 이 메서드 안에서 잡지 않는 이유: saveAndFlush() 가 제약 위반으로 실패하면
 * Hibernate 세션이 "예외 발생 후 flush 시도됨" 상태가 되어, 같은 트랜잭션 안에서 이어지는 조회가
 * AssertionFailure(null identifier)로 깨질 수 있다(실제로 동시성 테스트로 재현됨 — 5/5 실패).
 * 그래서 예외는 호출자(PointService.resolveAccountForUpdate)로 전파시키고, 호출자가 완전히
 * 별개인 자신의 트랜잭션에서 재조회하도록 한다.
 */
@Component
@RequiredArgsConstructor
public class PointAccountProvisioner {

	private final PointAccountRepository accountRepository;
	private final Clock clock;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void provision(Long userId) {
		if (accountRepository.findByUserId(userId).isPresent()) {
			return;
		}
		accountRepository.saveAndFlush(PointAccount.open(userId, LocalDateTime.now(clock)));
	}

}
