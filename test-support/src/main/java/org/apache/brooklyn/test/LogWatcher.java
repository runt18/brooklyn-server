/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.test;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.testng.Assert.assertFalse;

import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.Appender;

/**
 * Testing utility that registers an appender to watch a given logback logger, and records events 
 * that match a given predicate.
 * 
 * Callers should first call {@link #start()}, and must call {@link #close()} to de-register the
 * appender (doing this in a finally block).
 */
@Beta
public class LogWatcher implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(LogWatcher.class);

    public static class EventPredicates {
        public static Predicate<ILoggingEvent> containsMessage(final String expected) {
            return new Predicate<ILoggingEvent>() {
                @Override public boolean apply(ILoggingEvent input) {
                    if (input == null) return false;
                    String msg = input.getFormattedMessage();
                    return (msg != null) && msg.contains(expected);
                }
            };
        }
    
        public static Predicate<ILoggingEvent> containsExceptionStackLine(final Class<?> clazz, final String methodName) {
            return new Predicate<ILoggingEvent>() {
                @Override public boolean apply(ILoggingEvent input) {
                    IThrowableProxy throwable = (input != null) ? input.getThrowableProxy() : null;
                    if (throwable != null) {
                        for (StackTraceElementProxy line : throwable.getStackTraceElementProxyArray()) {
                            if (line.getStackTraceElement().getClassName().contains(clazz.getSimpleName())
                                    && line.getStackTraceElement().getMethodName().contains(methodName)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
            };
        }
    }
    
    private final List<ILoggingEvent> events = Collections.synchronizedList(Lists.<ILoggingEvent>newLinkedList());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ch.qos.logback.classic.Level loggerLevel;
    private final ch.qos.logback.classic.Logger watchedLogger;
    private final Appender<ILoggingEvent> appender;
    private volatile Level origLevel;
    
    @SuppressWarnings("unchecked")
    public LogWatcher(String loggerName, ch.qos.logback.classic.Level loggerLevel, final Predicate<? super ILoggingEvent> filter) {
        this.watchedLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(checkNotNull(loggerName, "loggerName"));
        this.loggerLevel = checkNotNull(loggerLevel, "loggerLevel");
        this.appender = Mockito.mock(Appender.class);
        
        Mockito.when(appender.getName()).thenReturn("MOCK");
        Answer<Void> answer = new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ILoggingEvent event = invocation.getArgumentAt(0, ILoggingEvent.class);
                if (event != null && filter.apply(event)) {
                    events.add(event);
                }
                LOG.trace("level="+event.getLevel()+"; event="+event+"; msg="+event.getFormattedMessage());
                return null;
            }
        };
        Mockito.doAnswer(answer).when(appender).doAppend(Mockito.<ILoggingEvent>any());
    }
    
    public void start() {
        checkState(!closed.get(), "Cannot start LogWatcher after closed");
        origLevel = watchedLogger.getLevel();
        watchedLogger.setLevel(loggerLevel);
        watchedLogger.addAppender(appender);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (watchedLogger != null) {
                if (origLevel != null) watchedLogger.setLevel(origLevel);
                watchedLogger.detachAppender(appender);
            }
        }
    }
    
    public void assertHasEvent() {
        assertFalse(events.isEmpty());
    }

    public void assertHasEventEventually() {
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertFalse(events.isEmpty());
            }});
    }

    public List<ILoggingEvent> getEvents() {
        synchronized (events) {
            return ImmutableList.copyOf(events);
        }
    }
}
