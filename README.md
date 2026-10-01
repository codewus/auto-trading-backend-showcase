# Auto Trading Backend Showcase

이 저장소는 비공개 개인 프로젝트에서 주문 처리와 복구에 관한 일부 설계와 실패 시나리오를 선별해, 외부 계정 없이 실행할 수 있도록 재구성한 Backend Showcase입니다. 전체 원본 소스나 운영 환경의 복제본은 아닙니다.

## 어떤 문제를 다루는가

주문을 처리한 외부 시스템의 응답을 받지 못하면 재전송이 중복 주문으로 이어질 수 있습니다. 체결 결과를 저장했더라도 로컬 상태 변경이나 후속 이력이 빠질 수 있습니다. 저장 구조를 바꾸는 중에는 신규 구조와 과거 거래 기록이 같은 거래를 두 번 보여줄 수도 있습니다.

두 흐름으로 이 문제를 확인합니다.

1. **주문 처리와 복구**: 논리 주문의 intent와 submit claim을 저장하고, 응답 유실 시 같은 `clientOrderId`로 재조회합니다. 확인된 aggregate Fill을 기록하고 로컬 적용과 누락 Event를 이어갑니다. [OrderService](src/main/java/com/codewus/showcase/order/OrderService.java), [SQL](src/main/resources/mapper/OrderMapper.xml), [정책 테스트](src/test/java/com/codewus/showcase/OrderPolicyTest.java)를 연결해서 읽을 수 있습니다.
2. **호환 조회**: 신규 Order/Fill을 우선하고, 신규 구조에 없는 과거 거래만 `legacy_trade`에서 보완합니다. [OrderHistoryService](src/main/java/com/codewus/showcase/order/OrderHistoryService.java), [조회 SQL](src/main/resources/mapper/FillMapper.xml), [조회 테스트](src/test/java/com/codewus/showcase/OrderHistoryTest.java)가 판단과 결과를 보여줍니다.

## 공개 범위

입력은 합성 `actorId`, `actionId`, `symbol`, `side`, `quantity`입니다. 외부 시스템은 네트워크를 사용하지 않는 Fake Exchange이고, 저장소는 전용 로컬 PostgreSQL입니다. 주문·체결·적용 상태와 조회 호환성만 다룹니다.

UI, 실제 거래소 연계와 credential, 실계좌, 로그인, 전략, 투자 판단과 수익률, 운영·배포·백업 자료는 포함하지 않습니다. 실제 거래를 실행할 수 없습니다. 모든 예제 데이터는 새로 만든 합성 데이터입니다.

## Architecture

API → Order intent / submit claim → Fake Exchange submit 또는 query → Fill 저장 → 로컬 적용 / Event → 조회와 명시적 복구 순서입니다. [Architecture](docs/architecture.md)의 도식은 각 단계의 transaction 경계를 설명합니다.

- **Order**: 요청과 확인 상태. SQL 예약어를 피하려고 물리 테이블 이름은 `demo_order`를 사용합니다.
- **Fill**: 확인된 하나의 aggregate 체결 결과. 개별 partial execution 목록은 아닙니다.
- **application_ledger / local_state**: Fill 적용 marker와 합성 수량·적용 횟수. 실계좌 잔고가 아닙니다.
- **Event**: `ORDER_CONFIRMED`, `FILL_RECORDED` 후속 이력. Event Sourcing이나 transactional outbox가 아닙니다.

## 대표 설계 판단

| 발생 가능한 문제 | 선택한 방법 | 코드와 검증 |
| --- | --- | --- |
| 같은 action 재요청 또는 다른 payload 재사용 | `(actorId, actionId)`와 canonical fingerprint로 재사용·충돌 구분 | [submit](src/main/java/com/codewus/showcase/order/OrderService.java) → [주문 SQL](src/main/resources/mapper/OrderMapper.xml) → [OrderPolicyTest](src/test/java/com/codewus/showcase/OrderPolicyTest.java) |
| 실행자가 동시에 주문 제출 | DB claim을 먼저 commit하고 승자만 submit. 다른 실행자는 query | [OrderService](src/main/java/com/codewus/showcase/order/OrderService.java) → [V3](src/main/resources/db/migration/V3__claim_and_local_application.sql) → [동시성 테스트](src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java) |
| 응답 유실과 불명확한 결과 | 같은 clientOrderId로 query. claim은 만료·재획득하지 않음 | [FakeExchangeAdapter](src/main/java/com/codewus/showcase/exchange/FakeExchangeAdapter.java) → [응답 유실 테스트](src/test/java/com/codewus/showcase/OrderPolicyTest.java) |
| 이미 적용한 Fill의 반복 복구 | 상태 변경과 marker를 같은 transaction에 저장 | [FillApplicationService](src/main/java/com/codewus/showcase/order/FillApplicationService.java) → [적용 SQL](src/main/resources/mapper/ApplicationMapper.xml) → [rollback·동시 복구 테스트](src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java) |
| Event 저장만 실패 | Fill을 보존하고 누락 Event만 별도 transaction에서 재시도 | [FillRecorder](src/main/java/com/codewus/showcase/order/FillRecorder.java) → [Event SQL](src/main/resources/mapper/EventMapper.xml) → [Event SQL 실패 테스트](src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java) |
| 신규/기존 기록의 이중 표시 | actor·symbol·exchangeOrderId 범위와 legacy 연결로 dedup 후 전체 정렬·limit | [OrderHistoryService](src/main/java/com/codewus/showcase/order/OrderHistoryService.java) → [호환 조회 테스트](src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java) |

