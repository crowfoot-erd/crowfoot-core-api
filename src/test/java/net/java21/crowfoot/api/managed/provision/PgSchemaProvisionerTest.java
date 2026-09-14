package net.java21.crowfoot.api.managed.provision;

import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.PostgresIntrospector;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL 프로비저닝 실물 왕복 테스트 — Testcontainers 격리 컨테이너에서
 * verify(SELECT 1)·스키마+전용 계정 프로비저닝·발급 계정 실접속(권한 한정)·회수를 검증한다.
 * docker 데몬이 없으면 스킵(실물 개발 DB는 앱 레벨 수동 최종 확인 대상).
 */
@Testcontainers(disabledWithoutDocker = true)
class PgSchemaProvisionerTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private final PgSchemaProvisioner provisioner = new PgSchemaProvisioner(
            new Introspectors(List.of(new PostgresIntrospector())));

    @Test
    @DisplayName("verify — 제출된 자격으로 SELECT 1이 통과한다")
    void verifyPasses() {
        provisioner.verify(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("verify — database 생략 접속은 pgjdbc 표준 폴백(username database)으로 통과한다")
    void verifyFallsBackToUsernameDatabase() {
        provisioner.verify(POSTGRES.getHost(), port(), null,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("verify — 잘못된 자격은 MANAGED_INSTANCE_UNREACHABLE")
    void verifyRejectsWrongCredential() {
        assertThatThrownBy(() -> provisioner.verify(POSTGRES.getHost(), port(),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), "wrong-password"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_INSTANCE_UNREACHABLE);
    }

    @Test
    @DisplayName("test — 성공은 connected:true + 지연 측정, 실패는 예외가 아닌 계약 응답(connected:false + 문구)")
    void testReportsConnectivity() {
        ConnectionTestResponse ok = provisioner.test(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThat(ok.connected()).isTrue();
        assertThat(ok.latencyMs()).isGreaterThanOrEqualTo(0);

        ConnectionTestResponse fail = provisioner.test(POSTGRES.getHost(), port(),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), "wrong-password");
        assertThat(fail.connected()).isFalse();
        assertThat(fail.message()).isNotBlank();
    }

    @Test
    @DisplayName("provision — 스키마와 LOGIN 역할이 만들어지고 발급 계정으로 접속·스키마 사용이 된다")
    void provisionCreatesSchemaAndDedicatedAccount() throws SQLException {
        provisioner.provision(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "cf_u2_d1", "cf_u2_d1", "Passw0rd!strong");

        assertThat(schemaExists("cf_u2_d1")).isTrue();
        assertThat(roleExists("cf_u2_d1")).isTrue();

        // 발급 계정 실접속 — 인스턴스 루트가 아니라 그 계정 자격으로 스키마에 테이블을 만든다
        try (Connection connection = connectAs("cf_u2_d1", "Passw0rd!strong");
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO cf_u2_d1");
            statement.execute("CREATE TABLE issued_member (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO issued_member VALUES (1)");
            try (ResultSet rs = statement.executeQuery("SELECT id FROM issued_member")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("provision — 발급 계정은 자기 스키마 밖에 객체를 만들 수 없다(스키마 한정 권한)")
    void provisionedAccountCannotCreateOutsideSchema() throws SQLException {
        provisioner.provision(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "cf_u2_d3", "cf_u2_d3", "Passw0rd!strong");

        // public 스키마 생성은 PG 15+ 기본 거부 + 발급 계정엔 다른 스키마 CREATE 권한이 없다
        try (Connection connection = connectAs("cf_u2_d3", "Passw0rd!strong");
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE public.leak (id INT)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("provision — 같은 이름이 이미 있으면 MANAGED_PROVISION_FAILED(이름 충돌)")
    void provisionDuplicateFails() {
        provisioner.provision(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "cf_u2_d2", "cf_u2_d2", "Passw0rd!strong");

        assertThatThrownBy(() -> provisioner.provision(POSTGRES.getHost(), port(),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                "cf_u2_d2", "cf_u2_d2", "Passw0rd!strong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_PROVISION_FAILED);
    }

    @Test
    @DisplayName("withdraw — 발급 계정이 만든 테이블(자기 소유)이 있어도 스키마 CASCADE → 역할까지 전부 회수된다")
    void withdrawDropsSchemaAndAccountEvenWithOwnedObjects() throws SQLException {
        provisioner.provision(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "cf_u2_d4", "cf_u2_d4", "Passw0rd!strong");
        try (Connection connection = connectAs("cf_u2_d4", "Passw0rd!strong");
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO cf_u2_d4");
            statement.execute("CREATE TABLE owned (id INT)");
        }

        // 소유 객체가 남으면 DROP ROLE이 거부된다 — 스키마 선행 CASCADE로 정리되는지 실증
        provisioner.withdraw(POSTGRES.getHost(), port(), POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), "cf_u2_d4", "cf_u2_d4");

        assertThat(schemaExists("cf_u2_d4")).isFalse();
        assertThat(roleExists("cf_u2_d4")).isFalse();
        // 회수된 계정 자격으로는 더 접속되지 않는다
        assertThatThrownBy(() -> connectAs("cf_u2_d4", "Passw0rd!strong"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("withdraw — 없는 대상은 이미 정리된 것으로 친다(IF EXISTS, 예외 없음). 계정 null이면 스키마만")
    void withdrawMissingIsTolerated() {
        assertThatCode(() -> provisioner.withdraw(POSTGRES.getHost(), port(),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                "cf_u9_d9", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("발급 이름 방어 — 서버 생성 규칙(소문자·밑줄) 밖 이름은 조립 전에 거부한다")
    void rejectsNonServerNamingName() {
        assertThatThrownBy(() -> provisioner.provision(POSTGRES.getHost(), port(),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                "BadName", "BadName", "Passw0rd!strong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_PROVISION_FAILED);
    }

    @Test
    @DisplayName("비밀번호 생성 — 20자·대소문자/숫자/특수문자 각 1개 이상, SQL 리터럴 위험 문자 없음")
    void generatedPasswordIsStrongAndLiteralSafe() {
        for (int i = 0; i < 50; i++) {
            String password = IssuedPasswords.generate();
            assertThat(password).hasSize(20);
            assertThat(password).matches(".*[A-Z].*");
            assertThat(password).matches(".*[a-z].*");
            assertThat(password).matches(".*[0-9].*");
            assertThat(password).matches(".*[!@#$%^&*\\-_=+].*");
            assertThat(password).doesNotContain("'");
            assertThat(password).doesNotContain("\\");
        }
    }

    private int port() {
        return POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    private Connection connectAs(String username, String password) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + port() + "/" + POSTGRES.getDatabaseName(),
                username, password);
    }

    private boolean schemaExists(String schemaName) throws SQLException {
        return exists("SELECT 1 FROM pg_namespace WHERE nspname = ?", schemaName);
    }

    private boolean roleExists(String roleName) throws SQLException {
        return exists("SELECT 1 FROM pg_roles WHERE rolname = ?", roleName);
    }

    private boolean exists(String sql, String argument) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + port() + "/" + POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, argument);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
