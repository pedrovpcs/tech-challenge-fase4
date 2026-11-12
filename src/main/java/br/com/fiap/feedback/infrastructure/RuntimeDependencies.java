package br.com.fiap.feedback.infrastructure;

import br.com.fiap.feedback.domain.Ports;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.nio.charset.StandardCharsets;

public final class RuntimeDependencies {
    private RuntimeDependencies() { }
    public static Ports.Repository repository() {
        return new DynamoRepository(DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build(), System.getenv("TABLE_NAME"));
    }
    public static Ports.Messenger messenger() {
        var client = SnsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        return (subject, body) -> {
            if (body.getBytes(StandardCharsets.UTF_8).length > 240_000) throw new IllegalArgumentException("Mensagem excede limite seguro");
            client.publish(r -> r.topicArn(System.getenv("TOPIC_ARN")).subject(subject).message(body));
        };
    }
}
