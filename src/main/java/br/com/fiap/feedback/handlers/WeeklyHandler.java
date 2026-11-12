package br.com.fiap.feedback.handlers;

import br.com.fiap.feedback.domain.*;
import br.com.fiap.feedback.infrastructure.RuntimeDependencies;
import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class WeeklyHandler implements RequestHandler<ScheduledEvent, String> {
    private final Ports.Repository repository;
    private final Ports.Messenger messenger;
    private final Clock clock;
    private final ZoneId zone;
    public WeeklyHandler() { this(RuntimeDependencies.repository(), RuntimeDependencies.messenger(), Clock.systemUTC(), ZoneId.of(System.getenv().getOrDefault("REPORT_ZONE", "America/Sao_Paulo"))); }
    public WeeklyHandler(Ports.Repository repository, Ports.Messenger messenger, Clock clock, ZoneId zone) {
        this.repository = repository; this.messenger = messenger; this.clock = clock; this.zone = zone;
    }
    public String handleRequest(ScheduledEvent event, Context context) {
        Instant reference = event != null && event.getTime() != null ? Instant.ofEpochMilli(event.getTime().getMillis()) : clock.instant();
        var endDay = reference.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var startDay = endDay.minusWeeks(1);
        var start = startDay.atStartOfDay(zone).toInstant();
        var end = endDay.atStartOfDay(zone).toInstant();
        var evaluations = repository.between(start, end);
        var days = new TreeMap<LocalDate, Integer>();
        for (int i = 0; i < 7; i++) days.put(startDay.plusDays(i), 0);
        var urgency = new EnumMap<Evaluation.Urgency, Integer>(Evaluation.Urgency.class);
        for (var level : Evaluation.Urgency.values()) urgency.put(level, 0);
        for (var e : evaluations) { days.merge(e.createdAt().atZone(zone).toLocalDate(), 1, Integer::sum); urgency.merge(e.urgencia(), 1, Integer::sum); }
        double average = evaluations.stream().mapToInt(Evaluation::nota).average().orElse(0);
        String header = "Relatório semanal [" + startDay + ", " + endDay + ") " + zone
            + "\nData de geração/envio: " + clock.instant() + "\nTotal: " + evaluations.size()
            + "\nMédia: " + String.format(Locale.ROOT, "%.2f", average) + "\nPor dia: " + days + "\nPor urgência: " + urgency + "\n";
        var pages = new ArrayList<String>(); var page = new StringBuilder(header);
        for (var e : evaluations) {
            String line = "ID: " + e.id() + " | Descrição: " + e.descricao() + " | Nota: " + e.nota() + " | Urgência: " + e.urgencia() + " | Data da avaliação: " + e.createdAt() + "\n";
            if ((page.toString() + line).getBytes(StandardCharsets.UTF_8).length > 230_000) { pages.add(page.toString()); page = new StringBuilder(header); }
            page.append(line);
        }
        pages.add(page.toString());
        for (int i = 0; i < pages.size(); i++) messenger.send("Relatório semanal " + startDay + " parte " + (i + 1) + "/" + pages.size(), pages.get(i));
        return "Relatório publicado: " + evaluations.size() + " avaliações, " + pages.size() + " partes";
    }
}
