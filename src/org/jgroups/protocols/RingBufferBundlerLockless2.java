package org.jgroups.protocols;

/**
 * A bundler based on a lockless ring buffer
 * @author Bela Ban
 * @since  4.0
 */

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.Message;
import org.jgroups.util.*;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * This bundler adds all (unicast or multicast) messages to a ring buffer until max size has been exceeded, but does
 * send messages immediately when no other messages are available. If no space is available, a message by a sender
 * thread is simply dropped, as it will get retransmitted anyway. This makes this implementation completely non-blocking.
 * https://issues.jboss.org/browse/JGRP-1540
 */
public class RingBufferBundlerLockless2 extends BaseBundler {
    protected Message[]                              buf;
    protected final AtomicInteger   read_index=new PaddedAtomicInteger(0); // shared by reader and writers (reader only writes it)
    protected int                   ri=0; // only used by reader
    protected final AtomicInteger   write_index=new PaddedAtomicInteger(1);
    protected final AtomicLong      accumulated_bytes=new PaddedAtomicLong(0);
    protected final AtomicInteger   num_threads=new PaddedAtomicInteger(0);
    protected final AtomicBoolean   unparking=new PaddedAtomicBoolean(false);
    protected Runner                bundler_thread;
    protected final Runnable        run_function=this::readMessages;
    protected static final String   THREAD_NAME=RingBufferBundlerLockless2.class.getSimpleName();
    public static final Message     NULL_MSG=new Message(false); // public for unit test



    public RingBufferBundlerLockless2() {
        this(1024);
    }


    public RingBufferBundlerLockless2(int capacity) {
        buf=new Message[Util.getNextHigherPowerOfTwo(capacity)]; // for efficient % (mod) op
    }

    public int                        readIndex()             {return read_index.get();}
    public int                        writeIndex()            {return write_index.get();}
    public RingBufferBundlerLockless2 reset()                 {ri=0; read_index.set(0); write_index.set(1); return this;}

    public int getBufferSize() {
        return _size(read_index.get(), write_index.get());
    }

    protected int _size(int ri, int wi) {
        return ri < wi? wi-ri-1 : buf.length - ri -1 +wi;
    }

    public void init(TP transport) {
        super.init(transport);
        bundler_thread=new Runner(transport.getThreadFactory(), THREAD_NAME, run_function, this::reset);
    }

    public void start() {
        bundler_thread.start();
    }

    public void stop() {
        bundler_thread.stop();
    }


    public void send(Message msg) throws Exception {
        if(msg == null)
            throw new IllegalArgumentException("message must not be null");

        num_threads.incrementAndGet();

        int tmp_write_index=getWriteIndex(read_index.get());
        // System.out.printf("[%d] tmp_write_index=%d\n", Thread.currentThread().getId(), tmp_write_index);
        if(tmp_write_index == -1) {
            log.warn("buf is full: %s\n", toString());
            unparkIfNeeded(0);
            return;
        }
        buf[tmp_write_index]=msg;
        unparkIfNeeded(msg.size());
    }

    protected void unparkIfNeeded(long size) {
        long acc_bytes=size > 0? accumulated_bytes.addAndGet(size) : accumulated_bytes.get();
        boolean size_exceeded=acc_bytes >= transport.getMaxBundleSize() && accumulated_bytes.compareAndSet(acc_bytes, 0);
        boolean no_other_threads=num_threads.decrementAndGet() == 0;

        boolean unpark=size_exceeded || no_other_threads;

        // only 2 threads at a time should do this in parallel (1st cond and 2nd cond)
        if(unpark && unparking.compareAndSet(false, true)) {
            Thread thread=bundler_thread.getThread();
            if(thread != null)
                LockSupport.unpark(thread);
            unparking.set(false);
        }
    }


    protected int getWriteIndex(int current_read_index) {
        for(;;) {
            int wi=write_index.get();
            int next_wi=index(wi + 1);
            if(next_wi == current_read_index)
                return -1;
            if(write_index.compareAndSet(wi, next_wi))
                // if(write_updater.compareAndSet(this, wi, next_wi))
                return wi;
        }
    }



    public int _readMessages() {
        int wi=write_index.get();
        if(index(ri+1) == wi)
            return 0;
        int sent_msgs=sendBundledMessages(buf, ri, wi);
        advanceReadIndex(wi); // publish read_index into main memory
        return sent_msgs;
    }



    protected boolean advanceReadIndex(final int wi) {
        boolean advanced=false;
        for(int i=increment(ri); i != wi; i=increment(i)) {
            if(buf[i] != NULL_MSG)
                break;
            buf[i]=null;
            ri=i;
            advanced=true;
        }
        if(advanced)
            read_index.set(ri); // publish the internal ri to read_index so writers get the update
        return advanced;
    }

    protected void readMessages() {
        _readMessages();
        LockSupport.park();
    }



    /** Read and send messages in range [read-index+1 .. write_index-1] */
    public int sendBundledMessages(final Message[] buf, final int read_index, final int write_index) {
        int       max_bundle_size=transport.getMaxBundleSize();
        byte[]    cluster_name=transport.cluster_name.chars();
        int       sent_msgs=0;

        for(int i=increment(read_index); i != write_index; i=increment(i)) {
            Message msg=buf[i];
            if(msg == NULL_MSG)
                continue;
            if(msg == null)
                break;

            Address dest=msg.dest();
            try {
                output.position(0);
                Util.writeMessageListHeader(dest, msg.src(), cluster_name, 1, output, dest == null);

                // remember the position at which the number of messages (an int) was written, so we can later set the
                // correct value (when we know the correct number of messages)
                int size_pos=output.position() - Global.INT_SIZE;
                int num_msgs=marshalMessagesToSameDestination(dest, buf, i, write_index, max_bundle_size);
                sent_msgs+=num_msgs;
                int current_pos=output.position();
                output.position(size_pos);
                output.writeInt(num_msgs);
                output.position(current_pos);
                transport.doSend(output.buffer(), 0, output.position(), dest);
            }
            catch(Exception ex) {
                log.error("failed to send message(s)", ex);
            }
        }
        return sent_msgs;
    }

    public String toString() {
        int tmp_ri=read_index.get(), tmp_wi=write_index.get(), size=_size(tmp_ri, tmp_wi);
        return String.format("read-index=%d write-index=%d size=%d cap=%d\n", tmp_ri, tmp_wi, size, buf.length);
    }



    // Iterate through the following messages and find messages to the same destination (dest) and write them to output
    protected int marshalMessagesToSameDestination(Address dest, Message[] buf, final int start_index, final int end_index,
                                                   int max_bundle_size) throws Exception {
        int num_msgs=0, bytes=0;
        for(int i=start_index; i != end_index; i=increment(i)) {
            Message msg=buf[i];
            if(msg != null && msg != NULL_MSG && Objects.equals(dest, msg.dest())) {
                long msg_size=msg.size();
                if(bytes + msg_size > max_bundle_size)
                    break;
                bytes+=msg_size;
                num_msgs++;
                buf[i]=NULL_MSG;
                msg.writeToNoAddrs(msg.src(), output, transport.getId());
            }
        }
        return num_msgs;
    }

    protected final int increment(int index) {return index+1 == buf.length? 0 : index+1;}
    protected final int index(int idx)     {return idx & (buf.length-1);}    // fast equivalent to %


    protected static int assertPositive(int value, String message) {
        if(value <= 0) throw new IllegalArgumentException(message);
        return value;
    }

}