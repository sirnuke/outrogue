package com.degrendel.outrogue.common

interface Level
{
  fun getSquare(x: Int, y: Int): Square

  fun isNavigable(coordinate: Coordinate): Boolean
}