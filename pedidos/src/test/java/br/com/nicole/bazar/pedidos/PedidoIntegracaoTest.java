package br.com.nicole.bazar.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import br.com.nicole.bazar.comum.eventos.Evento.EstoqueRecusado;
import br.com.nicole.bazar.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.bazar.comum.eventos.Evento.PagamentoAprovado;
import br.com.nicole.bazar.comum.eventos.Evento.PagamentoRecusado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoConfirmado;
import br.com.nicole.bazar.comum.eventos.Evento.PedidoCriado;
import br.com.nicole.bazar.comum.eventos.LeitorDeEventos;
import br.com.nicole.bazar.comum.eventos.Topicos;
import br.com.nicole.bazar.comum.teste.Containers;
import br.com.nicole.bazar.comum.teste.Mensageiro;
import br.com.nicole.bazar.pedidos.pedido.Pedido;
import br.com.nicole.bazar.pedidos.pedido.PedidoRepository;

@SpringBootTest(properties = {"DB_SENHA_PEDIDOS=teste", "bazar.outbox.intervalo=100ms"})
@AutoConfigureMockMvc
@Import(Containers.class)
class PedidoIntegracaoTest {

    @Autowired MockMvcTester http;
    @Autowired PedidoRepository pedidos;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumidores;
    @Autowired LeitorDeEventos leitor;

    Mensageiro mensageiro;

    @BeforeEach
    void preparar() {
        mensageiro = new Mensageiro(kafka, consumidores, leitor);
    }

    /** Token JWT de teste, como o que o Keycloak emitiria para este cliente. */
    private static RequestPostProcessor cliente(String nome) {
        return jwt().jwt(j -> j.subject(nome).claim("email", nome + "@exemplo.com").claim("email_verified", true));
    }

