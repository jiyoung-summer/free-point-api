# Free Point API

무신사페이먼츠 Backend Engineer 과제 — 적립 / 적립취소 / 사용 / 사용취소를 제공하는 무료 포인트 API.

- **개발 환경** : Java 21, Spring Boot 3.5.16, Spring Data JPA, H2(in-memory), Gradle 8.14.5
- **패키지** : `com.musinsapayments.point`
- **ERD** : [`resources/erd_core.svg`](resources/erd_core.svg) (확장: [`resources/erd_ext.svg`](resources/erd_ext.svg)), 참고 DDL: [`db/schema.sql`](db/schema.sql)
- **AWS 아키텍처(가정)** : [`resources/aws_architecture.svg`](resources/aws_architecture.svg)

---

## 1. 빌드 및 실행

```bash
# 빌드 + 테스트
./gradlew clean build

# 애플리케이션 실행 (기본 포트 8080)
./gradlew bootRun

# 또는 jar 실행
java -jar build/libs/free-point-api-0.0.1-SNAPSHOT.jar
```

| 항목 | 값 |
|---|---|
| Health check | `GET http://localhost:8080/api/health` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI 스펙(JSON) | `http://localhost:8080/v3/api-docs` |
| H2 콘솔 | `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:pointdb;MODE=MySQL`, user: `sa`, password 없음) |

기동 시 `PointPolicyInitializer`가 정책 테이블이 비어 있으면 기본 정책 1건을 자동으로 시딩한다 (1회 적립 1–100,000 / 개인별 최대 보유 1,000,000 / 만료일 1–1,824일(5년 미만) / 기본 만료 365일). 이후 정책 변경은 코드 재배포가 아니라 이 테이블에 새 버전 행을 추가하는 방식으로 이뤄진다(현재 별도 정책 변경 API는 없음 — "남은 과제" 참고).

### 테스트

```bash
./gradlew test
```

| 테스트 | 검증 내용 |
|---|---|
| `PointLotTest` | 사용/적립취소 상태 전이, 잔액 부족·만료·중복취소 거절, 만료 시각 경계값, 사용 금액 0/음수 거절, `restore()`의 4대 invariant(양수/ACTIVE/미만료/원금 초과 금지)와 오버플로의 `AMOUNT_OVERFLOW` 변환, 만료된 미사용 lot의 적립취소 거절 |
| `PointPolicyTest` | 적립 금액/보유 한도/만료일 범위 검증, 만료일 미지정 시 기본값, 만료일 상한(1,824일), `versionLabel`/`appliedFrom`/`changedBy`/`changeReason` 필드 검증, 보유 한도 오버플로 방어 |
| `RequestValidationTest` | 요청 DTO의 `@Positive` 검증 범위(0/음수 거절, 상한은 없음) |
| `PointServiceValidationTest` | `@Valid`가 적용되지 않는 서비스 직접 호출 경로에서도 amount<=0이 거절되고 잔액에 흔적을 남기지 않는지 |
| `SortSupportTest` | `/lots`, `/transactions` 정렬 필드 화이트리스트 |
| `PageableSupportTest` | `size` 상한 clamp, `page`/`sort`는 그대로 유지 |
| `PointControllerPaginationTest` | HTTP 레벨 `size` 상한 clamp, 음수 `page` 방어, 화이트리스트 밖 정렬 필드 400 |
| `PointControllerExplicitPageSizeCapTest` | 전역 설정과 무관하게 컨트롤러 명시 상한이 독립적으로 적용되는지 |
| `PointServiceScenarioTest` | 요구사항 예시 시나리오(A–E) 전체 재현, 조회 응답의 `usable`/`expired` 필드, 만료된 미사용 적립의 취소 거절 |
| `PointServiceConcurrentAccessTest` | 동시 적립/사용에서 lost update 없음, 잔액을 초과하는 동시 사용 요청이 몰려도 감당 가능한 건수만 성공 |
| `PointServiceUseCancelConcurrencyTest` | 같은 사용 거래에 부분 사용취소가 동시에 몰려도 취소 가능 금액을 넘지 않음 |
| `PointLotUsableConditionTest` | `USABLE_CONDITION`이 빠지면 즉시 실패하는 회귀 테스트 |
| `PointAccountProvisionerConcurrencyTest` | 계정이 없는 사용자에게 최초 적립이 2–10건 동시에 들어와도 계정 1개, 정확한 lot 수/잔액 |
| `PointControllerValidationTest` | HTTP 레벨 검증/에러 응답(적립 금액 0/음수, 빈 주문번호, 404, 파라미터 누락/타입 불일치, JSON 파싱 실패 500, 헬스 체크), `earn`/`earn cancel`/`use`/`use cancel` 4개 엔드포인트의 성공 응답 |
| `PointServiceIdempotencyTest` | `Idempotency-Key` 재시도 캐시, 키는 같고 내용이 다른 요청의 `IDEMPOTENCY_KEY_REUSED` 거절, 키 값 검증(공백/200자 초과/제어 문자), 동시 요청 시 정확히 1건만 처리 |
| `PointServiceIdempotencyRollbackTest` | 멱등 응답 저장(직렬화)이 실패하면 같은 트랜잭션의 lot/거래 생성까지 전부 롤백되는지, 재시도 시 저장된 응답 복원(역직렬화)이 실패하면 비즈니스 로직 재실행 없이 잔액에 흔적을 남기지 않는지, 둘 다 `IDEMPOTENCY_CODEC_FAILED`로 구분되는지 |
| `PointServiceMissingPolicyTest` | 정책이 하나도 없으면 다른 500 원인과 뭉뚱그려지지 않고 `POLICY_NOT_CONFIGURED`로 거절되는지 |
| `PointControllerIdempotencyTest` | `Idempotency-Key` HTTP 헤더 배선, 공백 헤더 거절, GET 요청에 보낸 헤더는 완전히 무시되는지 |
| `SchemaIndexTest` | 핵심 인덱스가 실제 실행 스키마에 존재하는지 |
| `PointApiApplicationTests` | Spring 컨텍스트 정상 기동 |

