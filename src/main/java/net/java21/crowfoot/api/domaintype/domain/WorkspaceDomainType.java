package net.java21.crowfoot.api.domaintype.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 워크스페이스 도메인 타입 (08-core/16-domain-type.md Section 2) — 여러 컬럼이 함께 쓰는 타입 정의.
 * 워크스페이스에 하나를 정의하면 그 워크스페이스의 모든 ERD 문서가 쓴다.
 * version은 문서가 "마지막으로 맞춘 뒤에 바뀌었는가"를 아는 기준이다 — 고칠 때마다 1씩 오른다.
 */
@Entity
@Table(name = "workspace_domain_types", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceDomainType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "domain_type_id")
    private Long id;

    private Long workspaceId;

    /** 이름 — 워크스페이스 안에서 대소문자를 무시하고 유일하다 */
    private String name;

    /** 공용 논리 타입 코드(VARCHAR, DECIMAL 등) — 문서의 컬럼과 같은 코드 체계 */
    private String dataType;

    @Column(name = "type_length")
    private Integer length;

    @Column(name = "type_precision")
    private Integer precision;

    @Column(name = "type_scale")
    private Integer scale;

    /** NULL 허용 기본값 */
    private boolean nullable;

    private String defaultValue;

    private String description;

    /** 1에서 시작한다. 고칠 때마다 1씩 오른다 */
    private int version;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public WorkspaceDomainType(Long workspaceId, String name, Long createdBy) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.createdBy = createdBy;
        this.version = 1;
    }
}
