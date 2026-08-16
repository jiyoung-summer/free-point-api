package com.musinsapayments.point.global.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageableSupportTest {

	@Test
	void 상한_이내면_그대로_돌려준다() {
		Pageable pageable = PageRequest.of(2, 50, Sort.by("createdAt").descending());

		Pageable result = PageableSupport.capSize(pageable, 100);

		assertThat(result).isSameAs(pageable);
	}

	@Test
	void 상한을_넘으면_size만_잘라내고_page와_sort는_유지한다() {
		Pageable pageable = PageRequest.of(3, 1_000_000, Sort.by("amount").ascending());

		Pageable result = PageableSupport.capSize(pageable, 100);

		assertThat(result.getPageSize()).isEqualTo(100);
		assertThat(result.getPageNumber()).isEqualTo(3);
		assertThat(result.getSort()).isEqualTo(pageable.getSort());
	}

	@Test
	void 상한과_정확히_같으면_그대로_돌려준다() {
		Pageable pageable = PageRequest.of(0, 100);

		Pageable result = PageableSupport.capSize(pageable, 100);

		assertThat(result).isSameAs(pageable);
	}

}
