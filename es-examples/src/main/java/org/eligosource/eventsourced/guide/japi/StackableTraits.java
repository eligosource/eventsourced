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

import java.io.File;

import akka.actor.*;

import org.eligosource.eventsourced.core.*;
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps;

public class StackableTraits {
    public static class Processor extends UntypedEventsourcedEmitter {
        private int counter = 0;

        @Override
        public int id() {
            return 1;
        }

        @Override
        public void onReceive(Object event) throws Exception {
            counter = counter + 1;
            System.out.println(String.format("[processor] event = %s (%d)", event, counter));
            emitter(1).sendEvent(String.format("processed %d event messages so far", counter), getSelf());
        }
    }

    public static class Destination extends UntypedConfirmingReceiver {
        @Override
        public void onReceive(Object event) throws Exception {
            System.out.println(String.format("[destination] event = %s", event));
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("guide");

        final ActorRef journal = LeveldbJournalProps.create(new File("target/guide-2-java")).withNative(false).createJournal(system);
        final EventsourcingExtension extension = EventsourcingExtension.create(system, journal);

        final ActorRef destination = system.actorOf(Props.create(Destination.class));
        final ActorRef channel = extension.channelOf(DefaultChannelProps.create(1, destination), system);
        final ActorRef processor = extension.processorOf(Props.create(Processor.class), system);

        extension.recover();

        processor.tell(Message.create("foo"), null);

        Thread.sleep(1000);
        system.shutdown();
    }
}
