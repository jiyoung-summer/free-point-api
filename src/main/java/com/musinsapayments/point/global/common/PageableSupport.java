package com.musinsapayments.point.global.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * spring.data.web.pageable.max-page-size(application.yml)가 전역 상한을 걸어주긴 하지만,
 * 그 설정 하나에만 의존하면 컨트롤러 코드만 봐서는 상한이 있는지 알 수 없고 설정이 바뀌거나
 * 실수로 빠지면 안전망이 조용히 사라진다. API별로 코드에 상한을 명시해 이중으로 방어한다.
 */
public final class PageableSupport {

	private PageableSupport() {
	}

	public static Pageable capSize(Pageable pageable, int maxSize) {
		if (pageable.getPageSize() <= maxSize) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), maxSize, pageable.getSort());
	}

}
