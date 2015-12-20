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
package com.metamx.tranquility.druid

import com.metamx.common.Granularity
import com.metamx.common.scala.Logging
import com.metamx.common.scala.untyped._
import com.metamx.emitter.service.ServiceEmitter
import com.metamx.tranquility.beam.BeamMaker
import com.metamx.tranquility.beam.ClusteredBeamTuning
import com.metamx.tranquility.finagle.FinagleRegistry
import com.metamx.tranquility.typeclass.ObjectWriter
import com.metamx.tranquility.typeclass.Timestamper
import com.twitter.util.Await
import com.twitter.util.Future
import io.druid.data.input.impl.JSONParseSpec
import io.druid.data.input.impl.MapInputRowParser
import io.druid.data.input.impl.TimestampSpec
import io.druid.indexing.common.task.RealtimeIndexTask
import io.druid.indexing.common.task.Task
import io.druid.indexing.common.task.TaskResource
import io.druid.segment.indexing.granularity.UniformGranularitySpec
import io.druid.segment.indexing.DataSchema
import io.druid.segment.indexing.RealtimeIOConfig
import io.druid.segment.indexing.RealtimeTuningConfig
import io.druid.segment.realtime.FireDepartment
import io.druid.segment.realtime.firehose.ClippedFirehoseFactory
import io.druid.segment.realtime.firehose.EventReceiverFirehoseFactory
import io.druid.segment.realtime.firehose.TimedShutoffFirehoseFactory
import io.druid.segment.realtime.plumber.NoopRejectionPolicyFactory
import io.druid.segment.realtime.plumber.ServerTimeRejectionPolicyFactory
import io.druid.timeline.partition.LinearShardSpec
import org.joda.time.chrono.ISOChronology
import org.joda.time.DateTime
import org.joda.time.Interval
import org.joda.time.DateTimeConstants
import org.joda.time.Period
import org.scala_tools.time.Implicits._
import scala.util.Random

class DruidBeamMaker[A: Timestamper](
  config: DruidBeamConfig,
  location: DruidLocation,
  beamTuning: ClusteredBeamTuning,
  druidTuning: DruidTuning,
  rollup: DruidRollup,
  timestampSpec: TimestampSpec,
  finagleRegistry: FinagleRegistry,
  indexService: IndexService,
  emitter: ServiceEmitter,
  objectWriter: ObjectWriter[A]
) extends BeamMaker[A, DruidBeam[A]] with Logging
{
  private def taskObject(
    interval: Interval,
    availabilityGroup: String,
    firehoseId: String,
    partition: Int,
    replicant: Int
  ): Task =
  {
    val dataSource = location.dataSource
    val suffix = if (config.randomizeTaskId) {
      // Randomize suffix to allow creation of multiple tasks with the same parameters (useful for testing)
      val rand = Random.nextInt()
      val suffix0 = (0 until 8).map(i => (rand >> (i * 4)) & 0x0F).map(n => ('a' + n).toChar).mkString
      "_%s" format suffix0
    } else {
      ""
    }
    val taskId = "index_realtime_%s_%s_%s_%s%s" format(dataSource, interval.start, partition, replicant, suffix)
    val shutoffTime = interval.end + beamTuning.windowPeriod + config.firehoseGracePeriod
    val shardSpec = new LinearShardSpec(partition)
    val parser = {
      new MapInputRowParser(
        new JSONParseSpec(timestampSpec, rollup.dimensions.spec)
      )
    }
    new RealtimeIndexTask(
      taskId,
      new TaskResource(availabilityGroup, 1),
      new FireDepartment(
        new DataSchema(
          dataSource,
          parser,
          rollup.aggregators.toArray,
          new UniformGranularitySpec(beamTuning.segmentGranularity, rollup.indexGranularity, null)
        ),
        new RealtimeIOConfig(
          new ClippedFirehoseFactory(
            new TimedShutoffFirehoseFactory(
              new EventReceiverFirehoseFactory(
                location.environment.firehoseServicePattern format firehoseId,
                config.firehoseBufferSize,
                null
              ), shutoffTime
            ), interval
          ),
          null
        ),
        new RealtimeTuningConfig(
          druidTuning.maxRowsInMemory,
          druidTuning.intermediatePersistPeriod,
          beamTuning.windowPeriod,
          null,
          null,
          if (beamTuning.maxSegmentsPerBeam > 1) {
            // Experimental setting, can cause tasks to cover many hours. We still want handoff to occur mid-task,
            // so we need a non-noop rejection policy. Druid won't tell us when it rejects events due to its
            // rejection policy, so this breaks the contract of Beam.propagate telling the user when events are and
            // are not dropped. This is bad, so, only use this rejection policy when we absolutely need to.
            new ServerTimeRejectionPolicyFactory
          } else {
            new NoopRejectionPolicyFactory
          },
          druidTuning.maxPendingPersists,
          shardSpec,
          null,
          null,
          null,
          null,
          null
        )
      )
    )
  }

  override def newBeam(interval: Interval, partition: Int) = {
    require(
      beamTuning.segmentGranularity.widen(interval) == interval,
      "Interval does not match segmentGranularity[%s]: %s" format(beamTuning.segmentGranularity, interval)
    )
    val taskDuration = new Period(interval.start,interval.end + beamTuning.windowPeriod + config.firehoseGracePeriod);
    val availabilityGroup = DruidBeamMaker.generateBaseFirehoseId(
      location.dataSource,
      taskDuration,
      interval.start,
      partition
    )
    val futureTasks = for (replicant <- 0 until beamTuning.replicants) yield {
      val firehoseId = "%s-%04d" format(availabilityGroup, replicant)
      indexService.submit(taskObject(interval, availabilityGroup, firehoseId, partition, replicant)) map {
        taskId =>
          TaskPointer(taskId, firehoseId)
      }
    }
    val tasks = Await.result(Future.collect(futureTasks))
    new DruidBeam(
      interval,
      partition,
      tasks,
      location,
      config,
      finagleRegistry,
      indexService,
      emitter,
      objectWriter
    )
  }

  override def toDict(beam: DruidBeam[A]) = {
    // At some point we started allowing beams to cover more than one segment.
    // We'll attempt to be backwards compatible when possible.
    val canBeBackwardsCompatible = beamTuning.segmentBucket(beam.interval.start) == beam.interval
    Dict(
      "interval" -> beam.interval.toString(),
      "partition" -> beam.partition,
      "tasks" -> (beam.tasks map {
        task =>
          Dict("id" -> task.id, "firehoseId" -> task.serviceKey)
      })
    ) ++ (if (canBeBackwardsCompatible) Dict("timestamp" -> beam.interval.start.toString()) else Map.empty)
  }

  override def fromDict(d: Dict) = {
    val interval = if (d contains "interval") {
      new Interval(d("interval"), ISOChronology.getInstanceUTC)
    } else {
      // Backwards compatibility (see toDict).
      beamTuning.segmentBucket(new DateTime(d("timestamp"), ISOChronology.getInstanceUTC))
    }
    require(
      beamTuning.segmentGranularity.widen(interval) == interval,
      "Interval does not match segmentGranularity[%s]: %s" format(beamTuning.segmentGranularity, interval)
    )
    val partition = int(d("partition"))
    val tasks = if (d contains "tasks") {
      list(d("tasks")).map(dict(_)).map(d => TaskPointer(str(d("id")), str(d("firehoseId"))))
    } else {
      Seq(TaskPointer(str(d("taskId")), str(d("firehoseId"))))
    }
    new DruidBeam(
      interval,
      partition,
      tasks,
      location,
      config,
      finagleRegistry,
      indexService,
      emitter,
      objectWriter
    )
  }
}