[설계 판단](docs/design-decisions.md)과 [실패 지점별 보장](docs/reliability.md)에 선택 이유와 제한을 정리했습니다.

## 코드 읽는 순서

10–15분 동안 핵심 흐름을 따라가려면 다음 순서로 읽습니다.

1. [OrderCommand](src/main/java/com/codewus/showcase/order/model/OrderCommand.java): 논리 ID와 fingerprint.
2. [OrderService.submit / reconcile / get](src/main/java/com/codewus/showcase/order/OrderService.java): intent, claim, submit/query, 순수 GET.
3. [FillRecorder.record / repairEvents](src/main/java/com/codewus/showcase/order/FillRecorder.java): aggregate Fill과 누락 Event.
4. [FillApplicationService.apply](src/main/java/com/codewus/showcase/order/FillApplicationService.java), [V3](src/main/resources/db/migration/V3__claim_and_local_application.sql): 한 번의 로컬 적용.
5. [RecoveryService.recover](src/main/java/com/codewus/showcase/order/RecoveryService.java), [OrderHistoryService.history](src/main/java/com/codewus/showcase/order/OrderHistoryService.java): 명시적 복구와 legacy 보완.
6. [OrderPolicyTest](src/test/java/com/codewus/showcase/OrderPolicyTest.java), [PostgresReliabilityTest](src/postgresTest/java/com/codewus/showcase/PostgresReliabilityTest.java): 실패 시나리오와 실제 SQL 검증.

## 로컬 실행

Java 17 JDK, Docker Compose v2 이상을 준비합니다. Java 17을 기본 JVM으로 선택하거나 `JAVA_HOME`을 해당 JDK로 설정합니다. Gradle을 별도로 설치할 필요는 없습니다. 첫 실행에는 wrapper와 dependency, Docker image 다운로드가 필요합니다. 테스트와 Fake Exchange 자체는 외부 계정·거래소 네트워크를 사용하지 않습니다.

```bash
java -version
./gradlew clean test
docker compose up -d postgres
docker compose ps
./gradlew postgresTest
./gradlew bootRun --args='--spring.profiles.active=demo'
```

DB health가 `healthy`가 된 뒤 `postgresTest`를 실행합니다. API는 `127.0.0.1:18080`, DB는 `127.0.0.1:15432`에만 binding합니다. 전용 `showcase` DB는 합성 로컬 데이터용이며 password 없이 `trust` 인증을 사용합니다. 공개 서비스용 설정으로 사용하지 않습니다. [compose.yaml](compose.yaml), [application.yml](src/main/resources/application.yml), [.env.example](.env.example)을 참고합니다. 기본 설정으로 `.env` 파일은 필요 없습니다. 포트를 바꾸면 Compose와 Gradle을 실행하는 두 터미널 모두에 `SHOWCASE_DB_PORT`를 export해야 합니다.

다른 터미널에서 `curl`, `python3`를 사용하는 [scripts/demo.sh](scripts/demo.sh)를 실행합니다.

```bash
bash scripts/demo.sh
```

정상 주문, 응답 유실, 같은 action 재요청, 반복 결과, 불명확 결과 보호, Event 실패 후 복구와 이력을 assertion으로 확인합니다. 직접 호출하는 최소 예제는 다음과 같습니다.

```bash
curl --fail -H 'Content-Type: application/json' \
  -d '{"actorId":"demo-reader","actionId":"example-1","symbol":"DEMOUSDT","side":"BUY","quantity":1}' \
  http://127.0.0.1:18080/demo/orders
curl --fail 'http://127.0.0.1:18080/demo/history?actorId=demo-reader&limit=20'
curl --fail -X POST 'http://127.0.0.1:18080/demo/recovery?actorId=demo-reader'
```

| API | 용도 |
| --- | --- |
| `POST /demo/orders` | 주문 요청. 같은 action의 다른 payload는 HTTP 409 |
| `GET /demo/orders/{id}?actorId=...` | 저장된 주문·Fill·적용 여부·Event 수 조회 |
| `GET /demo/history?actorId=...&limit=20` | 확인된 거래 이력. limit 1–100 |
| `POST /demo/recovery?actorId=...` | 누락 Fill 재조회, 미적용 Fill 적용, 누락 Event 처리 |
| `POST /demo/scenarios` | actor/action별 `NORMAL`, `RESPONSE_LOST`, `UNKNOWN`, `REPEATED_RESULT`, `EVENT_FAILURE` 선택 |

