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
package org.eligosource.eventsourced.core;

import akka.actor.ActorRef;
import akka.japi.Procedure;

public class BehaviorSpecProcessor extends UntypedEventsourcedReceiver {

    private ActorRef destination;

    private Procedure<Object> changed = new Procedure<Object>() {
        @Override
        public void apply(Object message) {
            if (message.equals("bar")) {
                destination.tell(String.format("bar (%d)", sequenceNr()), getSelf());
                unbecome();
            }
        }
    };

    public BehaviorSpecProcessor(ActorRef destination) {
        this.destination = destination;
    }

    @Override
    public int id() {
        return 1;
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message.equals("foo")) {
            destination.tell(String.format("foo (%d)", sequenceNr()), getSelf());
            become(changed);
        } else if (message.equals("baz")) {
            destination.tell(String.format("baz (%d)", sequenceNr()), getSelf());
        }
    }

}
