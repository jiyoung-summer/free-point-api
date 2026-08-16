package com.musinsapayments.point.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;

class PointLotTest {

	private final LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);

	private PointLot activeLot(long amount) {
		return PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				amount, now.plusDays(365), now);
	}

	@Test
	void 사용하면_잔액이_차감된다() {
		// given
		PointLot lot = activeLot(1000);

		// when
		lot.use(300, now);

		// then
		assertThat(lot.getRemainingAmount()).isEqualTo(700);
	}

	@Test
	void 잔액보다_많이_사용하면_예외가_발생한다() {
		// given
		PointLot lot = activeLot(1000);

		// when & then
		assertThatThrownBy(() -> lot.use(1001, now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 사용_금액이_0이면_예외가_발생하고_잔액이_바뀌지_않는다() {
		// given
		PointLot lot = activeLot(1000);

		// when & then
		assertThatThrownBy(() -> lot.use(0, now))
				.isInstanceOf(BusinessException.class);
		assertThat(lot.getRemainingAmount()).isEqualTo(1000);
	}

	@Test
	void 사용_금액이_음수면_예외가_발생하고_잔액이_오히려_늘어나지_않는다() {
		// given — use(-1)을 막지 않으면 remainingAmount -= (-1) 이 되어 잔액이 오히려 늘어난다.
		PointLot lot = activeLot(1000);

		// when & then
		assertThatThrownBy(() -> lot.use(-1, now))
				.isInstanceOf(BusinessException.class);
		assertThat(lot.getRemainingAmount()).isEqualTo(1000);
	}

	@Test
	void 만료된_lot은_사용할_수_없다() {
		// given
		PointLot lot = PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				1000, now.minusDays(1), now.minusDays(400));

		// when & then
		assertThat(lot.isUsable(now)).isFalse();
		assertThatThrownBy(() -> lot.use(100, now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 일부_사용된_lot은_적립취소할_수_없다() {
		// given
		PointLot lot = activeLot(1000);
		lot.use(1, now);

		// when & then
		assertThatThrownBy(() -> lot.cancelEarn(now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 미사용_lot은_적립취소하면_잔액이_0이_되고_취소상태가_된다() {
		// given
		PointLot lot = activeLot(1000);

		// when
		lot.cancelEarn(now);

		// then
		assertThat(lot.getStatus()).isEqualTo(PointLot.Status.CANCELED);
		assertThat(lot.getRemainingAmount()).isZero();
	}

	@Test
	void 한번도_사용되지_않았어도_만료된_lot은_적립취소할_수_없다() {
		// given — 무상 포인트는 만료되면 소멸한다: remainingAmount == amount(전혀 안 씀)여도
		// 이미 만료됐다면 취소할 대상 자체가 없다.
		PointLot lot = PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				1000, now.minusDays(1), now.minusDays(400));
		assertThat(lot.getRemainingAmount()).isEqualTo(lot.getAmount()); // 전제: 미사용 상태

		// when & then
		assertThatThrownBy(() -> lot.cancelEarn(now))
				.isInstanceOf(BusinessException.class);
		// 실패한 시도가 상태/잔액을 바꾸면 안 된다.
		assertThat(lot.getStatus()).isEqualTo(PointLot.Status.ACTIVE);
		assertThat(lot.getRemainingAmount()).isEqualTo(1000);
	}

	@Test
	void 만료_시각_1나노초_전이면_미사용_lot은_적립취소할_수_있다() {
		// given — "만료 시각과 정확히 같은 시점"이 이미 만료로 취급되는 경계와 대칭을 이루는 경계값
		LocalDateTime expireAt = now;
		PointLot lot = PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				1000, expireAt, now.minusDays(1));
		LocalDateTime justBeforeExpiry = expireAt.minusNanos(1);

		// when
		lot.cancelEarn(justBeforeExpiry);

		// then
		assertThat(lot.getStatus()).isEqualTo(PointLot.Status.CANCELED);
	}

	@Test
	void 이미_취소된_lot을_다시_취소하면_예외가_발생한다() {
		// given
		PointLot lot = activeLot(1000);
		lot.cancelEarn(now);

		// when & then
		assertThatThrownBy(() -> lot.cancelEarn(now))
				.isInstanceOf(BusinessException.class);
		// 상태/잔액도 그대로 유지되어야 한다(반복 취소 시도가 부작용을 남기지 않음)
		assertThat(lot.getStatus()).isEqualTo(PointLot.Status.CANCELED);
		assertThat(lot.getRemainingAmount()).isZero();
	}

	@Test
	void 만료_시각과_정확히_같은_시점에는_이미_만료된_것으로_취급한다() {
		// given — expireAt과 조회 시각(now)이 정확히 같은 lot
		LocalDateTime expireAt = now;
		PointLot lot = PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				1000, expireAt, now.minusDays(1));

		// when & then — expireAt.isAfter(now)가 false이므로 "만료 시각 그 순간부터" 사용 불가
		assertThat(lot.isUsable(now)).isFalse();
		assertThat(lot.isExpired(now)).isTrue();
		assertThatThrownBy(() -> lot.use(1, now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 만료_시각_1나노초_전까지는_사용_가능하다() {
		// given
		LocalDateTime expireAt = now;
		PointLot lot = PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				1000, expireAt, now.minusDays(1));
		LocalDateTime justBeforeExpiry = expireAt.minusNanos(1);

		// when & then
		assertThat(lot.isUsable(justBeforeExpiry)).isTrue();
		assertThat(lot.isExpired(justBeforeExpiry)).isFalse();
	}

	@Test
	void 복원하면_잔액이_늘어난다() {
		// given
		PointLot lot = activeLot(1000);
		lot.use(400, now);

		// when
		lot.restore(150, now);

		// then
		assertThat(lot.getRemainingAmount()).isEqualTo(750);
	}

	@Test
	void 복원_금액이_0이거나_음수면_예외가_발생한다() {
		// given
		PointLot lot = activeLot(1000);
		lot.use(400, now);

		// when & then
		assertThatThrownBy(() -> lot.restore(0, now)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> lot.restore(-1, now)).isInstanceOf(BusinessException.class);
		// 실패한 시도가 잔액을 바꾸면 안 된다.
		assertThat(lot.getRemainingAmount()).isEqualTo(600);
	}

	@Test
	void 복원_후_잔액이_원래_적립금액을_초과하면_예외가_발생한다() {
		// given — 400만 사용했으므로 복원 가능한 최대는 400인데 401을 복원 시도한다.
		PointLot lot = activeLot(1000);
		lot.use(400, now);

		// when & then
		assertThatThrownBy(() -> lot.restore(401, now))
				.isInstanceOf(BusinessException.class);
		assertThat(lot.getRemainingAmount()).isEqualTo(600);
	}

	@Test
	void 복원_후_잔액이_원래_적립금액과_정확히_같으면_허용된다() {
		// given — 사용한 만큼(400) 정확히 복원하는 경계값
		PointLot lot = activeLot(1000);
		lot.use(400, now);

		// when
		lot.restore(400, now);

		// then
		assertThat(lot.getRemainingAmount()).isEqualTo(1000);
	}

	@Test
	void 복원_합산이_long_범위를_넘으면_INTERNAL이_아니라_AMOUNT_OVERFLOW_비즈니스_예외로_변환된다() {
		// given — remainingAmount가 이미 Long.MAX_VALUE 근처인 비정상 데이터(정책 상한을 우회한 상황을 가정)
		PointLot lot = activeLot(Long.MAX_VALUE);

		// when & then — Math.addExact가 던지는 ArithmeticException이 그대로 새어나가면 안 되고,
		// GlobalExceptionHandler의 catch-all(500)이 아니라 AMOUNT_OVERFLOW(BusinessException)로 잡혀야 한다.
		assertThatThrownBy(() -> lot.restore(10, now))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.AMOUNT_OVERFLOW));
	}

	@Test
	void 취소된_lot은_복원할_수_없다() {
		// given
		PointLot lot = activeLot(1000);
		lot.cancelEarn(now);

		// when & then
		assertThatThrownBy(() -> lot.restore(100, now))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 만료된_lot은_복원할_수_없다() {
		// given
		PointLot lot = PointLot.earn("pk-1", 1L, 100L, 1L, PointLot.EarnSource.ORDER, 100,
				1000, now.minusDays(1), now.minusDays(400));

		// when & then
		assertThatThrownBy(() -> lot.restore(100, now))
				.isInstanceOf(BusinessException.class);
	}

}
