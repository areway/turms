locals {
  open_ports = [
    # SSH
    "22/22",
    # RPC
    "7510/7510",
    # Metrics APIs + Admin APIs
    "8510/8510"
  ]

  config_mongodb_uri       = "mongodb://root:${var.config_mongodb_account_password}@${join(",", var.config_mongodb_hosts)}/turms?authSource=admin"
  admin_mongodb_uri        = "mongodb://root:${var.admin_mongodb_account_password}@${join(",", var.admin_mongodb_hosts)}/turms?authSource=admin"
  user_mongodb_uri         = "mongodb://root:${var.user_mongodb_account_password}@${join(",", var.user_mongodb_hosts)}/turms?authSource=admin"
  group_mongodb_uri        = "mongodb://root:${var.group_mongodb_account_password}@${join(",", var.group_mongodb_hosts)}/turms?authSource=admin"
  conversation_mongodb_uri = "mongodb://root:${var.conversation_mongodb_account_password}@${join(",", var.conversation_mongodb_hosts)}/turms?authSource=admin"
  message_mongodb_uri      = "mongodb://root:${var.message_mongodb_account_password}@${join(",", var.message_mongodb_hosts)}/turms?authSource=admin"

  session_redis_uri  = "redis://${var.session_redis_account_name}:${var.session_redis_account_password}@${var.session_redis_host}"
  location_redis_uri = "redis://${var.location_redis_account_name}:${var.location_redis_account_password}@${var.location_redis_host}"
}

#=============== Security

resource "alicloud_security_group" "default" {
  count = var.create_turms ? 1 : 0

  name                = var.security_group_name
  vpc_id              = var.vpc_id
  inner_access_policy = "Accept"
  tags                = var.security_group_tags
}

resource "alicloud_security_group_rule" "default" {
  count = var.create_turms ? length(local.open_ports) : 0

  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = var.nic_type
  policy            = "accept"
  port_range        = local.open_ports[count.index]
  security_group_id = alicloud_security_group.default[0].id
  cidr_ip           = "0.0.0.0/0"
}

#=============== ECS

data "alicloud_images" "default" {
  name_regex = var.image_name_regex
}

resource "alicloud_instance" "default" {
  count = var.create_turms ? var.instance_count : 0

  host_name     = "${var.host_name}-${count.index + 1}"
  instance_name = "${var.ecs_name}-${count.index + 1}"
  tags          = var.ecs_tags

  # Charge
  internet_charge_type = var.internet_charge_type
  period               = var.period

  # Specification
  image_id      = data.alicloud_images.default.images[0].id
  instance_type = var.instance_type

  # Network
  vswitch_id                 = var.vswitch_id
  internet_max_bandwidth_out = var.max_bandwidth_out

  # Disk
  system_disk_category = var.disk_category
  system_disk_size     = var.disk_size

  # Security
  security_groups = [alicloud_security_group.default[0].id]

  # Protection
  deletion_protection = var.delete_protection

  # TODO: replace with image
  user_data = templatefile("${path.root}/../../common/cluster/tpl/user-data-turms.sh", {
    PROFILE         = var.turms_profile
    CUSTOM_JVM_OPTS = var.turms_jvm_opts

    CONFIG_MONGODB_URI       = local.config_mongodb_uri
    ADMIN_MONGODB_URI        = local.admin_mongodb_uri
    USER_MONGODB_URI         = local.user_mongodb_uri
    GROUP_MONGODB_URI        = local.group_mongodb_uri
    CONVERSATION_MONGODB_URI = local.conversation_mongodb_uri
    MESSAGE_MONGODB_URI      = local.message_mongodb_uri

    SESSION_REDIS_URI  = local.session_redis_uri
    LOCATION_REDIS_URI = local.location_redis_uri
  })
}

resource "alicloud_ecs_key_pair" "default" {
  count = var.create_turms ? var.instance_count : 0

  key_pair_name = "${var.key_pair_name}-${count.index + 1}"
  tags          = var.key_pair_tags
}

resource "alicloud_ecs_key_pair_attachment" "default" {
  count = var.create_turms ? var.instance_count : 0

  key_pair_name = alicloud_ecs_key_pair.default[count.index].key_pair_name
  instance_ids  = [alicloud_instance.default[count.index].id]
}