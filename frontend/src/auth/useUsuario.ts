import type { User } from "oidc-client-ts";
import { useEffect, useState } from "react";

import { auth, retomarSessao } from "./auth";

export type Sessao = { carregando: boolean; usuario: User | null };

/** Usuario logado, atualizado quando o token renova ou a sessao termina. */
export function useUsuario(): Sessao {
  const [sessao, setSessao] = useState<Sessao>({ carregando: true, usuario: null });

  useEffect(() => {
    let ativo = true;
    retomarSessao().then((usuario) => ativo && setSessao({ carregando: false, usuario }));

    const aoCarregar = (usuario: User) => setSessao({ carregando: false, usuario });
    const aoSair = () => setSessao({ carregando: false, usuario: null });
    auth.events.addUserLoaded(aoCarregar);
    auth.events.addUserUnloaded(aoSair);
    auth.events.addSilentRenewError(aoSair);
    return () => {
      ativo = false;
      auth.events.removeUserLoaded(aoCarregar);
      auth.events.removeUserUnloaded(aoSair);
      auth.events.removeSilentRenewError(aoSair);
    };
  }, []);

  return sessao;
}
