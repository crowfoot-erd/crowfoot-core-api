package net.java21.crowfoot.api.managed.provision;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 프로비저너 레지스트리 — 인스턴스 dbms_type → 전략. Introspectors와 같은 관례로
 * 스프링이 주입한 구현체로 초기화되므로 신규 DBMS 방식은 구현체 하나 추가로 자동 등록된다.
 */
@Component
public class ManagedProvisioners {

    private final Map<String, ManagedProvisioner> byDbmsType;

    public ManagedProvisioners(List<ManagedProvisioner> provisioners) {
        this.byDbmsType = provisioners.stream()
                .collect(Collectors.toUnmodifiableMap(ManagedProvisioner::dbmsType, Function.identity()));
    }

    /** 등록된 전략이 없으면 null — 호출부가 INVALID_REQUEST로 판정한다 */
    public ManagedProvisioner forDbmsType(String dbmsType) {
        return byDbmsType.get(dbmsType == null ? "" : dbmsType.trim().toLowerCase());
    }
}
