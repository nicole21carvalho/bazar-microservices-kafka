terraform {
  required_version = ">= 1.9"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 9.8"
    }
  }
}

# As credenciais da API da Oracle ficam em ~/.oci/config, fora do repositorio
# (crie com "oci setup config"). Aqui so vai o nome do perfil.
provider "oci" {
  config_file_profile = var.perfil_oci
  region              = var.regiao
}
