package net.java21.crowfoot.api.showcase.client;

import net.java21.crowfoot.api.config.CaptureProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;

/**
 * 캡처 서비스 RestClient 구현 — connect 2s / read 40s, 재시도 없음(08-core/19-site-showcase.md Section 4).
 * 캡처 서비스의 최악 응답은 자리 대기 10초 + 캡처 20초 + 정리 5초 ≈ 35초다(11-capture Section 2.2).
 */
@Component
public class RestClientCaptureClient implements CaptureClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestClientCaptureClient(CaptureProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(40_000);
        this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public CaptureResult capture(String url) {
        JsonNode body;
        try {
            body = restClient.post()
                    .uri("/internal/capture/sites")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", url))
                    .exchange((request, response) -> objectMapper.readTree(response.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new CaptureException(CaptureException.UNAVAILABLE, "캡처 서비스에 연결하지 못했습니다");
        } catch (RuntimeException e) {
            throw new CaptureException(CaptureException.UNAVAILABLE, "캡처 서비스 응답을 읽지 못했습니다");
        }
        JsonNode header = body.path("header");
        if (!header.path("isSuccessful").asBoolean(false)) {
            String code = header.path("resultCode").asString("CAPTURE_FAILED");
            throw new CaptureException(code, header.path("resultMessage").asString("사이트를 찍지 못했습니다"));
        }
        JsonNode result = body.path("response");
        String image = text(result, "image");
        return new CaptureResult(
                text(result, "finalUrl"),
                text(result, "title"),
                text(result, "description"),
                text(result, "siteName"),
                text(result, "faviconUrl"),
                text(result, "imageType"),
                image == null ? null : Base64.getDecoder().decode(image));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