세 가지 호환 조회 fixture는 [history-fixtures.sql](scripts/history-fixtures.sql)로 재현합니다. 과거 전체 데이터의 backfill은 수행하지 않습니다. fixture는 이력 조회용이며 Fake Exchange 제출을 거친 주문은 아닙니다.

```bash
docker compose exec -T postgres psql -U showcase -d showcase -v ON_ERROR_STOP=1 < scripts/history-fixtures.sql
curl --fail 'http://127.0.0.1:18080/demo/history?actorId=demo-history&limit=20'
```

최근 순서로 `NEW`, `NEW`, `LEGACY` 세 건이 표시됩니다. 종료는 API 터미널에서 Ctrl+C, DB는 `docker compose stop postgres`로 수행합니다.

## 테스트

`./gradlew test`는 DB가 필요 없는 정책·호환 조회 테스트입니다. [unit fixture](src/test/java/com/codewus/showcase/UnitFixture.java)의 mapper doubles와 Fake Exchange를 사용하므로 SQL·rollback 보장까지 대신하지 않습니다.

`./gradlew postgresTest`는 전용 DB에서 [fresh/legacy migration](src/postgresTest/java/com/codewus/showcase/PostgresMigrationTest.java), 실제 MyBatis SQL, unique constraint, 동시 claim·복구, Fill 보존, 적용 rollback, 호환 조회와 HTTP GET 순수성을 검증합니다. 이 task는 **전용 DB의 합성 테스트 데이터를 초기화**합니다. 앱을 종료한 상태에서 실행합니다. DB 연결 실패, 테스트 0개 또는 skip이 있으면 실패합니다. [task 정의](build.gradle)에 해당 조건이 있습니다.

[GitHub Actions](.github/workflows/verify.yml)는 Java 17에서 unit job과 전용 PostgreSQL service를 사용하는 DB job을 각각 실행합니다. 두 test task는 테스트 0개나 skip을 성공으로 처리하지 않습니다. CI에는 별도 credential 등록이 필요 없습니다.

## private 원본과 Showcase의 차이

원본의 일부 설계 판단을 새 package·schema·fixture로 재구성했습니다. 기존 Git 이력과 migration은 가져오지 않았으며, 원본의 전체 기능·운영 환경·과거 테스트 결과를 제공하지 않습니다. 정상 신규 거래는 legacy와 Fill에 함께 기록하지만, 과거 legacy 데이터는 유지하고 조회 시 보완합니다. [SUPPORTING_EVIDENCE](SUPPORTING_EVIDENCE.md)는 공개 범위에서 확인할 수 있는 주장만 코드와 테스트에 연결합니다.

## 한계와 비목표

- 제출과 DB 사이 전체 exactly-once는 보장하지 않습니다. claim 후 submit 전 중단되면 자동 재전송을 포기해 진행이 막힐 수 있습니다.
- `UNCERTAIN`은 확인이 필요하다는 뜻입니다. 새 action으로 바꾸어 보내도 안전하다는 뜻이 아닙니다.
- Fake Exchange 메모리는 같은 JVM에서 서비스 객체를 재생성해도 유지됩니다. JVM 전체 재시작에는 유지되지 않습니다. DB에 저장되지 않은 결과를 JVM 재시작 뒤 재현할 수 없으면 보호 상태로 남습니다.
- 하나의 주문에 대한 full aggregate Fill만 다룹니다. partial fill, 취소, 거절·잔고 검증은 구현 범위 밖입니다.
- Event는 별도 transaction으로 저장합니다. 외부 메시지 전달, 자동 스케줄러와 상태 전체의 원자적 저장을 보장하지 않습니다.
- 호환 이력은 합성 소규모 데이터를 메모리에서 합쳐 정렬합니다. 대량 데이터 pagination과 동시 변경 중 일관된 snapshot은 다루지 않습니다.
- GET은 저장 데이터를 읽기만 합니다. 복구는 명시적 POST로 요청합니다. actor 구분은 합성 데이터 scope이며 인증·인가 기능이 아닙니다.

## 기술 구성

Java 17, Spring Boot 4.0.8, Gradle 8.14.5, MyBatis Starter 4.0.1, Flyway 11.14.1, PostgreSQL 16.15, pgJDBC 42.7.13을 사용합니다. 테스트는 JUnit Jupiter와 Mockito를 사용합니다. 공식 호환 근거와 버전 선택은 [BUILD_DECISIONS](BUILD_DECISIONS.md)에 기록했습니다.

## 라이선스

이 저장소에 새로 작성한 Showcase 코드에는 [MIT](LICENSE)를 적용합니다. 비공개 원본 저장소의 라이선스를 변경하는 의미가 아닙니다. Gradle wrapper와 dependency의 기존 고지는 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)에 정리했습니다.
