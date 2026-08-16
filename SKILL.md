---
name: spring-boot-api
description: Spring Boot로 REST API 서버를 만들거나 확장할 때 사용하는 계층형 아키텍처, 컨벤션, 예외 처리, API 문서화(Swagger/OpenAPI), 로깅 정책, H2/JPA 설정, 테스트 규칙. 사용자가 Spring Boot, Java 백엔드, REST API, 컨트롤러/서비스/리포지토리, 엔드포인트 추가, JPA 엔티티, @RestControllerAdvice, Swagger 문서, 로그 설정, H2 DB, MockMvc 테스트 중 하나라도 언급하면 반드시 이 스킬을 먼저 읽고 따를 것. "API 하나만 추가해줘", "이 코드 리뷰해줘" 처럼 짧은 요청이어도 Java/Spring 맥락이면 적용한다.
---

# Spring Boot API 서버 규칙

Spring Boot 3.5.x / Java 21 / Spring Data JPA / H2 기준 (`free-point-api`, base package `com.musinsapayments.point`). 이 문서의 구조와 컨벤션을 따라 작성하고, 기존 코드가 이 규칙과 다르면 **기존 코드의 스타일을 우선**하되 사용자에게 차이를 한 줄로 알린다.

## 1. 패키지 구조

일반적으로는 기술 계층이 아니라 **도메인(기능) 단위**로 먼저 나누는 것을 권장하지만, 이 프로젝트는 처음부터 끝까지 "포인트" 하나만 다루는 **단일 도메인** 서비스다. 루트 패키지 자체가 이미 도메인(`...point`)이므로 그 밑에 다시 `domain/point/...`를 만드는 건 불필요한 중첩이다. 대신 기술 계층으로 바로 나눈다:

```
com.musinsapayments.point
├── PointApiApplication.java
├── controller           # HealthController, (추가될) PointController 등
├── service               # 비즈니스 로직 (PointService 등)
├── repository            # Spring Data JPA
├── domain                # 엔티티 + 도메인 규칙 (예: PointLot)
├── dto                    # 요청/응답 record
└── global
    ├── common            # ApiResponse
    └── exception         # ErrorCode, BusinessException, ErrorResponse, GlobalExceptionHandler
```

여러 도메인이 실제로 생기는 시점(예: 회원/주문 등 point 외 다른 바운디드 컨텍스트 추가)이 오면 그때 `domain/<feature>/{controller,service,repository,entity,dto}` 형태로 쪼갠다. 그 전까지는 위 구조를 유지한다.

의존 방향은 항상 `controller → service → repository` 한 방향이다. 서비스가 컨트롤러를 참조하거나, 리포지토리가 서비스를 부르는 역방향 의존은 만들지 않는다.

## 2. 계층별 책임

| 계층 | 하는 일 | 하지 않는 일 |
|---|---|---|
| Controller | 요청 매핑, 입력 검증(`@Valid`), DTO 변환, 응답 반환 | 비즈니스 로직, 트랜잭션, 엔티티 직접 조작 |
| Service | 비즈니스 로직, 트랜잭션 경계, 예외 발생 | HTTP 개념(HttpServletRequest, ResponseEntity) 사용 |
| Repository | 쿼리 | 로직 분기, DTO 조립(단순 프로젝션 제외) |
| Entity | 상태와 상태 변경 메서드 | 외부 계층 의존 |

**엔티티를 컨트롤러 밖으로 내보내지 않는다.** 응답은 항상 DTO로 변환한다. 엔티티를 그대로 직렬화하면 지연 로딩 예외가 터지고, 엔티티 필드를 바꾸는 순간 API 스펙이 조용히 깨진다.

## 3. 코딩 컨벤션

- **생성자 주입만 사용.** 필드에 `@Autowired`를 붙이지 않는다. 필드는 `private final`, 클래스에 `@RequiredArgsConstructor`.
- **DTO는 `record`**로 선언한다. 불변이고 보일러플레이트가 없다. 요청 DTO에는 `@NotBlank`, `@Email`, `@Positive` 같은 검증 애너테이션을 붙인다.
- **엔티티에 `@Setter`를 붙이지 않는다.** 상태 변경은 의도가 드러나는 메서드로 (`member.changeNickname(...)`). 어디서 값이 바뀌는지 추적 가능해진다.
- 엔티티 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. JPA는 기본 생성자를 요구하지만 외부에서 빈 객체를 만들 이유는 없다.
- 엔티티에 `@Data`, `@EqualsAndHashCode`, `@ToString`(연관관계 포함)을 쓰지 않는다. 양방향 연관관계에서 무한 재귀가 발생한다.
- 연관관계는 기본 `FetchType.LAZY`. `@ManyToOne`, `@OneToOne`은 기본이 EAGER이므로 명시적으로 지정한다.
- 조회 메서드에는 `@Transactional(readOnly = true)`, 변경 메서드에만 `@Transactional`. 클래스에 readOnly를 걸고 변경 메서드에서 덮어쓰는 방식이 안전하다.
- 상수는 `static final`, 매직 넘버/문자열 금지.
- 컨트롤러 매핑은 복수형 명사 + HTTP 메서드로 표현한다. `POST /api/members`, `GET /api/members/{id}`. `/api/getMember` 같은 동사형 경로는 쓰지 않는다.