만료 시나리오는 `Clock`을 빈으로 분리(`ClockConfig`)하고 테스트에서 `MutableClock`으로 교체해, `Thread.sleep` 없이 시간을 앞당겨 검증한다. 동시성 테스트는 `Thread.sleep` 기반 타이밍 대신 `CountDownLatch`로 여러 스레드를 같은 순간에 출발시켜 실제 경쟁 상태를 재현한다.

### 테스트 커버리지

```bash
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html   # 사람이 보는 HTML 리포트
```

`test`가 끝나면 `jacocoTestReport`가 자동으로 뒤따라 실행된다(`build.gradle`의 `finalizedBy`). 리포트는 `build/reports/jacoco/test/html/index.html`(사람이 볼 것)과 `build/reports/jacoco/test/jacocoTestReport.xml`(CI 연동용)에 남는다. 수치는 커밋마다 달라지므로 README에 스냅샷을 남기지 않는다 — 필요할 때마다 위 명령으로 직접 확인한다.

---

## 2. API 목록

| Method | URI | 설명 | `Idempotency-Key` |
|---|---|---|---|
| POST | `/api/v1/points/earn` | 포인트 적립 | 지원(선택 — 보내야 멱등성 활성화) |
| POST | `/api/v1/points/earn/{pointKey}/cancel` | 적립 취소 (미사용 적립분만) | 지원(선택 — 보내야 멱등성 활성화) |
| POST | `/api/v1/points/use` | 포인트 사용 (주문 시) | 지원(선택 — 보내야 멱등성 활성화) |
| POST | `/api/v1/points/use/{pointKey}/cancel` | 사용 취소 (전체/부분) | 지원(선택 — 보내야 멱등성 활성화) |
| GET | `/api/v1/points/balance?userId=` | 잔액 조회 | 해당 없음(GET — 보내도 무시됨) |
| GET | `/api/v1/points/lots?userId=&page=&size=` | 적립분 목록 (만료/취소 포함 전체 이력) | 해당 없음(GET — 보내도 무시됨) |
| GET | `/api/v1/points/transactions?userId=&page=&size=` | 거래 이력 (EARN/EARN_CANCEL/USE/USE_CANCEL) | 해당 없음(GET — 보내도 무시됨) |
| POST | `/api/v1/admin/points/manual-earn` | 관리자 수기 지급 | 지원(선택 — 보내야 멱등성 활성화) |

