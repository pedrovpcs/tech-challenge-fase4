package br.com.fiap.feedback.handlers;

import br.com.fiap.feedback.domain.*;
import br.com.fiap.feedback.infrastructure.RuntimeDependencies;
import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;

public final class ReceiveHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Ports.Repository repository;
    private final Clock clock;
    public ReceiveHandler() { this(RuntimeDependencies.repository(), Clock.systemUTC()); }
    public ReceiveHandler(Ports.Repository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            if (event == null || event.getBody() == null) return response(400, "{\"erro\":\"Corpo obrigatório\"}");
            String body = Boolean.TRUE.equals(event.getIsBase64Encoded())
                ? new String(Base64.getDecoder().decode(event.getBody()), java.nio.charset.StandardCharsets.UTF_8) : event.getBody();
            if (body.length() > 16000) return response(413, "{\"erro\":\"Corpo muito grande\"}");
            JsonNode input = JSON.readTree(body);
            if (input == null || !input.isObject() || !input.hasNonNull("descricao") || !input.get("descricao").isTextual()
                || input.get("descricao").asText().isBlank() || input.get("descricao").asText().length() > 2000
                || !input.hasNonNull("nota") || !input.get("nota").isIntegralNumber() || !input.get("nota").canConvertToInt()
                || input.get("nota").intValue() < 0 || input.get("nota").intValue() > 10) {
                return response(400, "{\"erro\":\"Informe descricao de 1 a 2000 caracteres e nota inteira de 0 a 10\"}");
            }
            int score = input.get("nota").intValue();
            var evaluation = new Evaluation(UUID.randomUUID().toString(), input.get("descricao").asText().strip(), score,
                Evaluation.classify(score), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            repository.save(evaluation);
            return response(201, JSON.writeValueAsString(Map.of("id", evaluation.id(), "urgencia", evaluation.urgencia().name(), "createdAt", evaluation.createdAt().toString())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return response(400, "{\"erro\":\"JSON inválido\"}");
        } catch (RuntimeException e) {
            if (context != null) context.getLogger().log("persist_failure requestId=" + context.getAwsRequestId());
            return response(503, "{\"erro\":\"Persistência indisponível\"}");
        }
    }
    private static APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status).withHeaders(Map.of("Content-Type", "application/json; charset=utf-8", "Cache-Control", "no-store")).withBody(body);
    }
}