## 4. 새 엔드포인트 추가 워크플로

기능 추가 요청을 받으면 **이 순서대로** 만든다. 아래에서 위로 올라가면 컴파일이 계속 깨지고, 위에서 아래로 내려오면 각 단계마다 테스트가 가능하다.

1. **DTO** — 요청/응답 스펙을 먼저 확정한다. 필요한 필드가 무엇인지 여기서 정리되면 나머지가 따라온다.
2. **Entity** — 새 필드/엔티티가 필요하면 추가한다. 기존 엔티티 변경 시 다른 도메인에 미치는 영향을 확인한다.
3. **Repository** — 필요한 조회 메서드 선언. 메서드 이름으로 표현되면 쿼리 메서드, 복잡하면 `@Query`.
4. **Service** — 로직과 트랜잭션. 실패 케이스는 `BusinessException`으로 던진다.
5. **Controller** — 매핑과 검증만. 로직이 3줄을 넘어가면 서비스로 내린다.
6. **문서화** — 컨트롤러/DTO에 OpenAPI 애너테이션을 붙인다 (7절).
7. **테스트** — 최소한 서비스 단위 테스트 1개 + 컨트롤러 슬라이스 테스트 1개.

작업을 마치면 어떤 파일을 만들었고 어떤 엔드포인트가 생겼는지 표로 요약한다.

**예시 (컨트롤러):**

```java
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
@Tag(name = "Member", description = "회원 API")
public class MemberController {

    private final MemberService memberService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "회원 생성", description = "이메일과 닉네임으로 회원을 생성한다.")
    public ApiResponse<MemberResponse> create(@Valid @RequestBody MemberCreateRequest request) {
        return ApiResponse.success(memberService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "회원 단건 조회")
    public ApiResponse<MemberResponse> findOne(@PathVariable Long id) {
        return ApiResponse.success(memberService.findOne(id));
    }
}
```

## 5. 공통 응답 포맷

성공/실패 응답 구조를 통일한다. 클라이언트가 분기 로직을 한 번만 짜면 된다. `global/common/ApiResponse.java`에 이미 구현되어 있다 — 새로 만들지 말고 이걸 쓴다.

```java
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
```

성공 응답은 `data`, 실패 응답은 `message`만 채운다. 실패 시 에러 코드는 body가 아니라 `GlobalExceptionHandler`가 별도로 내려주는 `ErrorResponse`(6절)로 전달하므로, 컨트롤러에서 `ApiResponse.fail(...)`을 직접 호출할 일은 거의 없다 — 실패는 예외를 던지는 것으로 표현한다.

주의: 레코드 컴포넌트 `success`는 자동으로 `success()` 접근자를 생성하므로, 인자 없는 정적 팩토리 메서드 이름을 `success()`로 짓지 않는다(시그니처 충돌로 컴파일 에러). 그래서 무인자 버전은 `ok()`로 분리했다.

HTTP 상태 코드는 그대로 살린다. 모든 응답을 200으로 내리고 body의 success로만 구분하지 않는다.

## 6. 예외 처리

**핵심 원칙: 컨트롤러와 서비스에 try-catch를 쓰지 않는다.** 예외는 던지고, 변환은 한 곳에서 한다. `global/exception/`에 `ErrorCode`, `BusinessException`, `ErrorResponse`, `GlobalExceptionHandler`가 이미 구현되어 있다 — 새 에러는 대부분 `ErrorCode`에 상수 하나 추가하고 필요한 곳에서 `throw new BusinessException(ErrorCode.XXX)`만 하면 된다.

```java
public enum ErrorCode {
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    // 도메인 로직이 추가되면 여기에 구체적인 코드를 늘려간다 (예: INSUFFICIENT_BALANCE 등)

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}

public record ErrorResponse(String code, String message) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
```

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT_VALUE.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

지금은 `@Slf4j` 로깅이 빠져 있다 — 8절 로깅 정책(예외는 `GlobalExceptionHandler` 한 곳에서만 로깅)을 적용하려면 `handleException`에 `log.error("Unhandled exception", e)`를, `handleBusinessException`에 `log.warn(...)`을 추가한다. 예상치 못한 예외의 상세 메시지는 로그에만 남기고 응답에는 노출하지 않는다. 스택 트레이스와 SQL이 그대로 나가면 공격 표면이 된다.