> **상태를 바꾸는 5개 API는 `Idempotency-Key` 헤더를 보내야만 멱등성이 활성화된다.** 헤더가 필수가 아니라 선택이라서, 생략하면 이 API들도 매 요청마다 새로 처리되고 재시도 시 중복 적립/차감이 발생할 수 있다. "API가 멱등하다"가 아니라 "헤더를 보내면 멱등해진다"가 정확한 설명이다.

`lots`/`transactions`는 `Pageable` 파라미터(`page`, `size`, `sort`)를 지원하며 기본값은 `size=20, sort=createdAt,desc`이다. `Pageable`을 그대로 노출하면 `size`를 과도하게 키우거나 임의 필드로 정렬할 수 있어 두 가지로 제한한다:

- **`size`** : 전역 설정(`spring.data.web.pageable.max-page-size: 100`)에 더해, 컨트롤러에도 `MAX_LOT_PAGE_SIZE`/`MAX_TRANSACTION_PAGE_SIZE`(`PageableSupport.capSize`)로 상한을 한 번 더 명시해 이중으로 방어한다.
- **`sort`** : 엔드포인트별 화이트리스트(`SortSupport`)를 통과한 필드만 허용한다 — `lots`는 `createdAt/expireAt/amount/remainingAmount`, `transactions`는 `createdAt/amount`만 가능하며, 그 외 필드를 요청하면 400을 반환한다.

### 재시도 멱등성 — `Idempotency-Key` 헤더

`point_transaction.point_key`는 서버가 요청마다 새로 발급하는 UUID라 재시도 멱등키로 쓸 수 없다(재시도해도 매번 새 UUID가 나온다). 상태를 바꾸는 5개 엔드포인트(`earn`/`earn cancel`/`use`/`use cancel`/`manual-earn`)가 선택적으로 받는 `Idempotency-Key` 헤더와 `idempotency_key` 테이블로 재시도 멱등성을 보장한다.

- `(user_id, operation_type, idempotency_key)` 3개 컬럼에 DB unique 제약을 걸고, 요청 지문(`request_hash`, SHA-256)과 최초 처리 응답(JSON)을 저장한다. `operation_type`을 포함하는 이유는 클라이언트가 같은 키를 실수로 재사용해도 적립과 사용처럼 서로 다른 작업까지 뒤섞여 막히지 않게 하기 위함이다.
- 처리 순서: ① `PointAccount` 비관적 락을 먼저 잡는다 → ② `idempotency_key`에서 기존 응답을 조회한다 → ③ 있으면(그리고 `request_hash`가 같으면) 저장된 응답을 그대로 반환한다 → ④ 없으면 평소대로 처리 후 같은 트랜잭션 안에서 응답을 저장한다. 계정 락이 같은 사용자의 동시 요청을 이미 직렬화하므로 별도의 분산 락이 필요 없다.
- 캐시 조회는 정책 검증보다 먼저 한다 — 재시도는 정책이 그 사이 바뀌었더라도 항상 최초 결과를 그대로 돌려줘야 한다.
- `request_hash`는 요청의 핵심 필드(EARN: `userId/amount/expireDays/memo`, EARN_CANCEL: `earnPointKey`, USE: `userId/orderNo/amount`, USE_CANCEL: `usePointKey/amount`)를 JSON 배열로 직렬화해 해시한다. 같은 키로 다른 내용의 요청이 오면 캐시를 쓰지 않고 `IDEMPOTENCY_KEY_REUSED`(409)로 거절한다.
- 저장은 `saveAndFlush()`로 즉시 반영한다 — DB unique 제약 위반은 (정상 경로에서는 계정 락이 이미 막아주지만) `IDEMPOTENCY_KEY_REUSED`로 변환하는 최종 안전망이다.
- 헤더 값 자체도 검증한다: 공백, 200자 초과, 제어 문자 포함 시 `INVALID_INPUT_VALUE`(400)로 거절한다.
- 헤더를 보내지 않으면 멱등성 보장 없이 매번 새로 처리된다(하위 호환을 위한 선택 사항).
- **GET 엔드포인트(`balance`/`lots`/`transactions`)는 `Idempotency-Key` 헤더를 완전히 무시한다.** GET은 HTTP 시맨틱상 이미 멱등(부작용 없이 반복 호출 가능)하므로 별도 처리가 필요 없다 — 컨트롤러 메서드에 이 헤더를 바인딩하는 파라미터 자체가 없어서 Spring이 값을 읽지도 않는다. 공백/과다길이처럼 POST였다면 거절됐을 값을 보내도 조용히 무시되고 요청은 정상 처리된다.