    private UUID criarPedido(String cliente) {
        var resposta = http.post().uri("/api/pedidos")
                .with(cliente(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"itens": [{"produtoId": "cafe-especial", "quantidade": 1},
                                   {"produtoId": "caneca-ceramica", "quantidade": 2},
                                   {"produtoId": "cafe-especial", "quantidade": 1}]}
                        """)
                .exchange();
        assertThat(resposta).hasStatus(HttpStatus.ACCEPTED);
        String location = resposta.getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private Pedido.Status statusDe(UUID id) {
        return pedidos.findById(id).orElseThrow().getStatus();
    }

    private void esperarStatus(UUID id, Pedido.Status esperado) {
        await().atMost(Duration.ofSeconds(20)).until(() -> statusDe(id) == esperado);
    }

    @Test
    void pedido_nasce_pendente_e_publica_PedidoCriado_com_itens_repetidos_somados() {
        UUID id = criarPedido("ana");

        assertThat(statusDe(id)).isEqualTo(Pedido.Status.PENDENTE);
        assertThat(mensageiro.eventos(Topicos.PEDIDOS, id, 1)).singleElement()
                .isInstanceOfSatisfying(PedidoCriado.class, e -> {
                    assertThat(e.itens()).extracting(i -> i.produtoId() + "=" + i.quantidade())
                            .containsExactly("cafe-especial=2", "caneca-ceramica=2");
                });
    }

    @Test
    void estoque_reservado_e_pagamento_aprovado_confirmam_o_pedido() throws Exception {
        UUID id = criarPedido("bia");

        mensageiro.publicar(Topicos.ESTOQUE, new EstoqueReservado(id, new BigDecimal("165.60")), UUID.randomUUID());
        esperarStatus(id, Pedido.Status.AGUARDANDO_PAGAMENTO);
        mensageiro.publicar(Topicos.PAGAMENTOS,
                new PagamentoAprovado(id, UUID.randomUUID(), new BigDecimal("165.60")), UUID.randomUUID());

        esperarStatus(id, Pedido.Status.CONFIRMADO);
        assertThat(mensageiro.eventos(Topicos.PEDIDOS, id, 2))
                .anySatisfy(e -> assertThat(e).isInstanceOf(PedidoConfirmado.class));
    }

    @Test
    void pagamento_recusado_cancela_e_publica_PedidoCancelado_para_o_estoque_devolver_os_itens() throws Exception {
        UUID id = criarPedido("caio");

        mensageiro.publicar(Topicos.ESTOQUE, new EstoqueReservado(id, new BigDecimal("50.00")), UUID.randomUUID());
        mensageiro.publicar(Topicos.PAGAMENTOS, new PagamentoRecusado(id, "Cartão recusado"), UUID.randomUUID());

        esperarStatus(id, Pedido.Status.CANCELADO);
        assertThat(pedidos.findById(id).orElseThrow().getMotivo()).isEqualTo("Cartão recusado");
        assertThat(mensageiro.eventos(Topicos.PEDIDOS, id, 2))
                .anySatisfy(e -> assertThat(e).isInstanceOf(PedidoCancelado.class));
    }

    @Test
    void estoque_recusado_cancela_o_pedido() throws Exception {
        UUID id = criarPedido("duda");

        mensageiro.publicar(Topicos.ESTOQUE, new EstoqueRecusado(id, "Sem estoque de Luminária"), UUID.randomUUID());

        esperarStatus(id, Pedido.Status.CANCELADO);
    }

    @Test
    void pagamento_que_chega_antes_da_reserva_confirma_e_a_reserva_atrasada_nao_desfaz() throws Exception {
        UUID id = criarPedido("edu");

        // Topicos diferentes nao garantem ordem entre si.
        mensageiro.publicar(Topicos.PAGAMENTOS,
                new PagamentoAprovado(id, UUID.randomUUID(), new BigDecimal("10.00")), UUID.randomUUID());
        esperarStatus(id, Pedido.Status.CONFIRMADO);
        UUID atrasado = UUID.randomUUID();
        mensageiro.publicar(Topicos.ESTOQUE, new EstoqueReservado(id, new BigDecimal("10.00")), atrasado);

        // Espera o evento atrasado ser consumido e confere que nada mudou.
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM evento_processado WHERE evento_id = ?", Integer.class, atrasado) == 1);
        assertThat(statusDe(id)).isEqualTo(Pedido.Status.CONFIRMADO);
    }

    @Test
    void cliente_nao_ve_pedido_de_outro_cliente() {
        UUID id = criarPedido("fabi");

        assertThat(http.get().uri("/api/pedidos/" + id).with(cliente("gabi")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(http.get().uri("/api/pedidos/" + id).with(cliente("fabi")))
                .hasStatusOk();
    }

    @Test
    void entrada_invalida_e_recusada_sem_criar_pedido() {
        long antes = pedidos.count();

        assertThat(http.post().uri("/api/pedidos")
                .with(cliente("hugo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itens\": [{\"produtoId\": \"cafe-especial\", \"quantidade\": 0}]}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(http.post().uri("/api/pedidos")
                .with(cliente("hugo"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"itens\": []}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pedidos.count()).isEqualTo(antes);
    }

    @Test
    void o_trace_da_requisicao_atravessa_o_outbox_e_chega_no_kafka() {
        // Trace iniciado "no navegador": o pedido inteiro deve continuar nele.
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        var resposta = http.post().uri("/api/pedidos").with(cliente("iris"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itens\": [{\"produtoId\": \"cafe-especial\", \"quantidade\": 1}]}")
                .exchange();
        String location = resposta.getResponse().getHeader("Location");
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        var mensagem = mensageiro.esperar(Topicos.PEDIDOS, id.toString(), 1).getFirst();

        var traceparent = mensagem.headers().lastHeader("traceparent");
        assertThat(traceparent).as("cabecalho traceparent na mensagem").isNotNull();
        assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).contains(traceId);
    }

    @Test
    void email_nao_confirmado_nao_cria_pedido() {
        assertThat(http.post().uri("/api/pedidos")
                .with(jwt().jwt(j -> j.subject("jade").claim("email", "jade@exemplo.com").claim("email_verified", false)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itens\": [{\"produtoId\": \"cafe-especial\", \"quantidade\": 1}]}"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void sem_token_responde_401() {
        assertThat(http.get().uri("/api/pedidos")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void token_sem_email_nao_cria_pedido() {
        assertThat(http.post().uri("/api/pedidos")
                .with(jwt().jwt(j -> j.subject("sem-email")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itens\": [{\"produtoId\": \"cafe-especial\", \"quantidade\": 1}]}"))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
