package com.musinsapayments.point.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * amount 경계값(0 / 음수 / Long.MAX_VALUE)에 대해 DTO의 Bean Validation(@Positive)이
 * 실제로 어디까지 막아주는지 확인한다. @Positive는 "0보다 큰가"만 보므로 Long.MAX_VALUE처럼
 * 비정상적으로 큰 값은 이 레이어에서 걸러지지 않는다 — 그 상한은 PointPolicy(도메인 레이어)의 책임이다.
 */
class RequestValidationTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void setUp() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		factory.close();
	}

	@Test
	void 적립_금액_0은_검증에_실패한다() {
		Set<ConstraintViolation<EarnRequest>> violations =
				validator.validate(new EarnRequest(1L, 0, null, null, null));

		assertThat(violations).isNotEmpty();
	}

	@Test
	void 적립_금액_음수는_검증에_실패한다() {
		Set<ConstraintViolation<EarnRequest>> violations =
				validator.validate(new EarnRequest(1L, -1000, null, null, null));

		assertThat(violations).isNotEmpty();
	}

	@Test
	void 적립_금액_Long_MAX_VALUE는_Positive_검증만으로는_걸러지지_않는다() {
		// @Positive는 상한이 없다 — 실제 상한은 PointPolicy.validateEarnAmount / MAX_REALISTIC_AMOUNT가 담당한다.
		Set<ConstraintViolation<EarnRequest>> violations =
				validator.validate(new EarnRequest(1L, Long.MAX_VALUE, null, null, null));

		assertThat(violations).isEmpty();
	}

	@Test
	void 사용_금액_0과_음수는_검증에_실패한다() {
		assertThat(validator.validate(new UseRequest(1L, "A1234", 0, null))).isNotEmpty();
		assertThat(validator.validate(new UseRequest(1L, "A1234", -1, null))).isNotEmpty();
	}

	@Test
	void 사용취소_금액_0과_음수는_검증에_실패한다() {
		assertThat(validator.validate(new UseCancelRequest(1L, 0, null))).isNotEmpty();
		assertThat(validator.validate(new UseCancelRequest(1L, -1, null))).isNotEmpty();
	}

	@Test
	void 취소_요청의_userId가_없으면_검증에_실패한다() {
		assertThat(validator.validate(new UseCancelRequest(null, 1000, null))).isNotEmpty();
		assertThat(validator.validate(new EarnCancelRequest(null, null))).isNotEmpty();
	}

	@Test
	void 관리자_지급_금액_0과_음수는_검증에_실패한다() {
		assertThat(validator.validate(new AdminGrantRequest(1L, 0, null, null))).isNotEmpty();
		assertThat(validator.validate(new AdminGrantRequest(1L, -1, null, null))).isNotEmpty();
	}

	@Test
	void 정상_금액은_검증을_통과한다() {
		assertThat(validator.validate(new EarnRequest(1L, 1000, null, null, null))).isEmpty();
		assertThat(validator.validate(new UseRequest(1L, "A1234", 1000, null))).isEmpty();
		assertThat(validator.validate(new UseCancelRequest(1L, 1000, null))).isEmpty();
		assertThat(validator.validate(new AdminGrantRequest(1L, 1000, null, null))).isEmpty();
	}

	@Test
	void clientTransactionId가_100자면_검증을_통과하고_101자면_실패한다() {
		String exactly100 = "a".repeat(100);
		String tooLong = "a".repeat(101);

		assertThat(validator.validate(new EarnRequest(1L, 1000, null, null, exactly100))).isEmpty();
		assertThat(validator.validate(new EarnRequest(1L, 1000, null, null, tooLong))).isNotEmpty();
	}

	@Test
	void clientTransactionId를_생략해도_검증을_통과한다() {
		assertThat(validator.validate(new EarnRequest(1L, 1000, null, null, null))).isEmpty();
		assertThat(validator.validate(new UseRequest(1L, "A1234", 1000, null))).isEmpty();
		assertThat(validator.validate(new EarnCancelRequest(1L, null))).isEmpty();
		assertThat(validator.validate(new UseCancelRequest(1L, 1000, null))).isEmpty();
	}

}
