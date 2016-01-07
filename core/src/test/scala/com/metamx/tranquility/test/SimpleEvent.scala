/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.metamx.tranquility.test

import com.fasterxml.jackson.annotation.JsonValue
import com.metamx.common.scala.untyped.Dict
import com.metamx.common.scala.untyped.long
import com.metamx.tranquility.test.DirectDruidTest.TimeColumn
import com.metamx.tranquility.typeclass.Timestamper
import org.scala_tools.time.Imports._

case class SimpleEvent(ts: DateTime, fields: Dict)
{
  @JsonValue
  def toMap = fields ++ Map(TimeColumn -> (ts.millis / 1000))
}

object SimpleEvent
{
  implicit val simpleEventTimestamper = new Timestamper[SimpleEvent] {
    def timestamp(a: SimpleEvent) = a.ts
  }

  def fromMap(d: Dict): SimpleEvent = {
    SimpleEvent(new DateTime(long(d(TimeColumn)) * 1000), d)
  }
}
