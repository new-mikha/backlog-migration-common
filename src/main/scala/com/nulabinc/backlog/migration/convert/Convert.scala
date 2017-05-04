package com.nulabinc.backlog.migration.convert

/**
  * @author uchida
  */
object Convert {

  def toBacklog[A, B](a: A)(implicit w: Writes[A, B]): B = w.writes(a)

}