```bash
# 같은 Idempotency-Key로 재시도해도 중복 적립되지 않는다
curl -s -X POST $BASE/points/earn -H 'Content-Type: application/json' -H 'Idempotency-Key: client-generated-key-1' \
  -d '{"userId":1,"amount":1000}'
```

### 요청 예시 — 요구사항 예시(A–E) 그대로 재현

```bash
BASE=http://localhost:8080/api/v1

# 1) 1000원 적립 (pointKey A) — 잔액 0 -> 1000
curl -s -X POST $BASE/points/earn -H 'Content-Type: application/json' \
  -d '{"userId":1,"amount":1000}'

# 2) 500원 적립 (pointKey B) — 잔액 1000 -> 1500
curl -s -X POST $BASE/points/earn -H 'Content-Type: application/json' \
  -d '{"userId":1,"amount":500}'

# 3) 주문 A1234 에서 1200원 사용 (pointKey C): A에서 1000, B에서 200 — 잔액 1500 -> 300
curl -s -X POST $BASE/points/use -H 'Content-Type: application/json' \
  -d '{"userId":1,"orderNo":"A1234","amount":1200}'

# 5) C의 사용금액 1200원 중 1100원 부분 사용취소 (pointKey D) — 잔액 300 -> 1400
#    (A가 이미 만료되어 있었다면 A 몫 1000원은 신규적립(pointKey E)으로, B 몫 100원은 B에 직접 복원된다)
curl -s -X POST $BASE/points/use/{C의 pointKey}/cancel -H 'Content-Type: application/json' \
  -d '{"amount":1100}'

# 잔액 / 적립분 목록 / 거래 이력 조회
curl -s "$BASE/points/balance?userId=1"
curl -s "$BASE/points/lots?userId=1"
curl -s "$BASE/points/transactions?userId=1"

# 관리자 수기 지급
curl -s -X POST $BASE/admin/points/manual-earn -H 'Content-Type: application/json' \
  -d '{"userId":1,"amount":300,"memo":"이벤트 보상"}'

# 적립취소 (미사용 적립분만)
curl -s -X POST $BASE/points/earn/{pointKey}/cancel
```

모든 응답은 `{"success":true,"data":{...},"message":null}` 형태의 공통 포맷(`ApiResponse`)으로 감싸지며, 실패 시 HTTP 상태 코드와 함께 `{"code":"ERROR_CODE","message":"..."}`가 반환된다(`GlobalExceptionHandler`). `MissingServletRequestParameterException`(파라미터 누락), `MethodArgumentTypeMismatchException`(타입 불일치), `MethodArgumentNotValidException`(`@Valid` 실패)은 각각 400으로 매핑되고, 그 외 예외는 catch-all로 500을 반환한다.

500(서버 오류)은 원인별로 코드를 분리했다 — `ACCOUNT_PROVISIONING_FAILED`(계정 조회/생성 실패), `IDEMPOTENCY_CODEC_FAILED`(멱등 응답 JSON 직렬화/역직렬화 실패), `POLICY_NOT_CONFIGURED`(적용 가능한 정책 없음), 나머지 예상 못한 예외는 `INTERNAL_SERVER_ERROR`. 클라이언트에 보이는 메시지는 여전히 일반적이지만(내부 구현 용어 노출 방지), 이 코드들과 서버 로그(`GlobalExceptionHandler`가 5xx만 스택트레이스와 함께 `ERROR` 레벨로 남긴다 — 4xx는 정상 트래픽이라 로그를 남기지 않는다)로 운영 중 원인을 구분할 수 있다.

