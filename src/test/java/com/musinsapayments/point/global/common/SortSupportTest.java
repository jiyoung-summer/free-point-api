package com.musinsapayments.point.global.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.musinsapayments.point.global.exception.BusinessException;

class SortSupportTest {

	private static final Set<String> ALLOWED = Set.of("createdAt", "amount");

	@Test
	void 허용된_필드로만_정렬하면_예외가_발생하지_않는다() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("amount"));

		assertThatCode(() -> SortSupport.validate(sort, ALLOWED)).doesNotThrowAnyException();
	}

	@Test
	void 화이트리스트에_없는_필드로_정렬하면_예외가_발생한다() {
		Sort sort = Sort.by(Sort.Order.asc("policyId"));

		assertThatThrownBy(() -> SortSupport.validate(sort, ALLOWED))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 존재하지_않는_필드명이어도_화이트리스트_검증에서_먼저_걸러진다() {
		Sort sort = Sort.by(Sort.Order.asc("doesNotExist"));

		assertThatThrownBy(() -> SortSupport.validate(sort, ALLOWED))
				.isInstanceOf(BusinessException.class);
	}

}
