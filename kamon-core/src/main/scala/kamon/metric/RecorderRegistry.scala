package kamon
package metric

import java.util.concurrent.atomic.AtomicReference

import com.typesafe.config.Config
import kamon.metric.instrument.InstrumentFactory

import scala.collection.concurrent.TrieMap


trait RecorderRegistry {
  def shouldTrack(entity: Entity): Boolean
  def getRecorder(entity: Entity): EntityRecorder
  def removeRecorder(entity: Entity): Boolean
}

class RecorderRegistryImpl(initialConfig: Config) extends RecorderRegistry {
  private val instrumentFactory = new AtomicReference[InstrumentFactory]()
  private val entityFilter = new AtomicReference[EntityFilter]()
  private val entities = TrieMap.empty[Entity, EntityRecorder with EntitySnapshotProducer]

  reconfigure(initialConfig)


  override def shouldTrack(entity: Entity): Boolean =
    entityFilter.get().accept(entity)

  override def getRecorder(entity: Entity): EntityRecorder =
    entities.atomicGetOrElseUpdate(entity, new DefaultEntityRecorder(entity, instrumentFactory.get()))

  override def removeRecorder(entity: Entity): Boolean =
    entities.remove(entity).nonEmpty

  private[kamon] def reconfigure(config: Config): Unit = synchronized {
    instrumentFactory.set(InstrumentFactory.fromConfig(config))
    entityFilter.set(EntityFilter.fromConfig(config))
  }

  private[kamon] def snapshot(): Seq[EntitySnapshot] = {
    entities.values.map(_.snapshot()).toSeq
  }
}