예외 경로 외에도 운영 중 신호가 필요한 지점에는 로그를 남긴다: 기동 시 기본 정책 시딩 여부(`PointPolicyInitializer`, `INFO`), 신규 계정 생성 경합이 감지·처리된 경우(`PointService.resolveAccountForUpdate`, `DEBUG`), idempotency UK 충돌이 실제로 발생한 경우(`PointService.saveIdempotentResponseIfPresent`, `WARN` — 계정 락으로 이미 막혔어야 할 경쟁이라 발생 자체가 이상 신호). 마지막 것은 응답이 4xx(`IDEMPOTENCY_KEY_REUSED`)라 `GlobalExceptionHandler`의 5xx 자동 로깅을 타지 않으므로 별도로 남긴다.

---

## 3. 도메인 모델

ERD: [`resources/erd_core.svg`](resources/erd_core.svg) · 참고 DDL: [`db/schema.sql`](db/schema.sql)

| 테이블 | 역할 |
|---|---|
| `point_account` | 사용자별 계정. 잔액 변경(적립/적립취소/사용/사용취소) 시 비관적 락(`FOR UPDATE`)의 기준점. |
| `point_policy` | 정책 "버전" 원장(append-only). 유효 정책 = `applied_from <= now()` 중 최신 1건. |
| `point_transaction` | 거래 원장. 적립/적립취소/사용/사용취소 행위가 1건씩 기록되고 외부 식별자 `point_key`를 발급받는다. |
| `point_lot` | 적립 단위. EARN 거래 1건 = 1행. 적립금액/사용가능잔액/만료일/적립출처를 보유한다. |
| `point_use_detail` | 사용 상세. (사용거래 × 적립분) 매핑으로 1원 단위 사용 추적과 부분취소를 지원한다. |
| `point_use_cancel_detail` | 사용취소 상세. 어떤 사용상세를 얼마나 되돌렸는지, 만료로 인한 재적립 여부를 기록한다. |
| `idempotency_key` | 재시도 멱등성 저장소. `(user_id, operation_type, idempotency_key)` unique + 요청 지문(`request_hash`) + 최초 처리 응답(JSON). |

### 핵심 설계 : 원장(Transaction) + 적립단위(Lot) 분리

포인트 시스템은 "무엇을 했는가"를 남기는 일과 "지금 얼마가 남았는가"를 관리하는 일이 서로 다르다.

- `point_transaction`은 행위를 불변으로 남기는 원장이고, `point_lot`은 그 중 EARN 행위 하나하나가 지금 얼마나 남아있는지를 관리하는 단위다.
- 둘을 잇는 `point_use_detail` 덕분에 "이 적립분의 1원이 어떤 주문에서 쓰였는가"를 역추적할 수 있다.
- 잔액은 별도 컬럼으로 비정규화하지 않고 항상 아래 쿼리로 계산한다(`PointLotRepository.sumBalance`):

```sql
SELECT COALESCE(SUM(remaining_amount), 0) FROM point_lot
 WHERE user_id = ? AND status = 'ACTIVE' AND expire_at > now()
```

`db/schema.sql`의 범례대로 FK는 논리적 관계로만 표현하고 물리 `FOREIGN KEY` 제약은 걸지 않는다 — 엔티티도 이를 그대로 따라 `@ManyToOne` 대신 평범한 `Long` id 필드로 참조를 표현했다(`PointLot.policyId`, `PointUseDetail.pointLotId` 등).

---

## 4. 요구사항 구현 매핑

