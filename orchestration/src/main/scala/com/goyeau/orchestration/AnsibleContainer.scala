package com.goyeau.orchestration

import com.goyeau.orchestra.io.Directory
import com.goyeau.orchestra.kubernetes._

object AnsibleContainer
    extends Container("ansible", "registry.drivetribe.com/tools/ansible:cached", tty = true, Seq("cat")) {

  def install(implicit workDir: Directory) = {
    println("Install Ansible dependencies")
    "ansible-galaxy install -r ansible/requirements.yml" !> this
  }

  def init(environment: Environment)(implicit workDir: Directory) = {
    println("Init Ansible")
    s"""ansible-playbook init.yml \\
       |  --vault-password-file /opt/docker/secrets/ansible/vault-pass \\
       |  --private-key /opt/docker/secrets/ssh-key.pem \\
       |  -e env_name=${environment.entryName}""".stripMargin !> this
  }

  def playbook(playbook: String, params: String = "")(implicit workDir: Directory) =
    s"""ansible-playbook $playbook \\
       |  --vault-password-file /opt/docker/secrets/ansible/vault-pass \\
       |  --private-key /opt/docker/secrets/ssh-key.pem \\
       |  $params""".stripMargin !> this
}