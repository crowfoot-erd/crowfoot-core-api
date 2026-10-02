package net.java21.crowfoot.api.connection.service;

import net.java21.crowfoot.api.connection.domain.DbConnection;
import net.java21.crowfoot.api.managed.repository.ManagedDatabaseRepository;
import net.java21.crowfoot.api.managed.repository.ManagedInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버가 커넥션에 접속할 때 쓰는 주소 (08-core/07-managed-database.md Section 3.9).
 *
 * <p>매니지드 데이터베이스의 커넥션에는 사용자에게 보여 주는 노출 주소가 적혀 있다. 운영 클러스터에서는 그 주소로
 * 가는 길이 없으므로, <b>운영에서만</b> 서버가 인스턴스의 내부 주소로 접속한다(운영 프로필이 {@code internal}로 켠다).
 * 그 밖의 환경과 사용자가 직접 등록한 커넥션은 적힌 주소를 그대로 쓴다.
 * 화면과 API 응답에 보이는 주소는 바꾸지 않는다 — 이 클래스는 접속하는 자리에서만 쓴다.</p>
 */
@Component
public class ConnectionEndpointResolver {

    /** 접속 주소 */
    public record Endpoint(String host, int port) {
    }

    private final ManagedDatabaseRepository managedDatabaseRepository;
    private final ManagedInstanceRepository managedInstanceRepository;
    private final boolean internal;

    /**
     * @param serverAddress {@code public}(기본)이면 커넥션에 적힌 주소를 쓴다.
     *                      {@code internal}이면 매니지드 커넥션에 인스턴스의 내부 주소를 쓴다 — 운영 프로필만 켠다
     */
    public ConnectionEndpointResolver(ManagedDatabaseRepository managedDatabaseRepository,
                                      ManagedInstanceRepository managedInstanceRepository,
                                      @Value("${crowfoot.managed.server-address:public}") String serverAddress) {
        this.managedDatabaseRepository = managedDatabaseRepository;
        this.managedInstanceRepository = managedInstanceRepository;
        this.internal = "internal".equalsIgnoreCase(serverAddress);
    }

    /** 커넥션에 적힌 주소를 그대로 쓰는 해석기 — 테스트와, 매니지드 조회가 필요 없는 자리에서 쓴다 */
    public static ConnectionEndpointResolver asWritten() {
        return new ConnectionEndpointResolver(null, null, "public");
    }

    public Endpoint resolve(DbConnection connection) {
        Endpoint written = new Endpoint(connection.getHost(), connection.getPort());
        if (!internal || connection.getId() == null) {
            return written;
        }
        return managedDatabaseRepository.findByConnectionId(connection.getId())
                .flatMap(database -> managedInstanceRepository.findById(database.getInstanceId()))
                .map(instance -> new Endpoint(instance.getHost(), instance.getPort()))
                .orElse(written);
    }
}
