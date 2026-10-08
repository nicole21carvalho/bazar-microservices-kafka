package br.com.nicole.bazar.pagamentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import br.com.nicole.bazar.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.bazar.comum.eventos.Evento.PagamentoAprovado;
import br.com.nicole.bazar.comum.eventos.Evento.PagamentoRecusado;
import br.com.nicole.bazar.comum.eventos.LeitorDeEventos;
import br.com.nicole.bazar.comum.eventos.Topicos;
import br.com.nicole.bazar.comum.teste.Containers;
import br.com.nicole.bazar.comum.teste.Mensageiro;

@SpringBootTest(properties = {
    "DB_SENHA_PAGAMENTOS=teste", "bazar.outbox.intervalo=100ms", "bazar.pagamentos.limite-aprovacao=1000.00"})
@Import(Containers.class)
class PagamentoIntegracaoTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumidores;
    @Autowired LeitorDeEventos leitor;

    Mensageiro mensageiro;

    @BeforeEach
    void preparar() {
        mensageiro = new Mensageiro(kafka, consumidores, leitor);
    }

    @Test
    void valor_dentro_do_limite_e_aprovado() throws Exception {
        UUID pedido = UUID.randomUUID();

        mensageiro.publicar(Topicos.ESTOQUE, new EstoqueReservado(pedido, new BigDecimal("389.00")), UUID.randomUUID());

        assertThat(mensageiro.eventos(Topicos.PAGAMENTOS, pedido, 1)).singleElement()
                .isInstanceOfSatisfying(PagamentoAprovado.class,
                        e -> assertThat(e.valor()).isEqualByComparingTo("389.00"));
    }

    @Test
    void valor_acima_do_limite_e_recusado_com_o_motivo() throws Exception {
        UUID pedido = UUID.randomUUID();

        mensageiro.publicar(Topicos.ESTOQUE, new EstoqueReservado(pedido, new BigDecimal("1899.00")), UUID.randomUUID());

        assertThat(mensageiro.eventos(Topicos.PAGAMENTOS, pedido, 1)).singleElement()
                .isInstanceOfSatisfying(PagamentoRecusado.class,
                        e -> assertThat(e.motivo()).contains("limite").contains("1.000,00"));
    }

    @Test
    void reserva_repetida_mesmo_com_outro_id_de_evento_nunca_cobra_duas_vezes() throws Exception {
        UUID pedido = UUID.randomUUID();
        var reserva = new EstoqueReservado(pedido, new BigDecimal("42.90"));

        mensageiro.publicar(Topicos.ESTOQUE, reserva, UUID.randomUUID());
        mensageiro.publicar(Topicos.ESTOQUE, reserva, UUID.randomUUID());

        assertThat(mensageiro.eventos(Topicos.PAGAMENTOS, pedido, 1)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento WHERE pedido_id = ?", Integer.class, pedido))
                .isEqualTo(1);
    }
}
