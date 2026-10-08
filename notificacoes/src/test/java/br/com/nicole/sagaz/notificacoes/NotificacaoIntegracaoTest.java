package br.com.nicole.sagaz.notificacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.nicole.sagaz.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.sagaz.comum.eventos.LeitorDeEventos;
import br.com.nicole.sagaz.comum.eventos.Topicos;
import br.com.nicole.sagaz.comum.teste.Containers;
import br.com.nicole.sagaz.comum.teste.Mensageiro;

// O envio de e-mail e um mock; o health check de e-mail procuraria o envio real.
@SpringBootTest(properties = {"DB_SENHA_NOTIFICACOES=teste", "management.health.mail.enabled=false"})
@Import(Containers.class)
class NotificacaoIntegracaoTest {

    @MockitoBean JavaMailSender email;

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumidores;
    @Autowired LeitorDeEventos leitor;

    Mensageiro mensageiro;

    @BeforeEach
    void preparar() {
        mensageiro = new Mensageiro(kafka, consumidores, leitor);
        clearInvocations(email);
    }

    private int avisos(UUID pedido) {
        return jdbc.queryForObject("SELECT count(*) FROM notificacao WHERE pedido_id = ?", Integer.class, pedido);
    }

    private SimpleMailMessage ultimoEmail() {
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(email, atLeastOnce()).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void pedido_confirmado_manda_email_com_o_total_em_reais() throws Exception {
        UUID pedido = UUID.randomUUID();

        mensageiro.publicar(Topicos.PEDIDOS,
                new PedidoConfirmado(pedido, "ana@exemplo.com", new BigDecimal("1234.50")), UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).until(() -> avisos(pedido) == 1);
        var enviado = ultimoEmail();
        assertThat(enviado.getTo()).containsExactly("ana@exemplo.com");
        assertThat(enviado.getText()).contains("R$").contains("1.234,50");
    }

    @Test
    void pedido_cancelado_manda_email_com_o_motivo() throws Exception {
        UUID pedido = UUID.randomUUID();

        mensageiro.publicar(Topicos.PEDIDOS,
                new PedidoCancelado(pedido, "bia@exemplo.com", "Sem estoque de Luminária"), UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).until(() -> avisos(pedido) == 1);
        assertThat(ultimoEmail().getText()).contains("Sem estoque de Luminária").contains("Nenhum valor foi cobrado");
    }

    @Test
    void mensagem_repetida_nao_manda_o_email_duas_vezes() throws Exception {
        UUID pedido = UUID.randomUUID();
        UUID mesmoId = UUID.randomUUID();
        var confirmado = new PedidoConfirmado(pedido, "caio@exemplo.com", BigDecimal.TEN);

        mensageiro.publicar(Topicos.PEDIDOS, confirmado, mesmoId);
        mensageiro.publicar(Topicos.PEDIDOS, confirmado, mesmoId);

        await().atMost(Duration.ofSeconds(20)).until(() -> avisos(pedido) == 1);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> avisos(pedido) == 1);
        verify(email, atLeastOnce()).send(any(SimpleMailMessage.class));
    }
}
