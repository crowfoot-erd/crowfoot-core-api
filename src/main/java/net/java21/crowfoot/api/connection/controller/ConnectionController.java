package net.java21.crowfoot.api.connection.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.auth.CurrentUserHolder;
import net.java21.crowfoot.api.connection.dto.ConnectionResponse;
import net.java21.crowfoot.api.connection.dto.ConnectionSchemaResponse;
import net.java21.crowfoot.api.connection.dto.ConnectionTestResponse;
import net.java21.crowfoot.api.connection.dto.CreateConnectionRequest;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringRequest;
import net.java21.crowfoot.api.connection.dto.ReverseEngineeringResponse;
import net.java21.crowfoot.api.connection.service.ConnectionService;
import net.java21.crowfoot.api.connection.service.ReverseEngineeringService;
import net.java21.crowfoot.api.connection.service.SchemaIntrospectionService;
import net.java21.crowfoot.common.ApiResponse;
import net.java21.crowfoot.common.ListApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * DB 커넥션 API (08-core/06-connection.md) — 구현 경로 /core/** (Gateway URL Rewrite 후).
 * 목록·등록·변경·삭제·접속 테스트·리버스 엔지니어링. 응답에 비밀번호는 담지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;
    private final ReverseEngineeringService reverseEngineeringService;
    private final SchemaIntrospectionService schemaIntrospectionService;

    /** 커넥션 목록 — 멤버 전체, 페이징 메타 없는 목록 */
    @GetMapping("/core/workspaces/{workspace-id}/connections")
    public ListApiResponse<ConnectionResponse> list(
            @PathVariable("workspace-id") long workspaceId) {
        return ListApiResponse.of(connectionService.list(CurrentUserHolder.get().userId(), workspaceId));
    }

    /** 커넥션 등록 — Editor 이상. 등록 시 접속을 검증하지 않는다 */
    @PostMapping("/core/workspaces/{workspace-id}/connections")
    public ResponseEntity<ApiResponse<ConnectionResponse>> create(
            @PathVariable("workspace-id") long workspaceId,
            @Valid @RequestBody CreateConnectionRequest request) {
        ConnectionResponse response = connectionService.create(
                CurrentUserHolder.get().userId(), workspaceId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/connections/" + response.connectionId()))
                .body(ApiResponse.success(response));
    }

    /** 커넥션 변경 — Editor 이상, 변경분만 전송(password는 왔을 때만 재암호화) */
    @PatchMapping("/core/workspaces/{workspace-id}/connections/{connection-id}")
    public ApiResponse<ConnectionResponse> patch(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("connection-id") long connectionId,
            @RequestBody JsonNode body) {
        return ApiResponse.success(
                connectionService.patch(CurrentUserHolder.get().userId(), workspaceId, connectionId, body));
    }

    /** 커넥션 삭제 — Editor 이상, 본문 없음 */
    @DeleteMapping("/core/workspaces/{workspace-id}/connections/{connection-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("connection-id") long connectionId) {
        connectionService.delete(CurrentUserHolder.get().userId(), workspaceId, connectionId);
    }

    /** 접속 테스트 — Editor 이상. 실패도 계약 응답이다(200 + connected:false) */
    @PostMapping("/core/workspaces/{workspace-id}/connections/{connection-id}/test")
    public ApiResponse<ConnectionTestResponse> test(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("connection-id") long connectionId) {
        return ApiResponse.success(
                connectionService.test(CurrentUserHolder.get().userId(), workspaceId, connectionId));
    }

    /** 리버스 엔지니어링 — Editor 이상. 스키마를 읽어 신규 문서를 생성·저장한다 */
    @PostMapping("/core/workspaces/{workspace-id}/connections/{connection-id}/reverse-engineering")
    public ResponseEntity<ApiResponse<ReverseEngineeringResponse>> reverseEngineering(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("connection-id") long connectionId,
            @Valid @RequestBody(required = false) ReverseEngineeringRequest request) {
        ReverseEngineeringResponse response = reverseEngineeringService.reverse(
                CurrentUserHolder.get().userId(), workspaceId, connectionId,
                request == null ? new ReverseEngineeringRequest(null, null) : request);
        return ResponseEntity
                .created(URI.create("/api/v1/core/workspaces/" + workspaceId
                        + "/models/" + response.model().modelId()))
                .body(ApiResponse.success(response));
    }

    /** 스키마 조회 — Editor 이상. 동기화 원천 content만 반환한다(문서 생성 없음 — 06-connection.md Section 3.7) */
    @PostMapping("/core/workspaces/{workspace-id}/connections/{connection-id}/schema")
    public ApiResponse<ConnectionSchemaResponse> schema(
            @PathVariable("workspace-id") long workspaceId,
            @PathVariable("connection-id") long connectionId) {
        return ApiResponse.success(
                schemaIntrospectionService.introspect(
                        CurrentUserHolder.get().userId(), workspaceId, connectionId));
    }
}
