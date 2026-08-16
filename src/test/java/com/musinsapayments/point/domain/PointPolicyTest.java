package com.musinsapayments.point.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.musinsapayments.point.global.exception.BusinessException;

class PointPolicyTest {

	private final LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);

	private PointPolicy defaultPolicy() {
		return PointPolicy.create("v1", 1, 100_000, 1_000_000, 365, 1, 1824, now, "system", "초기 정책", now);
	}

	@Test
	void 정책_범위_밖의_적립금액은_거절된다() {
		// given
		PointPolicy policy = defaultPolicy();

		// when & then
		assertThatThrownBy(() -> policy.validateEarnAmount(100_001))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 적립금액_0은_거절된다() {
		PointPolicy policy = defaultPolicy();

		assertThatThrownBy(() -> policy.validateEarnAmount(0))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 적립금액_음수는_거절된다() {
		PointPolicy policy = defaultPolicy();

		assertThatThrownBy(() -> policy.validateEarnAmount(-1))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 적립금액_Long_MAX_VALUE는_거절된다() {
		PointPolicy policy = defaultPolicy();

		assertThatThrownBy(() -> policy.validateEarnAmount(Long.MAX_VALUE))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 적립_최소_최대_경계값은_허용된다() {
		PointPolicy policy = defaultPolicy(); // minEarnAmount=1, maxEarnAmount=100_000

		assertThatCode(() -> policy.validateEarnAmount(1)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateEarnAmount(100_000)).doesNotThrowAnyException();
	}

	@Test
	void 보유한도를_초과하는_적립은_거절된다() {
		// given
		PointPolicy policy = defaultPolicy();

		// when & then
		assertThatThrownBy(() -> policy.validateHoldLimit(999_999, 2))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 보유한도_이내면_적립_후_예상_잔액을_그대로_돌려준다() {
		// given
		PointPolicy policy = defaultPolicy();

		// when
		long projectedBalance = policy.validateHoldLimit(500_000, 300_000);

		// then
		assertThat(projectedBalance).isEqualTo(800_000);
	}

	@Test
	void 보유_잔액_합산이_long_범위를_넘으면_예외가_발생한다() {
		// given
		PointPolicy policy = defaultPolicy();

		// when & then — Long.MAX_VALUE 근처의 잘못된 currentBalance가 들어와도 조용히 감싸(wrap)지 않고 예외로 드러난다.
		assertThatThrownBy(() -> policy.validateHoldLimit(Long.MAX_VALUE - 5, 100))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 정책_금액_한도가_비현실적으로_크면_생성이_거절된다() {
		// when & then
		assertThatThrownBy(() -> PointPolicy.create("v1", 1, 100_000, Long.MAX_VALUE,
				365, 1, 1824, now, "system", "비정상 정책", now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 만료일_미지정시_기본값을_사용한다() {
		// given
		PointPolicy policy = defaultPolicy();

		// when
		int days = policy.resolveExpireDays(null);

		// then
		assertThat(days).isEqualTo(365);
	}

	@Test
	void 만료일_범위를_벗어나면_예외가_발생한다() {
		// given
		PointPolicy policy = defaultPolicy();

		// when & then
		assertThatThrownBy(() -> policy.resolveExpireDays(2000))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 만료일_상한_1824일을_넘는_정책은_생성이_거절된다() {
		assertThatThrownBy(() -> PointPolicy.create("v2", 1, 100_000, 1_000_000,
				365, 1, 1825, now, "system", "5년 초과 시도", now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 만료일_상한과_정확히_같은_1824일은_생성이_허용된다() {
		assertThatCode(() -> PointPolicy.create("v2", 1, 100_000, 1_000_000,
				365, 1, 1824, now, "system", "경계값", now))
				.doesNotThrowAnyException();
	}

	@Test
	void versionLabel이_null이거나_공백이면_생성이_거절된다() {
		assertThatThrownBy(() -> PointPolicy.create(null, 1, 100_000, 1_000_000,
				365, 1, 1824, now, "system", "null 라벨", now))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> PointPolicy.create("   ", 1, 100_000, 1_000_000,
				365, 1, 1824, now, "system", "공백 라벨", now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void appliedFrom이_null이면_생성이_거절된다() {
		assertThatThrownBy(() -> PointPolicy.create("v2", 1, 100_000, 1_000_000,
				365, 1, 1824, null, "system", "적용시각 누락", now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void changedBy가_100자를_넘으면_생성이_거절된다() {
		String tooLong = "a".repeat(101);

		assertThatThrownBy(() -> PointPolicy.create("v2", 1, 100_000, 1_000_000,
				365, 1, 1824, now, tooLong, "사유", now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void changeReason이_500자를_넘으면_생성이_거절된다() {
		String tooLong = "a".repeat(501);

		assertThatThrownBy(() -> PointPolicy.create("v2", 1, 100_000, 1_000_000,
				365, 1, 1824, now, "system", tooLong, now))
				.isInstanceOf(BusinessException.class);
	}

}
