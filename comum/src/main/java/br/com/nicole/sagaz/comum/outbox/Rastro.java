package br.com.nicole.sagaz.comum.outbox;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Leva o rastreamento distribuido atraves do outbox.
 *
 * O evento e gravado durante a requisicao do cliente, mas publicado depois,
 * por uma tarefa agendada que nao sabe de requisicao nenhuma. Sem isto, o trace
 * do pedido terminaria no banco, e o resto da saga apareceria no Jaeger como
 * traces soltos, sem ligacao com o pedido que os causou.
 *
 * A solucao: guardar o "traceparent" (padrao W3C Trace Context) junto com o
 * evento e, na hora de publicar, continuar o mesmo trace a partir dele.
 */
public class Rastro {

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagador;

    public Rastro(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagador) {
        this.tracer = tracer;
        this.propagador = propagador;
    }

    /** O traceparent do trace atual, ou null se nao ha trace em andamento. */
    public String atual() {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagador.getIfAvailable();
        if (t == null || p == null || t.currentTraceContext().context() == null) {
            return null;
        }
        Map<String, String> cabecalhos = new HashMap<>();
        p.inject(t.currentTraceContext().context(), cabecalhos, Map::put);
        return cabecalhos.get("traceparent");
    }

    /** Executa a acao dentro de um span filho do trace guardado no evento. */
    public <T> T continuar(String traceparent, String nomeDoSpan, Supplier<T> acao) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagador.getIfAvailable();
        if (traceparent == null || t == null || p == null) {
            return acao.get();
        }
        Span span = p.extract(Map.of("traceparent", traceparent), Map::get)
                .name(nomeDoSpan)
                .kind(Span.Kind.PRODUCER)
                .start();
        try (var emEscopo = t.withSpan(span)) {
            return acao.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
