/**
 * Copyright (C) 2012 FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.hawtdispatch

import java.util.HashSet
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.fusesource.hawtdispatch._

/**
 * <p>
 * A TaskTracker is used to track multiple async processing tasks and
 * call a callback once they all complete.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class TaskTracker(val name:String="unknown", var timeout: Long = 0) {

  private[this] val tasks = new HashSet[TrackedTask]()
  private[this] var _callback:Runnable = null
  val queue = createQueue("tracker: "+name);
  var done = false

  class TrackedTask(var name:Any) extends Task {
    def run = {
      remove(this)
    }
    override def toString = name.toString
  }

  def task(name:Any="unknown"):TrackedTask = {
    val rc = new TrackedTask(name)
    queue {
      assert(_callback==null || !tasks.isEmpty)
      tasks.add(rc)
    }
    return rc
  }

  def callback(handler: Runnable) {
    var start = System.currentTimeMillis
    queue {
      _callback = handler
      checkDone()
    }

    def schedualCheck(timeout:Long):Unit = {
      if( timeout>0 ) {
        queue.after(timeout, TimeUnit.MILLISECONDS) {
          if( !done ) {
            schedualCheck(onTimeout(start, tasks.toArray.toList.map(_.toString)))
          }
        }
      }
    }
    schedualCheck(timeout)
  }

  def callback(handler: =>Unit ) {
    callback(^(handler))
  }

  /**
   * Subclasses can override if they want to log the timeout event.
   * the method should return the next timeout value.  If 0, then
   * it will not check for further timeouts.
   */
  protected def onTimeout(started:Long, tasks: List[String]):Long = 0

  private def remove(r:Runnable) = queue {
    if( tasks.remove(r) ) {
      checkDone()
    }
  }

  private def checkDone() = {
    assert(!done)
    if( tasks.isEmpty && _callback!=null && !done ) {
      done = true
      _callback.run
    }
  }

  def await() = {
    val latch =new CountDownLatch(1)
    callback {
      latch.countDown
    }
    latch.await
  }

  def await(timeout:Long, unit:TimeUnit) = {
    val latch = new CountDownLatch(1)
    callback {
      latch.countDown
    }
    latch.await(timeout, unit)
  }

  override def toString = tasks.synchronized { name+" waiting on: "+tasks }
}

