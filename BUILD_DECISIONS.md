# Build Decisions

검증 기준일은 2026-10-01입니다. Java 17을 유지하면서 신규 프로젝트를 안정 GA 구성으로 작성했습니다. 아래 버전은 [build.gradle](build.gradle), [wrapper properties](gradle/wrapper/gradle-wrapper.properties), [compose.yaml](compose.yaml)에 고정했습니다.

| 구성 | 선택 버전 | 근거 |
| --- | --- | --- |
| Java | 17 | [Spring Boot 4.0 system requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)의 최소 Java 버전 |
| Spring Boot | 4.0.8 | 공식 Stable GA이며 Java 17, Gradle 8.14+ 지원. MyBatis Starter 4.0 계열의 명시적 baseline과 맞춤 |
| Gradle | 8.14.5 | [공식 patch release](https://docs.gradle.org/8.14.5/release-notes.html). Java 17 실행 및 Boot의 Gradle 요구사항 충족 |
| MyBatis Starter | 4.0.1 | [공식 compatibility matrix](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)의 Java 17 / Boot 4.0+ 조합, [GA release](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.0.1) |
| MyBatis / MyBatis Spring | 3.5.19 / 4.0.0 | Starter 4.0.1이 관리하는 버전 |
| Flyway core / PostgreSQL module | 11.14.1 | Boot 4.0.8 BOM이 관리. [PostgreSQL 지원 모듈](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)을 함께 사용 |
| pgJDBC | 42.7.13 | Boot 4.0.8 BOM이 관리하는 PostgreSQL JDBC driver |
| PostgreSQL | 16.15 | 안정 16 계열의 [patch release](https://www.postgresql.org/docs/16/release-16-15.html). 전용 Compose image를 manifest digest로 고정 |
| JUnit Jupiter / Mockito / AssertJ | 6.0.3 / 5.20.0 / 3.27.7 | Boot 4.0.8 test starter/BOM이 관리하는 안정 버전 |

## 선택 이유와 확인 방법

최신 Stable 전체 계열을 무조건 선택하기보다, Java 17과 MyBatis Starter 4.0의 공식 지원 조합인 Boot 4.0.8을 선택했습니다. Boot 4.1이나 preview로 범위를 넓히지 않았습니다. milestone, RC, snapshot은 사용하지 않습니다. 원본의 Spring Boot 2.7.18과 Gradle 설정은 복사하지 않았습니다.

Spring Boot BOM은 [Maven Central의 공식 배포 POM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.8/spring-boot-dependencies-4.0.8.pom)으로 확인했습니다. Web MVC, validation, Flyway starter는 Boot 4의 모듈 구조를 따릅니다. PostgreSQL 통합 테스트가 Flyway fresh/legacy migration과 실제 MyBatis SQL을 모두 실행했으므로 문서상 호환성만 확인한 상태는 아닙니다. [PostgresMigrationTest](src/postgresTest/java/com/codewus/showcase/PostgresMigrationTest.java), [PostgresReliabilityTest](src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java)를 참고합니다.

Gradle wrapper는 별도 bootstrap 디렉터리에서 생성한 후 Gradle 8.14.5 자체로 다시 생성했습니다. `distributionSha256Sum`과 wrapper JAR hash를 Gradle의 [공식 checksum](https://gradle.org/release-checksums/)과 대조했습니다. 원본 프로젝트의 wrapper를 가져오지 않았습니다. Java toolchain은 17이며 JDK 자동 다운로드는 사용하지 않습니다.

## 실제 검증

- `./gradlew clean test`: 성공, unit 19건, 실패·오류·skip 0.
- `docker compose up -d postgres`: 전용 DB 생성, PostgreSQL 16.15, loopback port 확인.
- `./gradlew postgresTest`: 성공, PostgreSQL 21건, 실패·오류·skip 0.
- DB가 없는 로컬 포트로 `postgresTest` 실행: exit 1. assumption skip이나 성공 처리 없음.
- `./gradlew bootRun --args='--spring.profiles.active=demo'`: 성공. `scripts/demo.sh`와 호환 fixture 실행은 로컬 API에서 확인. 실제 JVM 재기동 후 저장된 Fill·ledger·합성 상태를 보존하고 누락 Event만 복구하는 것도 확인.

검증 결과는 공개본에서 실행한 결과입니다. 원본의 테스트 개수나 통과 결과는 재사용하지 않았습니다. 첫 build의 wrapper/dependency 다운로드 이후 unit 테스트 실행 자체에는 DB와 외부 네트워크가 필요하지 않습니다.

## 경고와 환경 차이

초기 Gradle DSL의 `exceptionFormat 'full'` deprecation은 `exceptionFormat = 'full'`로 수정했습니다. 최종 build에는 해당 deprecation이나 Java compiler warning이 없습니다. Mockito test JVM에서 bootstrap classpath 추가에 따른 class data sharing 경고가 발생합니다. 경고를 숨기는 옵션은 추가하지 않았으며 기능 검증 결과에는 영향이 없었습니다.

로컬 검증 JVM은 Java 17.0.1입니다. 이 프로젝트는 JDK를 배포하지 않으며, 실행 환경은 최신 보안 패치를 적용한 Java 17을 준비하는 것이 적절합니다. 원본의 JUnit 5 테스트 원칙은 공개본에서 Boot BOM의 JUnit Jupiter 6으로 재현했습니다.

## CI 구성

[verify.yml](.github/workflows/verify.yml)은 Ubuntu 24.04와 Temurin Java 17을 사용합니다. [actions/checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1)과 [actions/setup-java v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1)을 release commit SHA로 고정했습니다. 공식 Node 24 Action을 GitHub hosted runner에서 실행하며 checkout credential은 보존하지 않습니다. unit과 PostgreSQL job을 분리하고 [공식 service container 구성](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers)을 따릅니다. 테스트 0개 또는 skip은 [build.gradle](build.gradle)에서 실패시킵니다.
