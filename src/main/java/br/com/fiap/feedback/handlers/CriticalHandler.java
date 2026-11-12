package br.com.fiap.feedback.handlers;

import br.com.fiap.feedback.domain.*;
import br.com.fiap.feedback.infrastructure.RuntimeDependencies;
import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.*;
import java.time.*;
import java.util.*;

public final class CriticalHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {
    private final Ports.Repository repository;
    private final Ports.Messenger messenger;
    private final Clock clock;
    public CriticalHandler() { this(RuntimeDependencies.repository(), RuntimeDependencies.messenger(), Clock.systemUTC()); }
    public CriticalHandler(Ports.Repository repository, Ports.Messenger messenger, Clock clock) {
        this.repository = repository; this.messenger = messenger; this.clock = clock;
    }
    public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
        var failures = new ArrayList<StreamsEventResponse.BatchItemFailure>();
        for (var record : event.getRecords()) {
            if (!"INSERT".equals(record.getEventName())) continue;
            try {
                var image = record.getDynamodb().getNewImage();
                if (!"CRITICA".equals(image.get("urgencia").getS())) continue;
                String id = image.get("id").getS();
                if (repository.notified(id)) continue;
                Instant sentAt = clock.instant();
                messenger.send("Avaliação crítica", "ID: " + id + "\nDescrição: " + image.get("descricao").getS()
                    + "\nUrgência: CRITICA\nData da avaliação: " + image.get("createdAt").getS() + "\nData de envio: " + sentAt);
                repository.markNotified(id, sentAt);
            } catch (RuntimeException e) {
                failures.add(new StreamsEventResponse.BatchItemFailure(record.getDynamodb().getSequenceNumber()));
                if (context != null) context.getLogger().log("notification_failure requestId=" + context.getAwsRequestId());
            }
        }
        return new StreamsEventResponse(failures);
    }
}
