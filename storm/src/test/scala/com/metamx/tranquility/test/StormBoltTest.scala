/*
 * Tranquility.
 * Copyright 2013, 2014, 2015  Metamarkets Group, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.metamx.tranquility.test

import backtype.storm.Config
import backtype.storm.task.IMetricsContext
import backtype.storm.topology.TopologyBuilder
import com.google.common.collect.Lists
import com.metamx.common.scala.Logging
import com.metamx.common.scala.Predef._
import com.metamx.tranquility.beam.Beam
import com.metamx.tranquility.storm.BeamBolt
import com.metamx.tranquility.storm.BeamFactory
import com.metamx.tranquility.storm.common.SimpleKryoFactory
import com.metamx.tranquility.storm.common.SimpleSpout
import com.metamx.tranquility.storm.common.StormRequiringSuite
import com.metamx.tranquility.test.common.CuratorRequiringSuite
import com.metamx.tranquility.test.common.JulUtils
import com.twitter.util.Future
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.URLClassLoader
import java.{util => ju}
import org.scala_tools.time.Imports._
import org.scalatest.FunSuite
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

class SimpleBeam extends Beam[SimpleEvent]
{
  def propagate(events: Seq[SimpleEvent]) = {
    SimpleBeam.buffer ++= events
    Future.value(events.size)
  }

  def close() = Future.Done
}

object SimpleBeam
{
  val buffer = new ArrayBuffer[SimpleEvent] with mutable.SynchronizedBuffer[SimpleEvent]

  def sortedBuffer = buffer.sortBy(_.ts.millis).toList
}

class SimpleBeamFactory extends BeamFactory[SimpleEvent]
{
  def makeBeam(conf: ju.Map[_, _], metrics: IMetricsContext) = new SimpleBeam
}

class StormBoltTest extends FunSuite with CuratorRequiringSuite with StormRequiringSuite with Logging
{

  JulUtils.routeJulThroughSlf4j()

  test("Storm BeamBolt") {
    withLocalCurator {
      curator =>
        withLocalStorm {
          storm =>
            val inputs = Seq(
              new SimpleEvent(new DateTime("2010-01-01T02:03:04Z"), Map("hey" -> "what")),
              new SimpleEvent(new DateTime("2010-01-01T02:03:05Z"), Map("foo" -> "bar"))
            ).sortBy(_.ts.millis)
            val spout = SimpleSpout.create(inputs)
            val conf = new Config
            conf.setKryoFactory(classOf[SimpleKryoFactory])
            val builder = new TopologyBuilder
            builder.setSpout("events", spout)
            builder.setBolt("beam", new BeamBolt[SimpleEvent](new SimpleBeamFactory)).shuffleGrouping("events")
            storm.submitTopology("test", conf, builder.createTopology())
            val start = System.currentTimeMillis()
            while (SimpleBeam.sortedBuffer != inputs && System.currentTimeMillis() < start + 300000) {
              Thread.sleep(2000)
            }
            assert(SimpleBeam.sortedBuffer === inputs)
        }
    }
  }

}
