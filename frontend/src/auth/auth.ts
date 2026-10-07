import { InMemoryWebStorage, UserManager, WebStorageStateStore } from "oidc-client-ts";

const KEYCLOAK = import.meta.env.VITE_KEYCLOAK_URL ?? "http://localhost:8180/realms/vitrine";
const CLIENTE = import.meta.env.VITE_KEYCLOAK_CLIENTE ?? "vitrine-web";

/**
 * Login pelo Keycloak com Authorization Code + PKCE: o navegador nunca ve a
 * senha do usuario nem guarda segredo do cliente.
 *
 * Os tokens ficam so na memoria (InMemoryWebStorage), e nao no localStorage:
 * um script injetado na pagina (XSS) nao tem onde le-los. Ao recarregar, a
 * sessao volta em silencio pelo cookie de sessao do proprio Keycloak.
 */
export const auth = new UserManager({
  authority: KEYCLOAK,
  client_id: CLIENTE,
  redirect_uri: `${window.location.origin}/callback`,
  silent_redirect_uri: `${window.location.origin}/silent`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: "code",
  scope: "openid email profile",
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true,
});

/** Tenta retomar a sessao sem tela de login; null se nao houver sessao no Keycloak. */
export async function retomarSessao() {
  const atual = await auth.getUser();
  if (atual && !atual.expired) return atual;
  try {
    return await auth.signinSilent();
  } catch {
    return null;
  }
}

export function entrar() {
  return auth.signinRedirect({ state: window.location.pathname });
}

export function sair() {
  return auth.signoutRedirect();
}

export async function token(): Promise<string | null> {
  const usuario = await auth.getUser();
  return usuario && !usuario.expired ? usuario.access_token : null;
}
