package com.musinsapayments.point.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트에서 "시간이 지난" 상황(만료 등)을 Thread.sleep 없이 검증하기 위한 Clock.
 */
public class MutableClock extends Clock {

	private Instant instant;
	private final ZoneId zone;

	private MutableClock(Instant instant, ZoneId zone) {
		this.instant = instant;
		this.zone = zone;
	}

	public static MutableClock startingAt(Instant instant) {
		return new MutableClock(instant, ZoneId.systemDefault());
	}

	public void advance(Duration duration) {
		this.instant = this.instant.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(instant, zone);
	}

	@Override
	public Instant instant() {
		return instant;
	}

}
