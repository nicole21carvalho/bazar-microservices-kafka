import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import App from "./App";
import { auth } from "./auth/auth";
import "./index.css";

/**
 * Duas rotas tecnicas do login, tratadas antes de montar a tela:
 * - /callback: volta do Keycloak com o codigo, que vira token (PKCE);
 * - /silent: dentro de um iframe invisivel, renova a sessao sem tela.
 */
async function iniciar() {
  const caminho = window.location.pathname;
  if (caminho === "/silent") {
    await auth.signinSilentCallback();
    return;
  }
  if (caminho === "/callback") {
    try {
      const usuario = await auth.signinRedirectCallback();
      const destino = typeof usuario.state === "string" && usuario.state.startsWith("/") ? usuario.state : "/";
      // Tira o codigo da URL: nao fica no historico do navegador.
      window.history.replaceState({}, "", destino);
    } catch {
      window.history.replaceState({}, "", "/");
    }
  }
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void iniciar();
