/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
#include <stdlib.h>
#include <unistd.h>
#include <assert.h>

#include "isa.h"
#include "debug.h"
#include "jni.h"
#include "teleProcess.h"
#include "teleNativeThread.h"

extern void gather_and_trace_threads(void);

static int trace = 0;  // set to non-zero to trace thread resumption/blocking
static int terminated = 0;

struct minios_regs *checked_get_regs(char *f, int threadId) {
    struct minios_regs *minios_regs;
    minios_regs = get_regs(threadId);
    if (minios_regs == NULL) {
        debug_println("guestvmXenNativeThread_%s: cannot get registers for thread %d", f, threadId);
        gather_and_trace_threads();
    }
    return minios_regs;
}

JNIEXPORT jlong JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeSuspend(JNIEnv *env, jclass c, jint threadId) {
    suspend(threadId);

    return 1;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeSingleStep(JNIEnv *env, jclass c, jint threadId) {
    int rc = single_step(threadId);
    return rc == 0;
}


JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeSetInstructionPointer(JNIEnv *env, jclass c, jint threadId, jlong ip) {
    return set_ip(threadId, ip);
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeReadRegisters(JNIEnv *env, jclass c, jlong threadId,
		jbyteArray integerRegisters, jint integerRegistersLength,
		jbyteArray floatingPointRegisters, jint floatingPointRegistersLength,
		jbyteArray stateRegisters, jint stateRegistersLength) {
	
    isa_CanonicalIntegerRegistersStruct canonicalIntegerRegisters;
    isa_CanonicalStateRegistersStruct canonicalStateRegisters;
    struct minios_regs *minios_regs;

    if (integerRegistersLength > sizeof(canonicalIntegerRegisters)) {
        debug_println("buffer for integer register data is too large");
        return false;
    }

    if (stateRegistersLength > sizeof(canonicalStateRegisters)) {
        debug_println("buffer for state register data is too large");
        return false;
    }

    minios_regs = checked_get_regs("nativeReadRegisters", threadId);
    if (minios_regs == NULL) {
    	return false;
    }

	isa_canonicalizeTeleIntegerRegisters(minios_regs, &canonicalIntegerRegisters);
	isa_canonicalizeTeleStateRegisters(minios_regs, &canonicalStateRegisters);

    (*env)->SetByteArrayRegion(env, integerRegisters, 0, integerRegistersLength, (void *) &canonicalIntegerRegisters);
    (*env)->SetByteArrayRegion(env, stateRegisters, 0, stateRegistersLength, (void *) &canonicalStateRegisters);
    return true;
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeReadByte(JNIEnv *env, jclass c, jint domainId, jlong address) {
    unsigned long long_val;
    unsigned long aligned_address = (address & (~7));
    int bit_offset = 8 * (address & 7);

    assert(sizeof(unsigned long) == 8);
    long_val = read_u64(aligned_address);

    return  (long_val >> bit_offset) & 0xFF;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeWriteByte(JNIEnv *env, jclass c, jint domainId, jlong address, jbyte jvalue) {
    unsigned long long_val;
    unsigned long aligned_address = (address & (~7));
    int bit_offset = 8 * (address & 7);
    unsigned long value = ((jvalue & 0xFFUL) << bit_offset);
    unsigned long mask =  (0xFFUL << bit_offset);


    assert(sizeof(unsigned long) == 8);
    /* read old 64bit value */
    long_val = read_u64(aligned_address);
    debug_println(" >> Read %lx at %lx, bit offset = %d, value=%lx, mask=%lx",
            long_val, aligned_address, bit_offset, value, mask);
    /* clear the byte we are interested in */
    long_val &= ~mask;
    /* or it with the new value */
    long_val |= value;

    debug_println(" >> Writing %lx at %lx, request for %x at %lx",
            long_val, aligned_address, value, address);
    write_u64(aligned_address, long_val);

    return 1;
}

JNIEXPORT jlong JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeReadInt(JNIEnv *env, jclass c, jint domainId, jlong address) {
    unsigned long long_val;
    unsigned long aligned_address = (address & (~7));
    unsigned long mask = (1UL << ((sizeof(unsigned int)) * 8)) - 1;
    int bit_offset = 8 * (address & 7);
    unsigned int value;

    assert(sizeof(unsigned long) == 8);
    long_val = read_u64(aligned_address);
    value = ((long_val >> bit_offset) & mask);
//    debug_println(" >> Request to read from %lx, read from %lx, value %lx, returning %x\n", address, aligned_address, long_val, value);

    return (jlong) value;
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeReadShort(JNIEnv *env, jclass c, jint domainId, jlong address) {
    unsigned long long_val;
    unsigned long aligned_address = (address & (~7));
    unsigned long mask = (1UL << ((sizeof(unsigned short)) * 8)) - 1;
    int bit_offset = 8 * (address & 7);
    unsigned short value;

    assert(sizeof(unsigned long) == 8);
    long_val = read_u64(aligned_address);
    value = ((long_val >> bit_offset) & mask);
//    debug_println(" >> Request to read short from %lx, read from %lx, value %lx, returning %x\n", address, aligned_address, long_val, value);

    return (jint) value;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeAttach(JNIEnv *env, jclass c, jint domainId) {
    debug_println("Calling do_attach on domId=%d", domainId);
    return db_attach(domainId);
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeDetach(JNIEnv *env, jclass c) {
    return db_detach();
}

void free_threads(struct thread *threads, int num)
{
    int i;

    for(i=0; i<num; i++)
        free(threads[i].name);

    free(threads);
}

static ThreadState_t toThreadState(struct thread_state* state) {
    if (state->is_aux1) {
        return TS_MONITOR_WAIT;
    }
    if (state->is_aux2) {
        return TS_NOTIFY_WAIT;
    }
    if (state->is_joining) {
        return TS_JOIN_WAIT;
    }
    if (state->is_sleeping) {
        return TS_SLEEPING;
    }
    return TS_SUSPENDED;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeGatherThreads(JNIEnv *env, jclass c, jobject threadSeq, jint domainId) {
    struct thread *threads;
    int num_threads, i;
    jmethodID gather_thread_method;
    jstring jname;

    threads = gather_threads(&num_threads);
    gather_thread_method = (*env)->GetStaticMethodID(env, c, "jniGatherThread", "(Lcom/sun/max/collect/AppendableSequence;ILjava/lang/String;IJJ)V");
    debug_ASSERT(gather_thread_method != NULL);
    for (i=0; i<num_threads; i++) {
     	struct thread_state state = threads[i].state;

        unsigned long stack_size, stack_start;
        int ret;

        ret = get_thread_stack(threads[i].id, &stack_start, &stack_size);
        assert(ret == 1);

        if (!state.is_xen) {
            jname = (*env)->NewStringUTF(env, threads[i].name);
            (*env)->CallStaticVoidMethod(env, c, gather_thread_method, threadSeq, threads[i].id, jname, toThreadState(&state), stack_start, stack_size);
        }
    }

    free_threads(threads, num_threads);

    return 0;
}

void trace_thread(struct thread *thread) {
    struct thread_state state = thread->state;
    if (trace && !state.is_xen) {
        debug_println("thread %s (%d), ra %d, r %d, dying %d, rds %d, ds %d, mw %d, nw %d, jw %d, sl %d",
            thread->name, thread->id, state.is_runnable, state.is_running, state.is_dying, state.is_req_debug_suspend, state.is_debug_suspend,
            state.is_aux1, state.is_aux2, state.is_joining, state.is_sleeping);
    }
}

void trace_threads(struct thread *threads, int num_threads) {
    int i;
    for(i=0; i<num_threads; i++) {
        trace_thread(&threads[i]);
    }
}

void gather_and_trace_threads(void) {
    struct thread *threads;
    int num_threads;
    if (terminated) return;
    threads = gather_threads(&num_threads);
    trace_threads(threads, num_threads);
    free_threads(threads, num_threads);
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeResume(JNIEnv *env, jobject domain, jint domainId) {
    unsigned long sleep_time = 0;
    struct thread *threads;
    int num_threads, i;
    struct thread_state *state;

    /* Gather threads first (to figure out which ones to resume) */
    if (trace) debug_println("checking which threads to resume");
    threads = gather_threads(&num_threads);
    trace_threads(threads, num_threads);
    for(i=0; i<num_threads; i++)
    {
        state = &threads[i].state;
        if((!state->is_runnable) && (!state->is_running) && (state->is_debug_suspend))
        {
            if (trace) debug_println("  resuming thread %s, %d", threads[i].name, threads[i].id);
            resume(threads[i].id);
        }
    }
    free_threads(threads, num_threads);
    /* Poll waiting for the thread to block */
again:
    if (trace) debug_println("waiting for a thread to block");
    threads = gather_threads(&num_threads);
    if (threads == NULL) {
        // target domain has explicitly terminated
        // send signoff
        db_signoff();
        terminated = 1;
        return 1;
    }
    trace_threads(threads, num_threads);

    for(i=0; i<num_threads; i++) {
        state = &threads[i].state;
        if((!state->is_runnable) && (!state->is_running) && (state->is_debug_suspend))
            goto out;
    }
    free_threads(threads, num_threads);
    sleep_time += 1000000;  // usecs
    usleep(sleep_time);
    goto again;

out:
// At this point at least one thread is debug_suspend'ed.
// Now suspend any other runnable threads.
// N.B. This is not an atomic operation and threads
// may become runnable, e.g., if a sleep expires
// or a driver thread is woken by an interrupt.
// However, those threads will debug_suspend themselves in that case.

    for(i=0; i<num_threads; i++) {
        int rc = 0;
        state = &threads[i].state;
        if (!state->is_xen && !state->is_debug_suspend) {
            if (trace) debug_println("suspending %s, %d", threads[i].name, threads[i].id);
            rc = suspend(threads[i].id);
        }
    }
    free_threads(threads, num_threads);
    threads = gather_threads(&num_threads);
    trace_threads(threads, num_threads);
    free_threads(threads, num_threads);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeGetBootHeapStart(JNIEnv *env, jclass c) {
    return app_specific1(0);
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeSetTransportDebugLevel(JNIEnv *env, jclass c, jint level) {
    return db_debug(level);
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeReadBytes(JNIEnv *env, jclass c, jlong address, jbyteArray byteArray, jint offset, jint length) {
    jbyte* buffer = (jbyte *) malloc(length * sizeof(jbyte));
    if (buffer == 0) {
        debug_println("failed to malloc buffer of %d bytes", length);
        return -1;
    }

    uint16_t bytesRead = readbytes(address, (char*)buffer, length);

    if (bytesRead > 0) {
        (*env)->SetByteArrayRegion(env, byteArray, offset, bytesRead, buffer);
    }
    free(buffer);
    return bytesRead;
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeWriteBytes(JNIEnv *env, jclass c, jlong address, jbyteArray byteArray, jint offset, jint length) {
    jbyte* buffer = (jbyte *) malloc(length * sizeof(jbyte));
    if (buffer == 0) {
        debug_println("failed to malloc byteArray of %d bytes", length);
        return -1;
    }

    (*env)->GetByteArrayRegion(env, byteArray, offset, length, buffer);
    if ((*env)->ExceptionOccurred(env) != NULL) {
        debug_println("failed to copy %d bytes from byteArray into buffer", length);
        return -1;
    }

    uint16_t bytesWritten = writebytes(address, (char*)buffer, length);
    free(buffer);
    return bytesWritten;
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_guestvm_xen_GuestVMXenDBChannel_nativeMaxByteBufferSize(JNIEnv *env, jclass c) {
	return multibytebuffersize();
}

void teleProcess_initialize(void)
{
    debug_println("teleProcess_initialize for guestvmXen");
}
