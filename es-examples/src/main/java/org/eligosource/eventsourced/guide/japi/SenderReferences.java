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
package org.eligosource.eventsourced.guide.japi;

import static akka.pattern.Patterns.ask;

import java.io.File;

import scala.concurrent.Future;

import akka.actor.*;
import akka.dispatch.OnSuccess;
import akka.japi.Util;

import org.eligosource.eventsourced.core.*;
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps;

public class SenderReferences {
    public static class Processor extends UntypedEventsourcedActor {
        private ActorRef destination;
        private int counter = 0;

        public Processor(ActorRef destination) {
            this.destination = destination;
        }

        @Override
        public int id() {
            return 1;
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Message) {
                Message msg = (Message)message;
                counter = counter + 1;
                System.out.println(String.format("[processor] event = %s (%d)", msg.event(), counter));
                destination.forward(msg.withEvent(String.format("processed %d event messages so far", counter)), getContext());
            }
        }
    }

    public static class Destination extends UntypedActor {
        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Message) {
                Message msg = (Message)message;
                System.out.println(String.format("[destination] event = %s", msg.event()));
                msg.confirm(true);
                getSender().tell(String.format("done processing event = %s", msg.event()), getSelf());
            }
        }
    }

    public final static class PrintResult<T> extends OnSuccess<T> {
        @Override
        public final void onSuccess(T t) {
            System.out.println(t);
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("guide");

        final ActorRef journal = LeveldbJournalProps.create(new File("target/guide-3-java")).withNative(false).createJournal(system);
        final EventsourcingExtension extension = EventsourcingExtension.create(system, journal);

        final ActorRef destination = system.actorOf(Props.create(Destination.class));
        final ActorRef channel = extension.channelOf(DefaultChannelProps.create(1, destination), system);
        final ActorRef processor = extension.processorOf(Props.create(Processor.class, channel), system);

        extension.recover();

        Future<String> futureResult = ask(processor, Message.create("foo"), 5000L).mapTo(Util.classTag(String.class));
        futureResult.onSuccess(new PrintResult<String>(), system.dispatcher());

        Thread.sleep(1000);
        system.shutdown();
    }

}
