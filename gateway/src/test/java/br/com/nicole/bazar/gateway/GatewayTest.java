package br.com.nicole.bazar.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sun.net.httpserver.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTest {

    /** Faz o papel do estoque e do pedidos: anota o que recebeu e responde. */
    static HttpServer servicos;
    static final Map<String, String> recebido = new ConcurrentHashMap<>();

    @BeforeAll
    static void subirServicos() throws IOException {
        servicos = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servicos.createContext("/", troca -> {
            recebido.put("caminho", troca.getRequestURI().getPath());
            String auth = troca.getRequestHeaders().getFirst("Authorization");
            if (auth != null) recebido.put("authorization", auth);
            byte[] corpo = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servicos.start();
    }

    @AfterAll
    static void descerServicos() {
        servicos.stop(0);
    }

    @DynamicPropertySource
    static void enderecos(DynamicPropertyRegistry registro) {
        String url = "http://localhost:" + servicos.getAddress().getPort();
        registro.add("ESTOQUE_URL", () -> url);
        registro.add("PEDIDOS_URL", () -> url);
    }

    // O Keycloak fica de fora: o decodificador aceita so o token "valido".
    @MockitoBean JwtDecoder jwt;

    @LocalServerPort int porta;
    @LocalManagementPort int portaGerencia;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void preparar() {
        recebido.clear();
        // willX().given(...): configura sem chamar o metodo, que ja lancaria o
        // erro da regra anterior.
        willThrow(new BadJwtException("token invalido")).given(jwt).decode(anyString());
        willReturn(Jwt.withTokenValue("valido")
                .header("alg", "RS256").subject("ana").claim("email", "ana@exemplo.com")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build())
                .given(jwt).decode("valido");
        // Outro usuario, so para o teste de limite nao gastar a cota da "ana".
        willReturn(Jwt.withTokenValue("valido-bia")
                .header("alg", "RS256").subject("bia").claim("email", "bia@exemplo.com")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build())
                .given(jwt).decode("valido-bia");
    }

    private HttpResponse<String> post(String caminho, String token, String corpo) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).GET();
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void catalogo_abre_sem_login_e_vai_para_o_estoque() throws Exception {
        var resposta = get("/api/produtos", null);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(recebido.get("caminho")).isEqualTo("/api/produtos");
    }

    @Test
    void pedidos_sem_token_ou_com_token_invalido_param_no_gateway() throws Exception {
        assertThat(get("/api/pedidos", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/pedidos", "forjado").statusCode()).isEqualTo(401);
        // A requisicao nem chegou ao servico.
        assertThat(recebido).doesNotContainKey("caminho");
    }

    @Test
    void com_token_valido_encaminha_e_repassa_o_token_para_o_servico_validar_de_novo() throws Exception {
        var resposta = get("/api/pedidos", "valido");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(recebido.get("caminho")).isEqualTo("/api/pedidos");
        assertThat(recebido.get("authorization")).isEqualTo("Bearer valido");
    }

    @Test
    void rota_que_ninguem_liberou_e_negada_mesmo_com_token() throws Exception {
        assertThat(get("/api/admin", "valido").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/env", "valido").statusCode()).isEqualTo(403);
        assertThat(recebido).doesNotContainKey("caminho");
    }

    @Test
    void mais_de_10_pedidos_por_minuto_recebe_429_com_retry_after() throws Exception {
        String pedido = "{\"itens\":[{\"produtoId\":\"cafe-especial\",\"quantidade\":1}]}";
        for (int i = 0; i < 10; i++) {
            assertThat(post("/api/pedidos", "valido-bia", pedido).statusCode()).isEqualTo(200);
        }

        var excesso = post("/api/pedidos", "valido-bia", pedido);

        assertThat(excesso.statusCode()).isEqualTo(429);
        assertThat(excesso.headers().firstValue("Retry-After")).isPresent();
        // A consulta continua liberada: o limite de criacao e separado do geral.
        assertThat(get("/api/pedidos", "valido-bia").statusCode()).isEqualTo(200);
    }

    @Test
    void corpo_acima_de_16_kb_e_recusado_sem_chegar_ao_servico() throws Exception {
        var resposta = post("/api/pedidos", "valido", "{\"x\":\"" + "a".repeat(20_000) + "\"}");

        assertThat(resposta.statusCode()).isEqualTo(413);
        assertThat(recebido).doesNotContainKey("caminho");
    }

    @Test
    void metricas_e_saude_nao_existem_na_porta_publica_so_na_de_gerencia() throws Exception {
        assertThat(get("/actuator/prometheus", null).statusCode()).isNotEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isNotEqualTo(200);

        var gerencia = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + portaGerencia + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(gerencia.statusCode()).isEqualTo(200);
    }

    @Test
    void cors_libera_so_o_endereco_do_painel() throws Exception {
        var painel = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/produtos"))
                .header("Origin", "http://localhost:5173").build(), HttpResponse.BodyHandlers.ofString());
        var outroSite = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/produtos"))
                .header("Origin", "https://site-malicioso.example").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(painel.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        // Outra origem: o navegador nao recebe permissao para ler a resposta.
        assertThat(outroSite.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void preflight_do_navegador_libera_o_cabecalho_authorization() throws Exception {
        var resposta = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/pedidos"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("authorization");
    }
}
