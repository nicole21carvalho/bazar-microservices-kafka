output "ip_publico" {
  description = "Aponte o seu dominio para este IP (registro DNS do tipo A)."
  value       = oci_core_instance.vitrine.public_ip
}

output "ssh" {
  description = "Acesso de administracao (so a partir do ip_administracao)."
  value       = "ssh ubuntu@${oci_core_instance.vitrine.public_ip}"
}

output "tunel_kubernetes" {
  description = "Tunel para usar o kubectl do seu computador sem abrir a porta 6443."
  value       = "ssh -N -L 6443:127.0.0.1:6443 ubuntu@${oci_core_instance.vitrine.public_ip}"
}
