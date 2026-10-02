package net.java21.crowfoot.api.connection.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.java21.crowfoot.api.managed.domain.ManagedInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인스턴스 접속 주소 — 운영은 내부 주소, 그 밖의 환경은 노출 주소 (08-core/07-managed-database.md Section 3.9)
 */
class ConnectionEndpointResolverTest {

    private static ManagedInstance instance(String publicHost) {
        return new ManagedInstance("MySQL", "mysql", "10.116.64.14", publicHost, 13306, null, "root", new byte[0], true, 1L);
    }

    @Test
    @DisplayName("운영(internal)은 발급·철회·접속 테스트에 내부 주소를 쓴다")
    void internalUsesInternalHost() {
        ConnectionEndpointResolver resolver = new ConnectionEndpointResolver(null, null, "internal");
        assertThat(resolver.instanceHost(instance("s4.java21.net"))).isEqualTo("10.116.64.14");
    }

    @Test
    @DisplayName("로컬(public)은 노출 주소를 쓴다 — 노출 주소가 없으면 내부 주소")
    void publicUsesPublicHost() {
        ConnectionEndpointResolver resolver = ConnectionEndpointResolver.asWritten();
        assertThat(resolver.instanceHost(instance("s4.java21.net"))).isEqualTo("s4.java21.net");
        assertThat(resolver.instanceHost(instance(null))).isEqualTo("10.116.64.14");
        assertThat(resolver.instanceHost(instance(" "))).isEqualTo("10.116.64.14");
    }
}
