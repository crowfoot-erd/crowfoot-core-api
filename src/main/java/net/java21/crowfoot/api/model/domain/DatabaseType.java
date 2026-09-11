package net.java21.crowfoot.api.model.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ERD 문서 데이터베이스 종류 코드 (06-erd/00-domain.md 코드 테이블) — providers·roles와 같은 코드 테이블 패턴.
 * 종류 추가(oracle 등)는 시드 행 추가만으로 확장된다(활성 토글로 노출 제어).
 */
@Entity
@Table(name = "database_types", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class DatabaseType {

    @Id
    private String code;

    private String displayName;

    private boolean isActive;

    public DatabaseType(String code, String displayName, boolean isActive) {
        this.code = code;
        this.displayName = displayName;
        this.isActive = isActive;
    }
}
