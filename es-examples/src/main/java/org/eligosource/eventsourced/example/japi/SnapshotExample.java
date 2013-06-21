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
package org.eligosource.eventsourced.example.japi;

import static akka.pattern.Patterns.ask;

import java.io.File;
import java.io.Serializable;

import akka.actor.*;
import akka.dispatch.OnComplete;
import akka.japi.Util;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.eligosource.eventsourced.core.*;
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps;

public class SnapshotExample {
    public static class Processor extends UntypedEventsourcedReceiver {
        private int counter = 0;

        @Override
        public int id() {
            return 1;
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Increment) {
                Increment inc = (Increment)message;
                counter += inc.by;
                System.out.println(String.format("incremented counter by %d to %d (snr = %d)",
                        inc.by, counter, sequenceNr()));
            } else if (message instanceof SnapshotRequest) {
                SnapshotRequest sr = (SnapshotRequest)message;
                sr.process(counter, getContext());
                System.out.println(String.format("processed snapshot request for ctr = %d (snr = %d)",
                        counter, sr.sequenceNr()));
            } else if (message instanceof SnapshotOffer) {
                SnapshotOffer so = (SnapshotOffer)message;
                counter = (Integer)so.snapshot().state();
                System.out.println(String.format("accepted snapshot offer for ctr = %d (snr = %d time = %d)",
                        counter, so.snapshot().sequenceNr(), so.snapshot().timestamp()));
            }
        }
    }

    public static class Increment implements Serializable {
        private int by;

        public Increment(int by) {
            this.by = by;
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("guide");

        FileSystem fs = FileSystem.getLocal(new Configuration());

        final ActorRef journal = LeveldbJournalProps.create(new File("target/snapshots-java")).withNative(false).withSnapshotFilesystem(fs).createJournal(system);
        final EventsourcingExtension extension = EventsourcingExtension.create(system, journal);
        final ActorRef processor = extension.processorOf(Props.create(Processor.class), system);

        extension.recover(extension.getReplayParams().allWithSnapshot());

        processor.tell(Message.create(new Increment(1)), null);
        processor.tell(Message.create(new Increment(2)), null);

        ask(processor, SnapshotRequest.get(), 5000L).mapTo(Util.classTag(SnapshotSaved.class)).onComplete(new OnComplete<SnapshotSaved>() {
            public void onComplete(Throwable failure, SnapshotSaved result) {
                if (failure != null) {
                    System.out.println(String.format("snapshotting failed: %s", failure.getMessage()));
                } else {
                    System.out.println(String.format("snapshotting succeeded: pid = %d snr = %d time = %d",
                            result.processorId(), result.sequenceNr(), result.timestamp()));
                }
            }
        }, system.dispatcher());

        processor.tell(Message.create(new Increment(3)), null);

        Thread.sleep(1000);
        system.shutdown();
    }
}