| 요구사항 | 구현 위치 |
|---|---|
| 1회 1–100,000 적립, 하드코딩 금지 | `point_policy` 테이블(`PointPolicy`), 기동 시 시드 후 정책 행 추가로 런타임 변경 |
| 개인별 최대 보유 한도, 하드코딩 금지 | `PointPolicy.validateHoldLimit()` — 적립 시 `현재잔액 + 적립액 > 한도`면 거절 |
| 1원 단위 사용처 추적 | `PointUseDetail`(사용거래 × 적립분), 사용 응답의 `allocations` |
| 수기지급 구분 | `PointLot.EarnSource.MANUAL` + 전용 엔드포인트 `/admin/points/manual-earn` (회원용 API에서는 source 조작 불가) |
| 만료일 1일–5년 미만(기본 365일) | `PointPolicy.resolveExpireDays()`가 요청을, `PointPolicy.create()`가 정책 자체(상한 1,824일)를 검증 — 시드 정책 값 1–1,824일 |
| 적립취소: 일부 사용 시 불가 | `PointLot.cancelEarn()` — `remainingAmount != amount`면 `PARTIALLY_USED_LOT_CANNOT_BE_CANCELED` |
| 적립취소: **만료된 적립은 불가**(가정) | `PointLot.cancelEarn()` — `isExpired(now)`면 한 번도 안 썼어도(`remainingAmount == amount`) `POINT_LOT_NOT_USABLE`. 요구사항에 명시되지 않아 내린 가정: 무상 포인트는 만료되면 소멸하므로, 이미 소멸된 적립분은 "취소"할 대상 자체가 없다고 봤다 |
| 사용: 주문 시에만, 주문번호 기록 | `UseRequest.orderNo`(필수), `PointUseDetail.orderNo` |
| 사용: 수기지급 우선 + 만료임박 순 | `PointLotRepository.findUsableLotsForAllocation` — `use_priority asc, expire_at asc, id asc` |
| 사용취소: 전체/부분 | `PointUseDetail.cancelableAmount()`/`cancel()` — 반복 부분취소 가능 |
| 사용취소 시 만료 적립분 → 신규 적립 | `PointService.restoreAllocation()` — 만료 안 됐으면 원본 lot에 직접 복원, 만료됐으면 새 `EARN` 거래 + 새 lot으로 재발급(`origin_lot_id`로 원본 추적) |

---

## 5. 동시성 및 정합성

포인트는 금전성 데이터이므로 중복 사용/초과 사용이 발생하면 안 된다.

- 잔액을 변경하는 모든 메서드(적립/적립취소/사용/사용취소)는 시작 시 `point_account` 행을 `SELECT ... FOR UPDATE`로 선점해 같은 사용자의 요청을 직렬화한다(`PointAccountRepository.findByUserIdForUpdate`).
- 계정이 아직 없는 사용자의 최초 요청은 `PointAccountProvisioner`가 `REQUIRES_NEW` 트랜잭션으로 먼저 생성한다. 동시에 같은 사용자가 처음 적립을 시도해 유니크 제약이 충돌하면 `provision()`은 예외를 그대로 던져 이 짧은 트랜잭션만 롤백시키고, 본 트랜잭션(`PointService.resolveAccountForUpdate`)이 그 예외를 잡아 완전히 별개인 자신의 트랜잭션에서 재조회한다 — 같은 트랜잭션 안에서 예외를 잡고 이어서 조회하면 Hibernate 세션이 깨질 수 있어 의도적으로 분리했다.
- **HikariCP 풀 크기(30)는 이 `REQUIRES_NEW` 패턴을 고려한 값이다.** `REQUIRES_NEW`는 바깥 트랜잭션의 커넥션을 보류한 채 새 커넥션을 하나 더 빌리므로, 계정 생성 경합 중인 스레드 1개가 순간적으로 커넥션 2개를 점유한다. 실제 운영에서는 동시 신규 가입/최초 적립 트래픽 규모에 맞춰 `RDS Proxy`([`resources/aws_architecture.svg`](resources/aws_architecture.svg) 참고)나 풀 크기를 별도로 산정해야 한다.
- 만료 판정은 상태값이 아니라 매 요청 시점에 `expire_at` 컬럼을 직접 비교(`PointLot.isUsable`/`isExpired`)한다. 상태를 실제로 `EXPIRED`로 전환하는 배치는 없다.
  > **규칙(지연 판정 정책)** : 만료 배치가 없어 `status`는 `expireAt`이 지나도 `ACTIVE`로 남을 수 있다. 그래서 "지금 실제로 사용 가능/보유 잔액에 포함되는 적립분"을 조회·통계·정산할 때는 반드시 `status = ACTIVE`와 `expireAt > now`를 **함께** 써야 한다. 이 조건은 `PointLotRepository.USABLE_CONDITION`으로 한 곳에 모아뒀고(`sumBalance`/`findUsableLotsForAllocation`이 공유), `PointLotUsableConditionTest`가 이 조건이 빠지면 즉시 실패하는 회귀 테스트로 지킨다.
