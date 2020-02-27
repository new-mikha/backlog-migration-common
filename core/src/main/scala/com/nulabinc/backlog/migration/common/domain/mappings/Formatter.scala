package com.nulabinc.backlog.migration.common.domain.mappings

trait Formatter[A] {
  def format(value: A): (String, String)
}
