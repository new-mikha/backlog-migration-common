package com.nulabinc.backlog.migration.common.dsl

import com.nulabinc.backlog.migration.common.domain.Types.AnyId
import com.nulabinc.backlog.migration.common.persistence.sqlite.DBIOTypes._
import monix.reactive.Observable

trait StoreDSL[F[_]] {
  def read[A](a: DBIORead[A]): F[A]
  def write[A](a: DBIOWrite): F[AnyId]
  def stream[A](a: DBIOStream[A]): F[Observable[A]]
}

