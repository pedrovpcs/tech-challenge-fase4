package br.com.fiap.feedback.infrastructure;

import br.com.fiap.feedback.domain.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public final class DynamoRepository implements Ports.Repository {
    private final DynamoDbClient client;
    private final String table;
    public DynamoRepository(DynamoDbClient client, String table) { this.client = client; this.table = table; }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static String week(Instant date) {
        return date.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }
    private static String timestamp(Instant value) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }
    public void save(Evaluation e) {
        var item = new HashMap<String, AttributeValue>();
        item.put("id", s(e.id())); item.put("descricao", s(e.descricao()));
        item.put("nota", AttributeValue.builder().n(Integer.toString(e.nota())).build());
        item.put("urgencia", s(e.urgencia().name())); item.put("createdAt", s(timestamp(e.createdAt())));
        item.put("week", s(week(e.createdAt()))); item.put("sort", s(timestamp(e.createdAt()) + "#" + e.id()));
        client.putItem(PutItemRequest.builder().tableName(table).item(item).conditionExpression("attribute_not_exists(id)").build());
    }
    public List<Evaluation> between(Instant start, Instant end) {
        var result = new ArrayList<Evaluation>();
        for (var day = start.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
             day.atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(end); day = day.plusWeeks(1)) {
            Map<String, AttributeValue> cursor = null;
            do {
                var response = client.query(QueryRequest.builder().tableName(table).indexName("ByWeek")
                    .keyConditionExpression("#w = :w AND #s BETWEEN :start AND :end")
                    .expressionAttributeNames(Map.of("#w", "week", "#s", "sort"))
                    .expressionAttributeValues(Map.of(":w", s(day.toString()), ":start", s(timestamp(start)), ":end", s(timestamp(end))))
                    .exclusiveStartKey(cursor).build());
                for (var item : response.items()) {
                    var e = decode(item);
                    if (!e.createdAt().isBefore(start) && e.createdAt().isBefore(end)) result.add(e);
                }
                cursor = response.lastEvaluatedKey();
            } while (cursor != null && !cursor.isEmpty());
        }
        result.sort(Comparator.comparing(Evaluation::createdAt).thenComparing(Evaluation::id));
        return result;
    }
    public static Evaluation decode(Map<String, AttributeValue> item) {
        return new Evaluation(item.get("id").s(), item.get("descricao").s(), Integer.parseInt(item.get("nota").n()),
            Evaluation.Urgency.valueOf(item.get("urgencia").s()), Instant.parse(item.get("createdAt").s()));
    }
    public boolean notified(String id) {
        var item = client.getItem(GetItemRequest.builder().tableName(table).key(Map.of("id", s(id))).consistentRead(true).build()).item();
        return item.containsKey("notificationSentAt");
    }
    public void markNotified(String id, Instant sentAt) {
        client.updateItem(UpdateItemRequest.builder().tableName(table).key(Map.of("id", s(id)))
            .conditionExpression("attribute_exists(id)").updateExpression("SET notificationSentAt = :sent")
            .expressionAttributeValues(Map.of(":sent", s(sentAt.toString()))).build());
    }
}
