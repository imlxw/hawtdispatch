/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.hawtdispatch.internal;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.lang.String.*;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class NioManager {

    /**
     * Set the "hawtdispatch.workaround-select-spin" System property to "true" if your
     * seeing the 100% CPU usage in the Selector.select() call.  This enables a
     * workaround for a JVM/OS bug documented at http://bugs.sun.com/view_bug.do?bug_id=6693490
     */
    final SelectStrategy selectStrategy = Boolean.getBoolean("hawtdispatch.workaround-select-spin") ? new WorkAroundSelectSpin() :  new SelectStrategy();

    /**
     * Handles doing a select on a selector.  Allows us to change
     * the implementation to work around bugs in some JVMs.
     */
    class SelectStrategy {
        public int select(long timeout) throws IOException {
            int rc=0;
            if (timeout == -1) {
                trace("entered blocking select");
                rc = selector.select();
                trace("exited blocking select");
            } else {
                trace("entered blocking select with timeout");
                rc = selector.select(timeout);
                trace("exited blocking select with timeout");
            }
            return rc;
        }
    }

    /**
     * Workaround for the selector spin bug.
     */
    class WorkAroundSelectSpin extends SelectStrategy {
        int spins;

        /**
         * Was a wakeup() issued after we entered the select() ??
         * @return
         */
        public boolean wakeupPending() {
            return selectCounter != wakeupCounter;
        }

        /**
         * Detects the buggy condition and works around by
         * re-creating the selector when the bug is triggered.
         */
        @Override
        public int select(long timeout) throws IOException {

            if( selector.keys().isEmpty() || ( timeout > 0 || timeout < 100) ) {
                // we can't detect spin in this case
                return super.select(timeout);
            } else {

                long start = System.nanoTime();
                int selected = super.select(timeout);

                // Did the select return immediately with 0 selections? 
                if (selected == 0 && !wakeupPending() ) {
                    long end = System.nanoTime();
                    long duration = TimeUnit.NANOSECONDS.toMillis(end-start);
                    if( duration < 50 ) {
                        spins++;
                        if(spins > 10) {
                            reset();
                            spins=0;
                        }
                    } else {
                        spins=0; // not spinning... reset the spin counter
                    }
                } else {
                    spins=0; // not spinning... reset the spin counter
                }
                return selected;
            }
        }

        /**
         * Called when the buggy condition is detected.
         */
        private void reset() throws IOException {
            trace("Selector spin detected... resetting the selector");
            Selector nextSelector = Selector.open();
            for (SelectionKey key : selector.keys()) {
                NioAttachment attachment = (NioAttachment) key.attachment();
                if( key.isValid() ) {
                    try {
                        SelectionKey nextKey = key.channel().register(nextSelector, key.interestOps());

                        // Associate the new key with source objects.
                        nextKey.attach(attachment);
                        for( NioDispatchSource source: attachment.sources ) {
                            NioDispatchSource.KeyState state = source.keyState.get();
                            if( state!=null ) {
                                state.key = nextKey;
                            }
                        }

                    } catch (IOException e ) {
                        // channel could have closed out
                        attachment.cancel(key);
                    }
                } else {
                    // perhaps key was canceled.
                    attachment.cancel(key);
                }
            }
            // Close out the old selector and set it to the new one.
            selector.close();
            selector = nextSelector;
        }
    }


    private Selector selector;
    volatile protected int wakeupCounter;
    volatile protected int selectCounter;

    volatile protected boolean selecting;

    public NioManager() throws IOException {
        this.selector = Selector.open();
    }

    Selector getSelector() {
        return selector;
    }

    public boolean isSelecting() {
        return selecting;
    }

    /**
     * Subclasses may override this to provide an alternative wakeup mechanism.
     */
    public void wakeup() {
        ++wakeupCounter;
        selector.wakeup();
    }

    /**
     * Selects ready sources, potentially blocking. If wakeup is called during
     * select the method will return.
     * 
     * @param timeout
     *            A negative value cause the select to block until a source is
     *            ready, 0 will do a non blocking select. Otherwise the select
     *            will block up to timeout in milliseconds waiting for a source
     *            to become ready.
     * @throws IOException
     */
    public int select(long timeout) throws IOException {
        try {
            if (timeout == 0) {
                selector.selectNow();
            } else {
                selecting=true;
                try {
                    if( selectCounter == wakeupCounter) {
                        selectStrategy.select(timeout);
                    }
                } finally {
                    selectCounter = wakeupCounter;
                    selecting=false;
                }
            }
        } catch (CancelledKeyException e) {
        }
        return processSelected();
    }

    private int processSelected() {
        
        if( selector.keys().isEmpty() ) {
            return 0;
        }

        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        int size = selectedKeys.size();
        if (size!=0) {
            trace("selected: %d",size);

            // Copy the key set.. to avoid getting ConcurrentModificationException
            // as it may get changed once we start processing the IO events.
            ArrayList<SelectionKey> copy = new ArrayList<SelectionKey>(selector.selectedKeys());
            selector.selectedKeys().clear();

            // Walk the set of ready keys servicing each ready context:
            for (SelectionKey key : copy) {
                if (key.isValid()) {
                    try {
                        key.interestOps(key.interestOps() & ~key.readyOps());
                        ((NioAttachment) key.attachment()).selected(key);
                    } catch (CancelledKeyException e) {
                        ((NioAttachment) key.attachment()).cancel(key);
                    }
                } else {
                    ((NioAttachment) key.attachment()).cancel(key);
                }
            }
        }
        return size;
    }

    public void shutdown() throws IOException {
        for (SelectionKey key : selector.keys()) {
            NioDispatchSource source = (NioDispatchSource) key.attachment();
            source.cancel();
        }
        selector.close();
    }

    private final boolean TRACE = false;
    private final LinkedList<String> traces = new LinkedList<String>();
    protected void trace(String str, Object... args) {
        if (TRACE) {
            String msg = System.currentTimeMillis()+": "+format(str, args)+"\n";
            synchronized(traces) {
                traces.add(msg);
                if( traces.size() > 100 ) {
                    traces.removeFirst();
                }
            }
        }
    }

}