- 금액 합산은 `long` 오버플로에 안전하도록 두 겹으로 막는다. ① `PointPolicy.create()`가 `maxEarnAmount`/`maxHoldAmount`를 현실적 상한(1조)으로 제한한다. ② `PointPolicy.validateHoldLimit()`과 `PointService`의 적립분 합산(`sumExact`)이 `Math.addExact`를 써서 오버플로를 예외(`AMOUNT_OVERFLOW`)로 드러낸다.
- 재시도 중복은 위 계정 락과 같은 메커니즘으로 막는다 — `Idempotency-Key` 조회를 계정 락 이후에 하기 때문에, 동시에 도착한 같은 키의 두 요청도 경쟁하지 않는다(§2 참고).

---

## 6. 성능

### 인덱스

실제 실행 스키마는 Hibernate `ddl-auto: create-drop`이 엔티티 애너테이션만 보고 만든다 — `db/schema.sql`은 참고 DDL일 뿐 앱이 로드하지 않는다. 그래서 `PointLot`/`PointTransaction`/`PointPolicy`/`PointUseDetail`/`PointUseCancelDetail` 엔티티에 `@Table(indexes = ...)`로 `db/schema.sql`과 동일한 인덱스를 선언해, 문서와 실제 스키마가 항상 같은 인덱스를 갖도록 맞췄다. 핵심 인덱스:

- `idx_point_lot_usable(user_id, status, use_priority, expire_at, id)` — 사용 가능 Lot 조회(`findUsableLotsForAllocation`)와 잔액 합계(`sumBalance`)가 공유한다.
- `idx_point_lot_point_key(point_key)` — 적립취소(`earnCancel`)의 조회 경로.
- `idx_point_transaction_user_created(user_id, created_at)` — 거래 내역 조회(`findByUserId`)는 `account_id`가 아니라 조회 편의용 비정규화 컬럼인 `user_id`로 필터링하므로 이 컬럼 기준으로 잡았다.

`SchemaIndexTest`가 `INFORMATION_SCHEMA.INDEXES`를 직접 조회해 핵심 인덱스가 실제 스키마에 존재하는지 회귀 테스트로 지킨다.

### N+1 방지

`useCancel()`은 취소 대상 lot을 `findAllById()`로 한 번에 모아 조회한 뒤 메모리 `Map`에서 찾는다 — 사용취소 상세 건마다 개별 조회하지 않는다.

### 확인했지만 이번 범위에서는 그대로 둔 것

- **`findUsableLotsForAllocation`은 상한 없이 사용 가능한 Lot을 전부 로드한다.** 한 사용자가 수만 건의 소액 Lot을 보유한 병적인 케이스가 아니면 문제되지 않는다 — 상한을 걸려면 정상 요청을 잘못 거절하지 않도록 스트리밍/페이징 소진 로직이 필요해 이번 범위에서는 두지 않았다.
- **`use()`가 소진한 Lot마다 `PointUseDetail`을 개별 저장한다.** `id`가 `IDENTITY` 전략이라 배치 저장으로 바꿔도 왕복 횟수가 줄지 않는다(JDBC 배치 insert와 IDENTITY 전략은 근본적으로 상성이 안 좋다) — 의미 있게 고치려면 ID 생성 전략을 프로젝트 전체적으로 바꿔야 해서 범위 밖으로 남겨둔다.
- **정책 조회(`currentPolicy()`)가 상태를 바꾸는 요청마다 매번 DB를 탄다.** `point_policy`는 거의 안 바뀌는 테이블이라 캐싱 여지가 크다 — [`resources/aws_architecture.svg`](resources/aws_architecture.svg)에 ElastiCache Redis로 캐싱하는 설계를 남겨뒀다(현재 제출 범위는 로컬 H2라 캐시 계층 없이 둔다).

---

## 7. 프로젝트 구조

```
free-point-api
├── README.md
├── build.gradle
├── db/schema.sql                   # ERD 기반 참고 DDL (앱이 로드하는 파일은 아님)
├── resources/erd_core.svg          # ERD
└── src
    ├── main/java/com/musinsapayments/point
    │   ├── controller/             # REST 컨트롤러
    │   ├── service/                # 유스케이스 (PointService, PointAccountProvisioner)
    │   ├── repository/             # Spring Data JPA
    │   ├── domain/                 # 엔티티 + 도메인 규칙(적립/사용/취소 상태 전이)
    │   ├── dto/                    # 요청/응답 record
    │   └── global/
    │       ├── common/             # ApiResponse, PageResponse
    │       ├── config/             # ClockConfig, PointPolicyInitializer
    │       └── exception/          # ErrorCode, BusinessException, GlobalExceptionHandler
    └── test/java/...               # 시나리오 / 단위 테스트
```