## 7. API 문서화 (Swagger / OpenAPI)

springdoc-openapi를 사용한다. Spring Boot 3.x에서는 springfox가 동작하지 않으므로 쓰지 않는다.

```gradle
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
```

기본 UI 경로는 `/swagger-ui.html`, 스펙 문서는 `/v3/api-docs`.

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Example API")
                .description("회원 관리 REST API")
                .version("v1"));
    }
}
```

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: alpha      # 엔드포인트를 알파벳 순으로
    tags-sorter: alpha
  api-docs:
    path: /v3/api-docs
```

**애너테이션 사용 규칙**

- 컨트롤러 클래스에 `@Tag(name, description)` — 도메인 단위로 그룹이 묶인다.
- 각 메서드에 `@Operation(summary, description)` — summary는 한 줄, description은 부연 설명이나 제약 조건.
- 응답이 여러 갈래면 `@ApiResponses`로 실패 케이스도 적는다. 6절의 `ErrorCode`와 코드 문자열을 일치시킨다.
- DTO 필드에 `@Schema(description = "...", example = "...")`. example이 있으면 "Try it out"이 바로 동작해서 문서의 실사용성이 크게 올라간다.
- 필수 여부는 `@Schema(requiredMode = REQUIRED)`를 따로 쓰기보다 `@NotBlank` 같은 Bean Validation 애너테이션으로 표현한다. springdoc이 자동으로 스펙에 반영하므로 정보가 두 곳에 흩어지지 않는다.

```java
@Schema(description = "회원 생성 요청")
public record MemberCreateRequest(
    @Schema(description = "이메일", example = "user@example.com")
    @NotBlank @Email String email,

    @Schema(description = "닉네임", example = "홍길동")
    @NotBlank @Size(max = 20) String nickname
) {}
```

**주의**

- 내부 전용/관리자 엔드포인트는 `@Hidden`으로 문서에서 제외한다.
- 운영 프로필에서는 Swagger UI를 노출하지 않는다. `springdoc.swagger-ui.enabled: false`를 운영 설정에 둔다.
- 문서화 애너테이션이 로직을 가리지 않게 한다. 설명이 길어지면 `description`을 줄이고 별도 문서로 뺀다.

## 8. 로깅 정책

`@Slf4j`(Lombok)로 로거를 선언하고 SLF4J API만 사용한다. **`System.out.println`과 `e.printStackTrace()`는 쓰지 않는다.** 로그 레벨 제어도, 파일 출력도, 포맷 통일도 되지 않는다.

**레벨 기준**

| 레벨 | 언제 | 예 |
|---|---|---|
| ERROR | 즉시 조치가 필요한 예기치 못한 실패 | 미처리 예외, 외부 연동 실패 |
| WARN | 처리는 됐지만 비정상적인 상황 | 비즈니스 예외, 재시도, 설정 누락 |
| INFO | 시스템 흐름상 남길 가치가 있는 사건 | 애플리케이션 기동, 주요 상태 변경 |
| DEBUG | 개발 중 진단용 | 파라미터 값, 분기 결과 |

기대된 실패(존재하지 않는 ID 조회 등)를 ERROR로 남기지 않는다. ERROR가 흔해지면 알람이 무의미해진다.

**작성 규칙**

- 문자열 연결 대신 플레이스홀더를 쓴다. `log.debug("member={}", id)` — 해당 레벨이 꺼져 있으면 문자열을 만들지 않는다.
- 예외는 마지막 인자로 넘긴다. `log.error("결제 실패: orderId={}", orderId, e)`. 메시지 안에 `e.getMessage()`만 넣으면 스택 트레이스가 사라진다.
- **같은 예외를 두 번 로깅하지 않는다.** 예외 로깅은 `GlobalExceptionHandler` 한 곳에서만 한다. 중간 계층에서 로그를 찍고 다시 던지면 같은 사건이 여러 줄로 흩어진다.
- **민감정보를 남기지 않는다.** 비밀번호, 주민번호, 카드번호, 인증 토큰, 개인 연락처는 로그에 넣지 않는다. 요청 본문을 통째로 찍는 코드는 특히 위험하다. 꼭 필요하면 마스킹한다 (`us**@example.com`).
- 반복 호출 구간(루프 내부, 스케줄러)에서 INFO 이상 로그를 남기지 않는다.

**요청 추적**

필터에서 요청마다 `traceId`를 MDC에 넣고 패턴에 포함시키면, 한 요청이 남긴 로그를 한 줄의 ID로 모아볼 수 있다.

```java
@Component
public class MdcFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();   // 스레드 재사용 시 값이 새는 것을 막는다
        }
    }
}
```

**설정 (`logback-spring.xml`)**

프로필별로 나눈다. 로컬은 콘솔, 운영은 파일 + 롤링.

