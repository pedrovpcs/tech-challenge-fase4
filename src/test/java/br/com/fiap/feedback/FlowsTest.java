package br.com.fiap.feedback;

import br.com.fiap.feedback.domain.*;
import br.com.fiap.feedback.handlers.*;
import br.com.fiap.feedback.infrastructure.DynamoRepository;
import com.amazonaws.services.lambda.runtime.events.*;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlowsTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    final Ports.Repository repo = mock(Ports.Repository.class);
    final Ports.Messenger messenger = mock(Ports.Messenger.class);
    @ParameterizedTest @ValueSource(strings={"-1","11","3.0","3.5","null","\"3\"","true","9999999999999999999999"})
    void rejectsInvalidScore(String score) {
        var response = new ReceiveHandler(repo, clock).handleRequest(new APIGatewayProxyRequestEvent().withBody("{\"descricao\":\"Aula\",\"nota\":"+score+"}"), null);
        assertEquals(400, response.getStatusCode()); verifyNoInteractions(repo);
    }
    @ParameterizedTest @ValueSource(ints={0,3,4,6,7,10})
    void persistsValidScores(int score) {
        var response = new ReceiveHandler(repo, clock).handleRequest(new APIGatewayProxyRequestEvent().withBody("{\"descricao\":\"Aula\",\"nota\":"+score+"}"), null);
        assertEquals(201, response.getStatusCode());
        verify(repo).save(argThat(e -> e.nota()==score && e.createdAt().equals(clock.instant()) && e.urgencia()==Evaluation.classify(score)));
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{","{\"descricao\":\" \",\"nota\":5}","{\"descricao\":7,\"nota\":5}","{\"descricao\":\"ok\",\"nota\":5} {}"})
    void rejectsMalformedInput(String body) {
        assertEquals(400,new ReceiveHandler(repo,clock).handleRequest(new APIGatewayProxyRequestEvent().withBody(body),null).getStatusCode());
        verifyNoInteractions(repo);
    }
    @Test void persistenceFailureIs503() {
        doThrow(new RuntimeException()).when(repo).save(any());
        assertEquals(503,new ReceiveHandler(repo,clock).handleRequest(new APIGatewayProxyRequestEvent().withBody("{\"descricao\":\"ok\",\"nota\":5}"),null).getStatusCode());
    }
    private DynamodbEvent stream(String urgency, String operation) {
        var record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(operation);
        record.setDynamodb(new com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord().withSequenceNumber("123").withNewImage(Map.of(
            "id",new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue().withS("id1"),
            "descricao",new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue().withS("Problema"),
            "createdAt",new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue().withS("2026-11-08T12:00:00Z"),
            "urgencia",new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue().withS(urgency))));
        var event = new DynamodbEvent(); event.setRecords(List.of(record)); return event;
    }
    @Test void criticalPublishAndRedelivery() {
        when(repo.notified("id1")).thenReturn(false,true);
        var handler = new CriticalHandler(repo,messenger,clock);
        assertTrue(handler.handleRequest(stream("CRITICA","INSERT"),null).getBatchItemFailures().isEmpty());
        handler.handleRequest(stream("CRITICA","INSERT"),null);
        verify(messenger).send(eq("Avaliação crítica"),argThat(s -> s.contains("Problema") && s.contains("Data de envio: 2026-11-09T12:00:00Z")));
        verify(repo).markNotified("id1",clock.instant());
    }
    @Test void streamIgnoresNoncriticalAndUpdates() {
        var handler = new CriticalHandler(repo,messenger,clock);
        handler.handleRequest(stream("BAIXA","INSERT"),null); handler.handleRequest(stream("CRITICA","MODIFY"),null);
        verifyNoInteractions(repo,messenger);
    }
    @Test void publishingFailureRetriesWithoutMarking() {
        doThrow(new RuntimeException()).when(messenger).send(anyString(),anyString());
        var result = new CriticalHandler(repo,messenger,clock).handleRequest(stream("CRITICA","INSERT"),null);
        assertEquals("123",result.getBatchItemFailures().getFirst().getItemIdentifier());
        verify(repo,never()).markNotified(anyString(),any());
    }
    @Test void weeklyAggregationAndZoneBoundaries() {
        var start=Instant.parse("2026-11-02T03:00:00Z"); var end=Instant.parse("2026-11-09T03:00:00Z");
        when(repo.between(start,end)).thenReturn(List.of(new Evaluation("a","Ruim",2,Evaluation.Urgency.CRITICA,start), new Evaluation("b","Boa",8,Evaluation.Urgency.BAIXA,end.minusSeconds(1))));
        new WeeklyHandler(repo,messenger,clock,ZoneId.of("America/Sao_Paulo")).handleRequest(null,null);
        verify(repo).between(start,end);
        verify(messenger).send(anyString(),argThat(s -> s.contains("Média: 5.00") && s.contains("2026-11-02=1") && s.contains("2026-11-08=1") && s.contains("MEDIA=0") && s.contains("CRITICA=1") && s.contains("Descrição: Ruim")));
    }
    @Test void emptyWeekAndScheduleReference() {
        when(repo.between(any(),any())).thenReturn(List.of());
        var event=new ScheduledEvent().withTime(org.joda.time.DateTime.parse("2026-11-02T12:00:00Z"));
        new WeeklyHandler(repo,messenger,clock,ZoneId.of("America/Sao_Paulo")).handleRequest(event,null);
        verify(repo).between(Instant.parse("2026-10-26T03:00:00Z"),Instant.parse("2026-11-02T03:00:00Z"));
        verify(messenger).send(anyString(),argThat(s->s.contains("Total: 0")&&s.contains("Média: 0.00")));
    }
    @Test void reportFailurePropagatesForRetry() {
        when(repo.between(any(),any())).thenReturn(List.of()); doThrow(new RuntimeException()).when(messenger).send(anyString(),anyString());
        assertThrows(RuntimeException.class,()->new WeeklyHandler(repo,messenger,clock,ZoneOffset.UTC).handleRequest(null,null));
    }
    @Test void dynamoQueryPaginatesAcrossUtcWeeks() {
        var db=mock(DynamoDbClient.class);
        var cursor=Map.of("id",software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("cursor").build());
        when(db.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).lastEvaluatedKey(cursor).build(),QueryResponse.builder().items(List.of()).build());
        var result=new DynamoRepository(db,"table").between(Instant.parse("2026-11-02T03:00:00Z"),Instant.parse("2026-11-09T03:00:00Z"));
        assertTrue(result.isEmpty()); verify(db,times(3)).query(any(QueryRequest.class));
    }
}
