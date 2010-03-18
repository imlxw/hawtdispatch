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
import java.util.Iterator;
import java.util.Set;

import static java.lang.String.*;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class NioManager {
    
    private final boolean DEBUG = false;
    private final Selector selector;
    volatile protected int selectCounter;
    volatile protected boolean selecting;

    public NioManager() throws IOException {
        this.selector = Selector.open();
    }

    Selector getSelector() {
        return selector;
    }

    /**
     * Subclasses may override this to provide an alternative wakeup mechanism.
     */
    public void wakeup() {
        debug("wakeup");
        int was = selectCounter;
        while( selecting && was==selectCounter) {
            selector.wakeup();
        }
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
            if (timeout == -1) {
                selecting=true;
                try {
                    debug("entered blocking select");
                    selector.select();
                    debug("exited blocking select");
                } finally {
                    selectCounter++;
                    selecting=false;
                }
            } else if (timeout > 0) {
                selecting=true;
                try {
                    debug("entered blocking select with timeout");
                    selector.select(timeout);
                    debug("exited blocking select with timeout");
                } finally {
                    selectCounter++;
                    selecting=false;
                }
            } else {
                selector.selectNow();
            }
        } catch (CancelledKeyException ignore) {
            return 0;
        }
        return processSelected();
    }

    private int processSelected() {
        
        if( selector.keys().isEmpty() ) {
            return 0;
        }

        // Walk the set of ready keys servicing each ready context:
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        int size = selectedKeys.size();
        if (size!=0) {
            debug("selected: %d",size);
            for (Iterator<SelectionKey> i = selectedKeys.iterator(); i.hasNext();) {
                SelectionKey key = i.next();
                i.remove();
                if (key.isValid()) {
                    key.interestOps(key.interestOps() & ~key.readyOps());
                    ((NioAttachment) key.attachment()).selected(key);
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

    protected void debug(String str, Object... args) {
        if (DEBUG) {
            System.out.println(format("[DEBUG] NioManager %0#10x: ", System.identityHashCode(this))+format(str, args));
        }
    }

    protected void debug(Throwable thrown, String str, Object... args) {
        if (DEBUG) {
            if (str != null) {
                debug(str, args);
            }
            if (thrown != null) {
                thrown.printStackTrace();
            }
        }
    }

}