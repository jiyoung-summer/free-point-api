package com.musinsapayments.point.global.common;

import java.util.Set;

import org.springframework.data.domain.Sort;

import com.musinsapayments.point.global.exception.BusinessException;
import com.musinsapayments.point.global.exception.ErrorCode;

/**
 * Pageable을 그대로 외부에 노출하면 클라이언트가 엔티티에 실제로 존재하는 아무 필드로나
 * 정렬을 요청할 수 있다(내부용 필드 노출, 인덱스 없는 컬럼 정렬로 인한 풀스캔 등).
 * 존재하지 않는 필드를 요청하면 Spring Data가 쿼리 실행 시점에야
 * PropertyReferenceException을 던져 500으로 새어나가므로, 컨트롤러 진입 시점에
 * 화이트리스트로 먼저 걸러 400으로 명확히 응답한다.
 */
public final class SortSupport {

	private SortSupport() {
	}

	public static void validate(Sort sort, Set<String> allowedProperties) {
		for (Sort.Order order : sort) {
			if (!allowedProperties.contains(order.getProperty())) {
				throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
						"정렬할 수 없는 필드입니다: " + order.getProperty());
			}
		}
	}

}
