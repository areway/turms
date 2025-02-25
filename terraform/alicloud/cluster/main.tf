terraform {
  required_version = ">= 1.00"
  required_providers {
    alicloud = {
      source  = "aliyun/alicloud"
      version = "~> 1.129.0"
    }
  }
  # TODO: https://github.com/hashicorp/terraform/issues/28406
  experiments      = [module_variable_optional_attrs]
}

#https://github.com/aliyun/terraform-provider-alicloud/blob/master/website/docs/index.html.markdown
provider "alicloud" {
  region     = var.region
  access_key = var.access_key
  secret_key = var.secret_key
}

data "alicloud_zones" "multi" {
  available_instance_type     = var.turms_instance_type
  available_disk_category     = var.turms_disk_category
  available_resource_creation = "MongoDB"
  multi                       = true
}

data "alicloud_zones" "single1" {
  available_instance_type     = var.turms_gateway_instance_type
  available_disk_category     = var.turms_gateway_disk_category
  available_resource_creation = "KVStore"
}

data "alicloud_zones" "single2" {
  available_instance_type     = var.turms_admin_instance_type
  available_disk_category     = var.turms_admin_disk_category
  available_resource_creation = "Slb"
}

locals {
  zone_ids          = tolist(setintersection(data.alicloud_zones.multi.zones[0].multi_zone_ids, data.alicloud_zones.single1.ids, data.alicloud_zones.single2.ids))
  primary_zone_id   = local.zone_ids[0]
  secondary_zone_id = local.zone_ids[1]
  multi_zone_id     = data.alicloud_zones.multi.ids[0]
}

module "vpc" {
  source = "./modules/vpc"

  create_vpc = var.create_vpc

  vpc_name = var.vpc_name
  vpc_tags = var.vpc_tags
  vpc_cidr = var.vpc_cidr

  vswitch_name           = var.vswitch_name
  vswitch_tags           = var.vswitch_tags
  vswitch_zone_cidr_list = var.vswitch_zone_cidr_list
}

module "natgateway" {
  source     = "./modules/nat"
  create_nat = var.create_nat

  nat_name          = var.nat_name
  nat_vpc_id        = var.create_vpc ? module.vpc.vpc_id : var.nat_vpc_id
  nat_vswitch_id    = var.create_vpc ? module.vpc.vswitch_ids[0] : var.nat_vswitch_id
  nat_payment_type  = var.nat_payment_type
  nat_specification = var.nat_specification

  snat_entry_name         = var.snat_entry_name
  snat_source_vswitch_ids = var.create_vpc ? module.vpc.vswitch_ids : var.snat_source_vswitch_ids

  eip_address_name         = var.nat_eip_address_name
  eip_tags                 = var.nat_eip_tags
  eip_internet_charge_type = var.nat_eip_internet_charge_type
  eip_payment_type         = var.nat_eip_payment_type
  eip_bandwidth            = var.nat_eip_bandwidth
  eip_isp                  = var.nat_eip_isp
}

module "slb" {
  source     = "./modules/slb"
  create_slb = var.create_slb

  name = var.slb_name
  tags = var.slb_tags

  internet_charge_type = var.slb_internet_charge_type
  payment_type         = var.slb_payment_type

  specification = var.slb_specification
  bandwidth     = var.slb_bandwidth

  established_timeout = var.slb_established_timeout

  primary_zone_id   = local.primary_zone_id
  secondary_zone_id = local.secondary_zone_id

  delete_protection = var.slb_delete_protection

  turms_gateway_instance_ids = var.create_turms_gateway ? module.turms_gateway.instance_ids : var.turms_gateway_instance_ids
}

module "mongodb" {
  source         = "./modules/mongodb"
  create_mongodb = var.create_mongodb

  instances          = local.mongodb_instances
  sharding_instances = local.mongodb_sharding_instances
}

module "redis" {
  source       = "./modules/redis"
  create_redis = var.create_redis

  instances = local.redis_instances
}

module "turms" {
  source       = "./modules/turms"
  create_turms = var.create_turms

  instance_count = var.turms_instance_count

  # Info
  host_name = var.turms_host_name
  ecs_name  = var.turms_ecs_name
  ecs_tags  = var.turms_ecs_tags

  # Charge
  internet_charge_type = var.turms_internet_charge_type
  period               = var.turms_period

  # Specification
  image_name_regex = var.turms_image_name_regex
  instance_type    = var.turms_instance_type

  # Network
  vpc_id            = module.vpc.vpc_id
  vswitch_id        = module.vpc.vswitch_ids[0]
  max_bandwidth_out = var.turms_max_bandwidth_out
  nic_type          = var.turms_nic_type

  # Disk
  disk_category = var.turms_disk_category
  disk_size     = var.turms_disk_size

  # Security
  security_group_name = var.turms_security_group_name
  security_group_tags = var.turms_security_group_tags
  key_pair_name       = var.turms_key_pair_name
  key_pair_tags       = var.turms_key_pair_tags

