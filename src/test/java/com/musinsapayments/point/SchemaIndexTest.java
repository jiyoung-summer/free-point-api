package com.musinsapayments.point;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * db/schema.sql은 "참고 DDL"일 뿐 앱이 실제로 로드하는 파일이 아니다 — 실제 스키마는
 * Hibernate가 엔티티 애너테이션(ddl-auto: create-drop)으로 만든다. 그래서 schema.sql에
 * 인덱스를 적어놓는 것만으로는 아무 효과가 없고, 엔티티에 {@code @Table(indexes = ...)}로도
 * 반드시 선언해야 한다 — 실제로 이 선언이 빠져 있어서 point_lot/point_transaction/point_policy 등
 * 핵심 조회 경로(사용 가능 Lot 조회, 잔액 합계, 적립취소 시 point_key 조회)가 인덱스 없이
 * 풀스캔되고 있었던 것을 이 테스트를 작성하며 발견했다. 이 테스트는 그중 가장 빈번히 실행되는
 * 두 인덱스(point_lot 조건 인덱스, point_key 인덱스)가 나중에 애너테이션 삭제 등으로 조용히
 * 사라지지 않는지 지키는 회귀 테스트다.
 */
@SpringBootTest
class SchemaIndexTest {

	@Autowired
	private DataSource dataSource;

	@Test
	void point_lot에_사용가능조회와_point_key조회를_받쳐주는_인덱스가_실제로_존재한다() throws Exception {
		Set<String> indexNames = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'POINT_LOT'")) {
			while (rs.next()) {
				indexNames.add(rs.getString(1));
			}
		}

		assertThat(indexNames)
				.as("findUsableLotsForAllocation/sumBalance가 의존하는 복합 인덱스")
				.contains("IDX_POINT_LOT_USABLE");
		assertThat(indexNames)
				.as("earnCancel()의 findByPointKey가 의존하는 인덱스")
				.contains("IDX_POINT_LOT_POINT_KEY");
	}

}
