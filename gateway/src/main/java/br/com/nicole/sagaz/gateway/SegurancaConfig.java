package br.com.nicole.sagaz.gateway;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.unit.DataSize;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SegurancaConfig {

    @Bean
    SecurityFilterChain seguranca(
            HttpSecurity http,
            @Value("${sagaz.limites.requisicoes-por-minuto}") int geralPorMinuto,
            @Value("${sagaz.limites.pedidos-por-minuto}") int pedidosPorMinuto,
            @Value("${sagaz.limites.corpo-maximo}") DataSize corpoMaximo) throws Exception {
        // Criado aqui, e nao como @Bean: um Filter que e bean o Spring Boot
        // tambem registra sozinho no servidor, e ele rodaria duas vezes, uma
        // delas antes de saber quem e o usuario.
        var limite = new LimiteDeRequisicoes(geralPorMinuto, pedidosPorMinuto, corpoMaximo.toBytes());
        return http
                .authorizeHttpRequests(regras -> regras
                        // O catalogo e publico: qualquer um ve os produtos.
                        .requestMatchers(HttpMethod.GET, "/api/produtos", "/api/produtos/**").permitAll()
                        .requestMatchers("/api/pedidos", "/api/pedidos/**").authenticated()
                        // Saude e metricas: so na porta de gerencia (9000), que nao
                        // e publicada. Na porta da aplicacao, estas rotas nem existem.
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Qualquer outra rota: negada. Rota nova so funciona se
                        // alguem decidir aqui quem pode usa-la.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                // Depois da validacao do token: o limite sabe quem e o usuario.
                .addFilterAfter(limite, BearerTokenAuthenticationFilter.class)
                // Sem sessao no servidor: cada requisicao traz o proprio token.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF protege sessao por cookie; com token no cabecalho
                // Authorization, outro site nao consegue enviar o token sozinho.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .build();
    }

    // O nome do metodo importa: o .cors() do Spring Security procura o bean
    // chamado "corsConfigurationSource". Com outro nome ele nao acha e segue
    // sem CORS nenhum, e o navegador bloqueia todas as respostas do painel.
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${sagaz.cors.origens}") List<String> origens) {
        var config = new CorsConfiguration();
        // Origens explicitas, nunca "*".
        config.setAllowedOrigins(origens);
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Location", "Retry-After"));
        var fonte = new UrlBasedCorsConfigurationSource();
        fonte.registerCorsConfiguration("/api/**", config);
        return fonte;
    }
}