금액 계산과 상태 전이 규칙(사용/복원/취소/만료)은 서비스가 아니라 엔티티 내부에 있다 — `PointLot.use()`/`restore()`/`cancelEarn()`, `PointPolicy.create()`가 각자 자신의 불변식(금액 양수 여부, 상태, 만료 여부, 정책 상한 등)을 스스로 검증해서 서비스가 규칙을 우회할 수 없다. 서비스는 락 획득 → 정책 검증 → 도메인 호출 → 기록의 흐름 제어만 담당한다. 이 프로젝트는 처음부터 끝까지 "포인트" 하나만 다루는 단일 도메인이라, 여러 도메인을 가정한 `domain/<feature>/...` 하위 폴더링 대신 기술 계층으로 바로 나눴다(자세한 컨벤션은 [`SKILL.md`](SKILL.md) 참고).

---

## 8. 남은 과제 (의도적으로 범위에서 제외한 것)

- **만료 배치 없음** : 만료는 조회/사용 시점에 `expire_at`을 비교하는 지연(lazy) 판정만 한다. `PointLot.expire()` 상태 전이 메서드는 있지만 이를 호출해 `status`를 실제로 `EXPIRED`로 바꾸는 스케줄러는 없다. `GET /points/lots` 응답은 `status`와 별도로 `usable`/`expired`를 조회 시점에 계산해 함께 내려주므로 클라이언트가 `status`를 사용 가능 여부로 오해할 필요는 없지만, 만료 통계 집계나 만료 임박 알림처럼 `status=EXPIRED` 전환이 실제로 필요한 용도에는 별도 배치가 있어야 한다.
- **정책 변경 API 없음** : `point_policy`는 append-only로 설계했지만, 새 버전 행을 추가하는 관리자 API(`PUT /admin/policy` 등)는 아직 없다. 현재는 기동 시 시딩된 기본 정책 1건만 존재한다.
- **애플리케이션 레벨 인증/인가 없음(의도적 제외)** : `/admin/points/manual-earn`을 포함한 모든 API는 코드 안에 인증/인가 로직이 없다 — `/admin/**` 경로 분리는 있지만 그 자체가 인가는 아니다. 인증(JWT/OAuth2 토큰 검증)과 인가(Role/Scope 검증)는 애플리케이션이 아니라 [`resources/aws_architecture.svg`](resources/aws_architecture.svg)의 API Gateway 계층 책임으로 두고, ECS Fargate에는 이미 인증을 통과한 요청만 도달한다고 가정했다. 다만 이번 제출 범위에서는 로컬 실행 시 Gateway가 없으므로 모든 API가 인증 없이 호출 가능한 상태다.
- **이력 아카이빙 없음** : `point_use_detail`/`point_transaction`은 계속 누적되는 원장이라, 실제 운영이라면 파티셔닝/아카이빙 정책이 필요하다.
- **Swagger UI가 프로필 구분 없이 항상 열려 있음** : 아직 `local`/`prod` 프로필을 분리하지 않아 `springdoc.swagger-ui.enabled: false` 같은 운영 차단 설정이 없다. 실제 배포 전에는 운영 프로필에서 비활성화해야 한다.
- **`Idempotency-Key` 헤더가 필수가 아니라 선택** : 결제 도메인 특성상 재시도는 항상 같은 키를 갖고 오도록 강제하는 편이 더 안전하지만, 기존 클라이언트와의 하위 호환을 위해 선택 사항으로 뒀다. 실제 운영에서는 상태 변경 API에 헤더를 필수로 강제하는 편을 권장한다.
- **멱등 응답 저장소에 TTL/정리 배치 없음** : `idempotency_key`는 append-only로 계속 쌓이기만 한다. 실제 운영에서는 일정 기간이 지난 키를 만료 처리하거나 별도 배치로 정리해야 한다.
