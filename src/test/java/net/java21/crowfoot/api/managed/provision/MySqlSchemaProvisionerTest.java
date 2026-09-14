package net.java21.crowfoot.api.managed.provision;

import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.connection.introspect.Introspectors;
import net.java21.crowfoot.api.connection.introspect.MySqlIntrospector;
import net.java21.crowfoot.common.error.BusinessException;
import net.java21.crowfoot.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
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
 * MySQL 프로비저닝 실물 왕복 테스트 — Testcontainers 격리 컨테이너에서
 * database 없는 접속(인스턴스 등록 경로)·스키마(= database)+전용 계정 프로비저닝·
 * 발급 계정 실접속(권한 한정)·회수를 검증한다.
 * docker 데몬이 없으면 스킵(실물 개발 DB는 앱 레벨 수동 최종 확인 대상).
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlSchemaProvisionerTest {

    // 매니지드 인스턴스는 루트 자격이 전제다 — 컨테이너도 root로 검증해야 실물 경로와 일치한다
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withUsername("root")
            .withPassword("root-test-pass");

    private final MySqlSchemaProvisioner provisioner = new MySqlSchemaProvisioner(
            new Introspectors(List.of(new MySqlIntrospector())));

    @Test
    @DisplayName("verify — database 없이 접속해도 SELECT 1이 통과한다(인스턴스 등록 경로)")
    void verifyPassesWithoutDatabase() {
        provisioner.verify(MYSQL.getHost(), port(), null, MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    @DisplayName("test — 성공은 connected:true + 지연 측정, 실패는 예외가 아닌 계약 응답(connected:false + 문구)")
    void testReportsConnectivity() {
        ConnectionTestResponse ok = provisioner.test(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(ok.connected()).isTrue();
        assertThat(ok.latencyMs()).isGreaterThanOrEqualTo(0);

        ConnectionTestResponse fail = provisioner.test(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), "wrong-password");
        assertThat(fail.connected()).isFalse();
        assertThat(fail.message()).isNotBlank();
    }

    @Test
    @DisplayName("provision — database와 전용 계정이 만들어지고 그 계정으로 database 사용이 된다")
    void provisionCreatesDatabaseAndDedicatedAccount() throws SQLException {
        provisioner.provision(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u2_d1", "cf_u2_d1", "Passw0rd!strong");

        assertThat(databaseExists("cf_u2_d1")).isTrue();

        // 발급 계정 실접속 — 루트가 아니라 그 계정 자격으로 자기 database에 테이블을 만든다
        try (Connection connection = connectAs("cf_u2_d1", "Passw0rd!strong");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE issued_member (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO issued_member VALUES (1)");
            try (ResultSet rs = statement.executeQuery("SELECT id FROM issued_member")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("provision — 발급 계정은 자기 database 밖에 객체를 만들 수 없다(권한 한정)")
    void provisionedAccountCannotCreateOutsideDatabase() throws SQLException {
        // 루트가 다른 database를 하나 만들어 두고 발급 계정이 침범 못 하는지 본다
        try (Connection root = connectRoot(null);
             Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE other_db");
        }
        try (Connection root = connectRoot("other_db");
             Statement statement = root.createStatement()) {
            statement.execute("CREATE TABLE sentinel (id INT)");
        }

        provisioner.provision(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u2_d3", "cf_u2_d3", "Passw0rd!strong");

        try (Connection connection = connectAs("cf_u2_d3", "Passw0rd!strong");
             Statement statement = connection.createStatement()) {
            // 다른 database에 USE 자체가 거부된다
            assertThatThrownBy(() -> statement.execute("USE other_db"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("provision — 같은 이름이 이미 있으면 MANAGED_PROVISION_FAILED(이름 충돌)")
    void provisionDuplicateFails() {
        provisioner.provision(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u2_d2", "cf_u2_d2", "Passw0rd!strong");

        assertThatThrownBy(() -> provisioner.provision(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u2_d2", "cf_u2_d2", "Passw0rd!strong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_PROVISION_FAILED);
    }

    @Test
    @DisplayName("withdraw — 발급 계정이 만든 테이블이 있어도 database와 계정이 전부 회수된다")
    void withdrawDropsDatabaseAndAccount() throws SQLException {
        provisioner.provision(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u2_d4", "cf_u2_d4", "Passw0rd!strong");
        try (Connection connection = connectAs("cf_u2_d4", "Passw0rd!strong");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owned (id INT)");
        }

        provisioner.withdraw(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u2_d4", "cf_u2_d4");

        assertThat(databaseExists("cf_u2_d4")).isFalse();
        assertThat(userExists("cf_u2_d4")).isFalse();
        // 회수된 계정 자격으로는 더 접속되지 않는다
        assertThatThrownBy(() -> connectAs("cf_u2_d4", "Passw0rd!strong"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("withdraw — 없는 대상은 이미 정리된 것으로 친다(IF EXISTS, 예외 없음). 계정 null이면 database만")
    void withdrawMissingIsTolerated() {
        assertThatCode(() -> provisioner.withdraw(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "cf_u9_d9", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("발급 이름 방어 — 서버 생성 규칙(소문자·밑줄) 밖 이름은 조립 전에 거부한다")
    void rejectsNonServerNamingName() {
        assertThatThrownBy(() -> provisioner.provision(MYSQL.getHost(), port(), null,
                MYSQL.getUsername(), MYSQL.getPassword(), "BadName", "BadName", "Passw0rd!strong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MANAGED_PROVISION_FAILED);
    }

    private int port() {
        return MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT);
    }

    private Connection connectRoot(String databaseName) throws SQLException {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + port();
        if (databaseName != null) {
            url += "/" + databaseName;
        }
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private Connection connectAs(String username, String password) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + port() + "/" + username,
                username, password);
    }

    private boolean databaseExists(String databaseName) throws SQLException {
        return rootQuery("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?", databaseName);
    }

    private boolean userExists(String username) throws SQLException {
        return rootQuery("SELECT 1 FROM mysql.user WHERE user = ?", username);
    }

    private boolean rootQuery(String sql, String argument) throws SQLException {
        try (Connection connection = connectRoot(null);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, argument);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
