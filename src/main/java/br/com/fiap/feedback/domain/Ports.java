package br.com.fiap.feedback.domain;

import java.time.Instant;
import java.util.List;

public final class Ports {
    private Ports() { }
    public interface Repository {
        void save(Evaluation evaluation);
        List<Evaluation> between(Instant start, Instant end);
        boolean notified(String id);
        void markNotified(String id, Instant sentAt);
    }
    public interface Messenger { void send(String subject, String body); }
}