```xml
<configuration>
  <property name="PATTERN"
            value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n"/>

  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>${PATTERN}</pattern></encoder>
  </appender>

  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
      <maxFileSize>100MB</maxFileSize>
      <maxHistory>30</maxHistory>
      <totalSizeCap>3GB</totalSizeCap>
    </rollingPolicy>
    <encoder><pattern>${PATTERN}</pattern></encoder>
  </appender>

  <springProfile name="local,dev">
    <root level="DEBUG"><appender-ref ref="CONSOLE"/></root>
  </springProfile>

  <springProfile name="prod">
    <root level="INFO">
      <appender-ref ref="CONSOLE"/>
      <appender-ref ref="FILE"/>
    </root>
  </springProfile>
</configuration>
```

`logs/` 디렉터리는 `.gitignore`에 추가한다. 운영에서 `org.hibernate.SQL: debug`를 켜두지 않는다 — 디스크가 빠르게 찬다.

## 9. H2 / JPA 설정

개발·테스트용으로 H2를 사용한다. 운영 DB로 교체될 수 있으므로 **H2 전용 문법에 의존하지 않는다.**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:pointdb;MODE=MySQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
  h2:
    console:
      enabled: true          # dev 프로필에서만
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: create-drop  # 로컬/테스트 전용
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100
    open-in-view: false
logging:
  level:
    org.hibernate.SQL: debug          # dev 프로필에서만
    org.hibernate.orm.jdbc.bind: trace
```

`src/main/resources/application.yml`은 위와 거의 동일하되 아직 프로필로 나뉘어 있지 않다(H2 console과 SQL 로깅이 항상 켜져 있음). 도메인 로직을 추가하며 `prod` 프로필이 필요해지면 그때 `h2.console.enabled`와 `org.hibernate.SQL` 로그를 `local,dev` 프로필로 옮긴다.

- `open-in-view: false`로 둔다. true면 컨트롤러까지 영속성 컨텍스트가 살아있어 지연 로딩 문제가 응답 시점에 숨어버린다. 필요한 데이터는 서비스에서 다 조회해 DTO로 넘긴다.
- `ddl-auto`는 로컬에서만 `create-drop`/`update`. 스키마를 유지해야 하면 Flyway를 붙이고 `validate`로 바꾼다.
- 파일 모드(`jdbc:h2:file:./data/db`)가 필요하면 데이터 경로를 `.gitignore`에 추가한다.
- 컬렉션 조회에서 N+1이 보이면 `fetch join` 또는 `@EntityGraph`로 해결하고, 페이징이 함께 필요하면 `default_batch_fetch_size`를 활용한다.

## 10. 테스트

테스트 없이 기능을 "완료"라고 보고하지 않는다. 최소 조합은 다음과 같다.

| 대상 | 애너테이션 | 확인할 것 |
|---|---|---|
| Service | `@ExtendWith(MockitoExtension.class)` + `@Mock` | 로직 분기, 예외 발생 |
| Controller | `@WebMvcTest` + `MockMvc` + `@MockBean` | 상태 코드, 응답 JSON, 검증 실패 |
| Repository | `@DataJpaTest` | 커스텀 쿼리 |
| 통합 | `@SpringBootTest` + `@AutoConfigureMockMvc` | 주요 시나리오 전체 흐름 |

- 구조는 given / when / then 주석으로 나눈다.
- 단언은 AssertJ(`assertThat`)를 사용한다.
- 테스트 메서드 이름은 한글로 상황을 서술해도 좋다. `이메일이_중복되면_예외가_발생한다()`.
- **성공 케이스만 쓰지 않는다.** 실패 케이스(없는 ID, 중복 값, 검증 실패)를 최소 1개 포함한다.
- `@SpringBootTest`를 남발하지 않는다. 느리고 실패 원인이 넓다. 슬라이스 테스트로 가능하면 슬라이스로.

```java
@Test
void 없는_회원을_조회하면_예외가_발생한다() {
    // given
    given(memberRepository.findById(1L)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> memberService.findOne(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("회원을 찾을 수 없습니다");
}
```

## 11. 작업 완료 전 체크리스트

- [ ] 컨트롤러에 비즈니스 로직이 없는가
- [ ] 엔티티가 응답으로 나가지 않는가
- [ ] 요청 DTO에 검증 애너테이션이 붙었는가
- [ ] 실패 경로가 `BusinessException` + `ErrorCode`로 처리되는가
- [ ] 조회 메서드에 `readOnly = true`가 붙었는가
- [ ] 새 엔드포인트에 `@Operation`, DTO에 `@Schema`가 붙었는가
- [ ] `System.out.println` / `printStackTrace`가 남아있지 않은가
- [ ] 로그에 민감정보가 들어가지 않는가
- [ ] 성공/실패 테스트가 각각 있는가
