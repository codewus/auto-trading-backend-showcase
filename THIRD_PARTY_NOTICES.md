# Third-Party Notices

새 Showcase 코드의 MIT는 아래 제3자 구성요소의 라이선스를 변경하지 않습니다. dependency는 Gradle이 공식 artifact를 내려받으며 해당 JAR의 LICENSE/NOTICE를 수정하거나 제거하지 않습니다. frontend vendor와 이미지는 포함하지 않습니다.

| 직접 사용한 구성요소 | 버전 | 라이선스 / 원문 |
| --- | --- | --- |
| Gradle wrapper | 8.14.5 | Apache-2.0. 표준 `gradlew`, `gradlew.bat`, wrapper JAR의 upstream 저작권 고지를 유지. [Apache 원문](gradle/WRAPPER_LICENSE.txt), [Gradle license](https://github.com/gradle/gradle/blob/v8.14.5/LICENSE) |
| Spring Boot Web MVC / validation / Flyway / test starters | 4.0.8 | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/v4.0.8/LICENSE.txt) |
| MyBatis Spring Boot Starter | 4.0.1 | [Apache-2.0](https://github.com/mybatis/spring-boot-starter/blob/mybatis-spring-boot-4.0.1/LICENSE) |
| MyBatis / MyBatis Spring | 3.5.19 / 4.0.0 | [Apache-2.0](https://github.com/mybatis/mybatis-3/blob/mybatis-3.5.19/LICENSE), [MyBatis Spring license](https://github.com/mybatis/spring/blob/mybatis-spring-4.0.0/LICENSE) |
| Flyway core / PostgreSQL support | 11.14.1 | [Apache-2.0](https://github.com/flyway/flyway/blob/flyway-11.14.1/LICENSE.txt), Community OSS artifact 사용 |
| PostgreSQL JDBC driver | 42.7.13 | [BSD-2-Clause](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.13/LICENSE) |
| PostgreSQL local container | 16.15 | [PostgreSQL License](https://www.postgresql.org/about/licence/). 서버/image를 이 저장소에 재배포하지 않음 |
| JUnit Jupiter / Platform | 6.0.3 / 6.0.3 | [EPL-2.0](https://github.com/junit-team/junit-framework/blob/r6.0.3/LICENSE.md) |
| Mockito | 5.20.0 | [MIT](https://github.com/mockito/mockito/blob/v5.20.0/LICENSE) |
| AssertJ | 3.27.7 | [Apache-2.0](https://github.com/assertj/assertj/blob/assertj-build-3.27.7/LICENSE.txt) |

Spring Framework, embedded Tomcat, Jackson, Hibernate Validator, HikariCP, Byte Buddy 등은 위 starter가 가져오는 전이 dependency입니다. 이들의 배포 artifact에 포함된 고지를 유지하며 별도 vendor 소스는 넣지 않습니다. [BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.8/spring-boot-dependencies-4.0.8.pom)과 각 artifact의 POM/JAR에 버전·고지가 있습니다.

```bash
./gradlew dependencies --configuration runtimeClasspath
./gradlew dependencies --configuration testRuntimeClasspath
```

MIT는 비공개 원본 저장소에 적용되지 않습니다. 로컬 JDK와 Docker 엔진은 별도 설치 환경이며 프로젝트에 포함하거나 재라이선스하지 않습니다.

CI는 [actions/checkout v7.0.1](https://github.com/actions/checkout/blob/v7.0.1/LICENSE)과 [actions/setup-java v6.0.1](https://github.com/actions/setup-java/blob/v6.0.1/LICENSE)의 MIT 라이선스를 따릅니다. Action 소스를 이 저장소로 복사하지 않습니다.
