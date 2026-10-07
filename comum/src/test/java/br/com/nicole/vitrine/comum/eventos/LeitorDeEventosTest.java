package br.com.nicole.vitrine.comum.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import br.com.nicole.vitrine.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.vitrine.comum.eventos.Evento.ItemPedido;
import br.com.nicole.vitrine.comum.eventos.Evento.PedidoCriado;
import tools.jackson.databind.json.JsonMapper;

class LeitorDeEventosTest {

    private final LeitorDeEventos leitor = new LeitorDeEventos(JsonMapper.builder().build());

    private ConsumerRecord<String, String> mensagem(String tipo, UUID id, String corpo) {
        var registro = new ConsumerRecord<>("vitrine.pedidos", 0, 0L, "chave", corpo);
        if (tipo != null) registro.headers().add(Cabecalhos.TIPO, tipo.getBytes(StandardCharsets.UTF_8));
        if (id != null) registro.headers().add(Cabecalhos.ID, id.toString().getBytes(StandardCharsets.UTF_8));
        return registro;
    }

    @Test
    void evento_vai_e_volta_pelo_json_sem_perder_nada() {
        var evento = new PedidoCriado(
                UUID.randomUUID(), "cliente-1", "ana@exemplo.com",
                List.of(new ItemPedido("cafe-especial", 2)), Instant.parse("2026-10-07T12:00:00Z"));
        var id = UUID.randomUUID();

        var lida = leitor.ler(mensagem("PedidoCriado", id, leitor.paraJson(evento)));

        assertThat(lida.eventoId()).isEqualTo(id);
        assertThat(lida.evento()).isEqualTo(evento);
    }

    @Test
    void valor_em_dinheiro_nao_perde_centavos() {
        var evento = new EstoqueReservado(UUID.randomUUID(), new BigDecimal("1234.56"));

        var lida = leitor.ler(mensagem("EstoqueReservado", UUID.randomUUID(), leitor.paraJson(evento)));

        assertThat(((EstoqueReservado) lida.evento()).total()).isEqualByComparingTo("1234.56");
    }

    @Test
    void tipo_desconhecido_json_quebrado_ou_sem_cabecalho_e_evento_invalido() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> leitor.ler(mensagem("PedidoInventado", id, "{}")))
                .isInstanceOf(EventoInvalidoException.class);
        assertThatThrownBy(() -> leitor.ler(mensagem("PedidoCriado", id, "{nao e json")))
                .isInstanceOf(EventoInvalidoException.class);
        assertThatThrownBy(() -> leitor.ler(mensagem(null, id, "{}")))
                .isInstanceOf(EventoInvalidoException.class);
        assertThatThrownBy(() -> leitor.ler(mensagem("PedidoCriado", null, "{}")))
                .isInstanceOf(EventoInvalidoException.class);
    }

    @Test
    void tipo_do_evento_e_o_nome_do_record() {
        assertThat(LeitorDeEventos.tipoDe(new EstoqueReservado(UUID.randomUUID(), BigDecimal.ONE)))
                .isEqualTo("EstoqueReservado");
    }
}
