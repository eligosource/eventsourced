/*
 * Copyright 2012-2013 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.core

import akka.actor._

import ProcessorSpec._

class ProcessorSpec extends EventsourcingSpec[Fixture] {
  "A eventsourced processor" must {
    "receive a timestamp message" in { fixture =>
      import fixture._
      result[Long](processor(1))(Message("foo")) must be > (0L)
    }
    "not have an id < 1" in { fixture =>
      import fixture._
      intercept[InvalidProcessorIdException](processor(0))
      intercept[InvalidProcessorIdException](processor(-1))
    }
  }
}

object ProcessorSpec {
  class Fixture extends EventsourcingFixture[Long] {
    def processor(pid: Int = 1) = extension.processorOf(Props(new Processor with Receiver with Eventsourced { val id = pid } ))
  }

  class Processor extends Actor { this: Receiver =>
    def receive = {
      case "foo" => sender ! message.timestamp
    }
  }
}
