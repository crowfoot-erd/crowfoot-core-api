package net.java21.crowfoot.api.connection.introspect;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * JDBC 예외 분류 (08-core/06-connection.md Section 3.5) — 접속 테스트·리버스가
 * 사용자에게 보여 줄 진단 문구를 고른다. 예외 원문 노출 금지(분류 문구만).
 */
public final class JdbcDiagnostics {

    private JdbcDiagnostics() {
    }

    /**
     * 문장 실행 실패 진단 (배포 1.8) — 접속 계열(08·28·timeout)은 접속 분류 문구를
     * 쓰고, 그 외(이미 존재하는 객체·구문 오류 등)는 서버 오류 문구를 그대로 보여 준다.
     * 문장 오류 메시지에 자격 증명이 포함되지 않기 때문에 원문이 더 실용적이다.
     */
    public static String diagnoseStatement(SQLException e) {
        String state = e.getSQLState();
        boolean connectionRelated = e instanceof SQLTimeoutException
                || (state != null && (state.startsWith("08") || state.startsWith("28")));
        if (!connectionRelated) {
            String message = e.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return diagnose(e);
    }

    public static String diagnose(SQLException e) {
        String state = e.getSQLState();
        if (e instanceof SQLTimeoutException) {
            return "연결 시간이 초과되었습니다 — 대상 서버가 느리거나 방화벽으로 차단되었을 수 있습니다";
        }
        if (state != null && state.startsWith("28")) {
            // 28000/28P01 등 — 인증 실패 계열
            return "인증에 실패했습니다 — 사용자 이름·비밀번호를 확인하세요";
        }
        if (state != null && state.startsWith("08")) {
            // 08001 접속 실패·08006 연결 끊김 등
            return "데이터베이스에 접속할 수 없습니다 — 호스트·포트·대상 이름을 확인하세요";
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return "연결 시간이 초과되었습니다 — 대상 서버가 느리거나 방화벽으로 차단되었을 수 있습니다";
        }
        if (message.contains("unknown database") || message.contains("does not exist")
                || message.contains("접속을 거부") || message.contains("connection refused")) {
            return "데이터베이스에 접속할 수 없습니다 — 호스트·포트·대상 이름을 확인하세요";
        }
        return "데이터베이스에 연결하지 못했습니다 — 접속 정보를 확인하세요";
    }
}
