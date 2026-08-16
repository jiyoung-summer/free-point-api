package com.musinsapayments.point.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스 계층은 LocalDateTime.now() 대신 이 Clock을 주입받아 사용한다.
 * 테스트에서 Clock 구현체를 교체하면 Thread.sleep 없이 "시간이 지난" 상황(만료 등)을 검증할 수 있다.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}

}