  # Protection
  delete_protection = var.turms_delete_protection

  # Turms Gateway
  turms_jvm_opts = var.turms_jvm_options
  turms_profile  = var.turms_profile

  # MongoDB
  config_mongodb_hosts                  = module.mongodb.config_mongodb_hosts
  config_mongodb_account_password       = module.mongodb.config_mongodb_account_password
  admin_mongodb_account_password        = module.mongodb.admin_mongodb_account_password
  admin_mongodb_hosts                   = module.mongodb.admin_mongodb_hosts
  user_mongodb_hosts                    = module.mongodb.user_mongodb_hosts
  user_mongodb_account_password         = module.mongodb.user_mongodb_account_password
  group_mongodb_hosts                   = module.mongodb.group_mongodb_hosts
  group_mongodb_account_password        = module.mongodb.group_mongodb_account_password
  conversation_mongodb_hosts            = module.mongodb.conversation_mongodb_hosts
  conversation_mongodb_account_password = module.mongodb.conversation_mongodb_account_password
  message_mongodb_hosts                 = module.mongodb.message_mongodb_hosts
  message_mongodb_account_password      = module.mongodb.message_mongodb_account_password

  # Redis
  session_redis_host              = module.redis.session_redis_host
  session_redis_account_name      = module.redis.session_redis_account_name
  session_redis_account_password  = module.redis.session_redis_account_password
  location_redis_host             = module.redis.location_redis_host
  location_redis_account_name     = module.redis.location_redis_account_name
  location_redis_account_password = module.redis.location_redis_account_password
}

module "turms_gateway" {
  source               = "./modules/turms-gateway"
  create_turms_gateway = var.create_turms_gateway

  instance_count = var.turms_gateway_instance_count

  # Info
  host_name = var.turms_gateway_host_name
  ecs_name  = var.turms_gateway_ecs_name
  ecs_tags  = var.turms_gateway_ecs_tags

  # Charge
  internet_charge_type = var.turms_gateway_internet_charge_type
  period               = var.turms_gateway_period

  # Specification
  image_name_regex = var.turms_gateway_image_name_regex
  instance_type    = var.turms_gateway_instance_type

  # Network
  vpc_id            = module.vpc.vpc_id
  vswitch_id        = module.vpc.vswitch_ids[0]
  max_bandwidth_out = var.turms_gateway_max_bandwidth_out
  nic_type          = var.turms_gateway_nic_type

  # Disk
  disk_category = var.turms_gateway_disk_category
  disk_size     = var.turms_gateway_disk_size

  # Security
  security_group_name = var.turms_gateway_security_group_name
  security_group_tags = var.turms_gateway_security_group_tags
  key_pair_name       = var.turms_gateway_key_pair_name
  key_pair_tags       = var.turms_key_pair_tags

  # Protection
  delete_protection = var.turms_gateway_delete_protection

  # Turms Gateway
  turms_gateway_jvm_opts = var.turms_gateway_jvm_options
  turms_gateway_profile  = var.turms_gateway_profile

  # MongoDB
  config_mongodb_hosts            = module.mongodb.config_mongodb_hosts
  config_mongodb_account_password = module.mongodb.config_mongodb_account_password
  user_mongodb_hosts              = module.mongodb.user_mongodb_hosts
  user_mongodb_account_password   = module.mongodb.user_mongodb_account_password

  # Redis
  session_redis_host              = module.redis.session_redis_host
  session_redis_account_name      = module.redis.session_redis_account_name
  session_redis_account_password  = module.redis.session_redis_account_password
  location_redis_host             = module.redis.location_redis_host
  location_redis_account_name     = module.redis.location_redis_account_name
  location_redis_account_password = module.redis.location_redis_account_password
}

module "turms_admin" {
  source             = "./modules/turms-admin"
  create_turms_admin = var.create_turms_admin

  # Info
  host_name = var.turms_admin_host_name
  ecs_name  = var.turms_admin_ecs_name
  ecs_tags  = var.turms_admin_ecs_tags

  # Charge
  internet_charge_type = var.turms_admin_internet_charge_type
  period               = var.turms_admin_period

  # Specification
  image_name_regex = var.turms_admin_image_name_regex
  instance_type    = var.turms_admin_instance_type

  # Network
  vpc_id            = module.vpc.vpc_id
  vswitch_id        = module.vpc.vswitch_ids[0]
  max_bandwidth_out = var.turms_admin_max_bandwidth_out
  nic_type          = var.turms_admin_nic_type

  # Disk
  disk_category = var.turms_admin_disk_category
  disk_size     = var.turms_admin_disk_size

  # Security
  security_group_name = var.turms_admin_security_group_name
  security_group_tags = var.turms_admin_security_group_tags
  key_pair_name       = var.turms_admin_key_pair_name
  key_pair_tags       = var.turms_key_pair_tags

  # Protection
  delete_protection = var.turms_admin_delete_protection

}