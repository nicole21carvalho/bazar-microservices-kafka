variable "perfil_oci" {
  description = "Perfil do arquivo ~/.oci/config com as credenciais da API."
  type        = string
  default     = "DEFAULT"
}

variable "regiao" {
  description = "Regiao da Oracle Cloud (a mesma da conta gratuita), por exemplo sa-saopaulo-1."
  type        = string
}

variable "compartimento_ocid" {
  description = "OCID do compartimento onde tudo e criado (pode ser o da propria tenancy)."
  type        = string

  validation {
    condition     = startswith(var.compartimento_ocid, "ocid1.")
    error_message = "Use um OCID (comeca com ocid1.)."
  }
}

variable "chave_ssh_publica" {
  description = "Chave SSH publica para administrar a VM. So a publica: a privada nunca sai do seu computador."
  type        = string

  validation {
    condition     = can(regex("^(ssh-ed25519|ecdsa-sha2-nistp256|ssh-rsa) ", var.chave_ssh_publica))
    error_message = "Cole a chave publica (o conteudo do arquivo .pub)."
  }
}

variable "ip_administracao" {
  description = "Unico endereco que pode abrir SSH, em notacao CIDR (seu IP com /32)."
  type        = string

  validation {
    condition     = can(cidrhost(var.ip_administracao, 0)) && var.ip_administracao != "0.0.0.0/0"
    error_message = "Use um CIDR do seu IP, por exemplo 203.0.113.10/32. SSH aberto para a internet inteira nao e aceito."
  }
}

variable "repositorio" {
  description = "Repositorio Git publico que o Flux acompanha (a pasta infra/ dele vira o cluster)."
  type        = string
  default     = "https://github.com/nicole21carvalho/sagaz-microservices-kafka"
}

variable "ramo" {
  description = "Ramo do repositorio que o Flux acompanha."
  type        = string
  default     = "main"
}

variable "versao_k3s" {
  description = "Versao do k3s (fixa: atualizar e uma decisao, nao um acidente)."
  type        = string
  default     = "v1.36.5+k3s1"
}

variable "versao_flux" {
  description = "Versao do Flux."
  type        = string
  default     = "v2.9.6"
}

# Limites do nivel gratuito (Always Free) da Oracle para maquinas ARM (Ampere
# A1): 4 OCPUs e 24 GB de memoria no total, 200 GB de disco.
variable "ocpus" {
  description = "Processadores ARM da VM."
  type        = number
  default     = 4
}

variable "memoria_gb" {
  description = "Memoria da VM, em GB."
  type        = number
  default     = 24
}

variable "disco_gb" {
  description = "Disco de boot, em GB."
  type        = number
  default     = 100
}

variable "indice_dominio_disponibilidade" {
  description = "Qual dominio de disponibilidade usar (0, 1 ou 2). Se a Oracle disser que falta capacidade ARM, tente outro."
  type        = number
  default     = 0
}
