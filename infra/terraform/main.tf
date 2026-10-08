# Uma VM ARM gratuita da Oracle Cloud com k3s, numa rede propria. Para fora,
# so ficam abertos HTTP e HTTPS (o site) e SSH apenas a partir do seu IP.

data "oci_identity_availability_domains" "todos" {
  compartment_id = var.compartimento_ocid
}

data "oci_core_images" "ubuntu" {
  compartment_id           = var.compartimento_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

# --- Rede ---

resource "oci_core_vcn" "bazar" {
  compartment_id = var.compartimento_ocid
  display_name   = "bazar"
  cidr_blocks    = ["10.0.0.0/16"]
  dns_label      = "bazar"
}

resource "oci_core_internet_gateway" "bazar" {
  compartment_id = var.compartimento_ocid
  vcn_id         = oci_core_vcn.bazar.id
  display_name   = "bazar"
  enabled        = true
}

resource "oci_core_route_table" "bazar" {
  compartment_id = var.compartimento_ocid
  vcn_id         = oci_core_vcn.bazar.id
  display_name   = "bazar"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.bazar.id
  }
}

resource "oci_core_security_list" "bazar" {
  compartment_id = var.compartimento_ocid
  vcn_id         = oci_core_vcn.bazar.id
  display_name   = "bazar"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  # Site: HTTP (so redireciona para HTTPS e responde ao Let's Encrypt) e HTTPS.
  dynamic "ingress_security_rules" {
    for_each = [80, 443]
    content {
      source   = "0.0.0.0/0"
      protocol = "6" # TCP
      tcp_options {
        min = ingress_security_rules.value
        max = ingress_security_rules.value
      }
    }
  }

  # SSH: so do seu IP. A API do Kubernetes nao tem regra nenhuma: chega-se a
  # ela por um tunel SSH (veja o README).
  ingress_security_rules {
    source   = var.ip_administracao
    protocol = "6"
    tcp_options {
      min = 22
      max = 22
    }
  }

  # ICMP "fragmentation needed": sem isto, conexoes com pacotes grandes travam.
  ingress_security_rules {
    source   = "0.0.0.0/0"
    protocol = "1" # ICMP
    icmp_options {
      type = 3
      code = 4
    }
  }
}

resource "oci_core_subnet" "publica" {
  compartment_id    = var.compartimento_ocid
  vcn_id            = oci_core_vcn.bazar.id
  display_name      = "bazar-publica"
  cidr_block        = "10.0.1.0/24"
  dns_label         = "publica"
  route_table_id    = oci_core_route_table.bazar.id
  security_list_ids = [oci_core_security_list.bazar.id]
}

# --- Maquina ---

resource "oci_core_instance" "bazar" {
  compartment_id      = var.compartimento_ocid
  availability_domain = data.oci_identity_availability_domains.todos.availability_domains[var.indice_dominio_disponibilidade].name
  display_name        = "bazar"
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memoria_gb
  }

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu.images[0].id
    boot_volume_size_in_gbs = var.disco_gb
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.publica.id
    assign_public_ip = true
    hostname_label   = "bazar"
  }

  # So a versao 2 do servico de metadados (com sessao): dificulta que uma
  # falha de SSRF numa aplicacao leia os dados da maquina.
  instance_options {
    are_legacy_imds_endpoints_disabled = true
  }

  metadata = {
    ssh_authorized_keys = var.chave_ssh_publica
    user_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
      config = join("\n", [
        "VERSAO_K3S=${var.versao_k3s}",
        "VERSAO_FLUX=${var.versao_flux}",
        "REPOSITORIO=${var.repositorio}",
        "RAMO=${var.ramo}",
      ])
      traefik         = file("${path.module}/k3s/traefik.yaml")
      cert_manager    = file("${path.module}/k3s/cert-manager.yaml")
      cluster_issuer  = file("${path.module}/k3s/cluster-issuer.yaml")
      preparar_script = file("${path.module}/k3s/preparar-cluster.sh")
    }))
  }

  lifecycle {
    # Uma imagem Ubuntu mais nova nao deve recriar a VM (e apagar o disco):
    # a propria VM se atualiza (unattended-upgrades).
    ignore_changes = [source_details[0].source_id]
  }
}