object DruidBeamMaker
{
  def generateBaseFirehoseId(dataSource: String,taskDuration:Period, ts: DateTime, partition: Int) = {
    // Not only is this a nasty hack, it also only works if the RT task hands things off in a timely manner. We'd rather
    // use UUIDs, but this creates a ton of clutter in service discovery.
    //It may also break if task duration is changed across RT tasks

    val tsUtc = new DateTime(ts.millis, ISOChronology.getInstanceUTC)


    val cycleBucket = taskDuration match {
      case i if(i.toStandardSeconds.getSeconds < DateTimeConstants.SECONDS_PER_HOUR) => "%02d".format(tsUtc.minuteOfHour().get)
      case i if(i.toStandardSeconds().getSeconds < DateTimeConstants.SECONDS_PER_DAY) => "%02d-%02d" .format(tsUtc.hourOfDay().get,tsUtc.minuteOfHour().get)
      case i if(i.toStandardSeconds().getSeconds < DateTimeConstants.SECONDS_PER_DAY *28) => "%02d-%02d-%02d" .format(tsUtc.dayOfMonth().get,tsUtc.hourOfDay().get,tsUtc.minuteOfHour().get)
      case i if(i.toStandardSeconds.getSeconds < DateTimeConstants.SECONDS_PER_DAY*365) => "%02d-%02d-%02d-%02d" .format(tsUtc.monthOfYear().get,tsUtc.dayOfMonth().get,tsUtc.hourOfDay().get,tsUtc.minuteOfHour().get)
      case i if(i.toStandardSeconds.getSeconds > DateTimeConstants.SECONDS_PER_DAY*365) => "%02d-%02d-%02d-%02d-%02d" .format(tsUtc.yearOfCentury().get,tsUtc.monthOfYear().get,tsUtc.dayOfMonth().get,tsUtc.hourOfDay().get,tsUtc.minuteOfHour().get)
      case x => throw new IllegalArgumentException("No gross firehose id hack for task duration [%s]" format x)
    }

    "%s-%s-%04d".format(dataSource,cycleBucket, partition)
  }
}
