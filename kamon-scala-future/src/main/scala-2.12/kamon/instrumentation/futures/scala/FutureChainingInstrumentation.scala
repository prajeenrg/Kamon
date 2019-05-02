package kamon.instrumentation.futures.scala

import kamon.Kamon
import kamon.context.Storage.Scope
import kamon.instrumentation.context._
import kamon.instrumentation.futures.scala.CallbackRunnableRunInstrumentation.InternalState
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.api.instrumentation.bridge.Bridge
import kanela.agent.libs.net.bytebuddy.asm.Advice

import scala.util.Try

/**
  * Ensures that chained transformations on Scala Futures (e.g. future.map(...).flatmap(...)) will propagate the context
  * set on each transformation to the next transformation.
  */
class FutureChainingInstrumentation extends InstrumentationBuilder {

  /**
    * Captures the current context when a Try instance is created. Since Future's use a Try underneath to handle the
    * completed value we decided to instrument that instead. As a side effect, all Try instances are instrumented even
    * if they are not being used in a future, although that is just one extra field that will not be used or visible to
    * anybody who is not looking for it.
    */
  onSubTypesOf("scala.util.Try")
    .mixin(classOf[HasContext.Mixin])
    .advise(isConstructor, CaptureCurrentContextOnExit)

  /**
    * Ensures that if resolveTry returns a new Try instance, the captured context will be transferred to that the new
    * instance.
    */
  onType("scala.concurrent.impl.Promise")
    .advise(method("resolveTry"), CopyContextFromArgumentToResult)

  /**
    * Captures the scheduling timestamp when a CallbackRunnable is scheduled for execution and then uses the Context
    * from the completed value as the current Context while the Runnable is executed.
    */
  onType("scala.concurrent.impl.CallbackRunnable")
    .mixin(classOf[HasContext.Mixin])
    .mixin(classOf[HasTimestamp.Mixin])
    .bridge(classOf[InternalState])
    .advise(isConstructor, CaptureCurrentContextOnExit)
    .advise(method("run"), CallbackRunnableRunInstrumentation)
    .advise(method("executeWithValue"), CaptureCurrentTimestampOnEnter)

}

object CallbackRunnableRunInstrumentation {

  /**
    * Exposes access to the "value" member of "scala.concurrent.impl.CallbackRunnable".
    */
  trait InternalState {

    @Bridge("scala.util.Try value()")
    def valueBridge(): Try[_]

  }

  @Advice.OnMethodEnter(suppress = classOf[Throwable])
  def enter(@Advice.This runnable: HasContext with HasTimestamp with InternalState): Scope = {
    val timestamp = runnable.timestamp
    val valueContext = runnable.valueBridge().asInstanceOf[HasContext].context
    val context = if(valueContext.nonEmpty()) valueContext else runnable.context

    storeCurrentRunnableTimestamp(timestamp)
    Kamon.store(context)
  }

  @Advice.OnMethodExit(suppress = classOf[Throwable])
  def exit(@Advice.Enter scope: Scope): Unit = {
    clearCurrentRunnableTimestamp()
    scope.close()
  }

  /**
    * Exposes the scheduling timestamp of the currently running CallbackRunnable, if any. This timestamp should be
    * taken when the CallbackRunnable.executeWithValue method is called.
    */
  def currentRunnableScheduleTimestamp(): Option[Long] =
    Option(_schedulingTimestamp.get())

  /** Keeps track of the scheduling time of the CallbackRunnable currently running on this thread, if any */
  private val _schedulingTimestamp = new ThreadLocal[java.lang.Long]()

  private def storeCurrentRunnableTimestamp(timestamp: Long): Unit =
      _schedulingTimestamp.set(timestamp)

  private def clearCurrentRunnableTimestamp(): Unit =
    _schedulingTimestamp.remove()
}

object CopyContextFromArgumentToResult {

  @Advice.OnMethodExit(suppress = classOf[Throwable])
  def enter(@Advice.Argument(0) arg: Any, @Advice.Return result: Any): Any =
    result.asInstanceOf[HasContext].setContext(arg.asInstanceOf[HasContext].context)
}

object CopyCurrentContextToArgument {

  @Advice.OnMethodEnter(suppress = classOf[Throwable])
  def enter(@Advice.Argument(0) arg: Any): Unit =
    arg.asInstanceOf[HasContext].setContext(Kamon.currentContext())
}